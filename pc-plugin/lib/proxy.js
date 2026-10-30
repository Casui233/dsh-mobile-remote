/**
 * 认证反向代理：把手机的 HTTP / WebSocket 转发到本机 loopback 上的 DSH。
 *
 * 只做三件事：
 *   1. 用内部登录 cookie 换掉手机的设备 cookie；
 *   2. 把 Host / Origin 改成内部 authority（否则会被 DSH 的信任栅栏 403）；
 *   3. 流式双向转发，不缓冲请求体或响应体。
 *
 * 认证（设备 token）在 index.js 的入口层完成；这里假定调用方已经验过设备。
 */

import http from 'node:http'
import https from 'node:https'
import net from 'node:net'
import tls from 'node:tls'
import { rewriteLocation, sanitizeRequestHeaders } from './core.js'

/**
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {unknown} value
 */
export function sendJson(res, status, value) {
  let body = ''
  try {
    body = JSON.stringify(value)
  } catch {
    body = '{"ok":false,"error":"response-not-serializable"}'
  }
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(body),
  })
  res.end(body)
}

/**
 * @param {{
 *   getTarget: () => { protocol: string, hostname: string, port: number, host: string, origin: string },
 *   getCookie: () => string | null,
 *   refreshCookie: () => Promise<string>,
 *   log?: (message: string) => void,
 * }} options
 */
export function createProxy(options) {
  const log = options.log || (() => {})
  const getTarget = options.getTarget
  const getCookie = options.getCookie
  const refreshCookie = options.refreshCookie

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   * @param {number} attempt
   */
  async function proxyAttempt(req, res, attempt) {
    const target = getTarget()
    const cookie = getCookie()
    if (!cookie) {
      sendJson(res, 503, { ok: false, error: '桥接还没拿到 DSH 的内部登录态，请稍后重试' })
      return
    }

    const headers = sanitizeRequestHeaders(req.headers, target, cookie)
    const transport = target.protocol === 'https:' ? https : http
    const noBody = req.method === 'GET' || req.method === 'HEAD'

    const upstream = transport.request(
      {
        hostname: target.hostname,
        port: target.port,
        method: req.method,
        path: req.url,
        headers,
        timeout: 0,
      },
      (upstreamRes) => {
        // 内部 cookie 过期：无 body 的请求可以安全重放一次。
        if (upstreamRes.statusCode === 401 && attempt === 0 && noBody) {
          upstreamRes.resume()
          refreshCookie()
            .then(() => proxyAttempt(req, res, 1))
            .catch((error) => {
              log(`内部登录态刷新失败：${error?.message ?? error}`)
              sendJson(res, 502, { ok: false, error: 'DSH 内部登录态失效，请在电脑端检查桥接插件' })
            })
          return
        }

        const outHeaders = { ...upstreamRes.headers }
        delete outHeaders['set-cookie']
        if (outHeaders.location) outHeaders.location = rewriteLocation(outHeaders.location, target.origin)
        res.writeHead(upstreamRes.statusCode || 502, outHeaders)
        upstreamRes.pipe(res)
      },
    )

    upstream.on('timeout', () => {
      upstream.destroy(new Error('upstream timeout'))
    })
    upstream.on('error', (error) => {
      log(`代理请求失败：${req.method} ${req.url} -> ${error?.message ?? error}`)
      if (!res.headersSent) {
        sendJson(res, 502, { ok: false, error: '无法连接本机 DSH（它可能正在重启）' })
      } else {
        res.destroy()
      }
    })

    res.on('close', () => {
      try {
        upstream.destroy()
      } catch {
        /* 已断开 */
      }
    })

    if (noBody) upstream.end()
    else req.pipe(upstream)
  }

  /**
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:http').ServerResponse} res
   */
  async function handleHttp(req, res) {
    try {
      await proxyAttempt(req, res, 0)
    } catch (error) {
      log(`代理异常：${error?.message ?? error}`)
      if (!res.headersSent) sendJson(res, 502, { ok: false, error: '桥接代理异常' })
      else res.destroy()
    }
  }

  /**
   * WebSocket / 其它 upgrade：整段 TCP 透传，只改握手头。
   * @param {import('node:http').IncomingMessage} req
   * @param {import('node:stream').Duplex} socket
   * @param {Buffer} head
   */
  function handleUpgrade(req, socket, head) {
    const cookie = getCookie()
    if (!cookie) {
      try {
        socket.write('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n')
      } catch {
        /* 忽略 */
      }
      socket.destroy()
      return
    }

    const target = getTarget()
    const connect = target.protocol === 'https:' ? tls.connect : net.connect
    const upstream = connect({ host: target.hostname, port: target.port }, () => {
      const lines = [`${req.method} ${req.url} HTTP/${req.httpVersion}`]
      const raw = req.rawHeaders || []
      for (let index = 0; index + 1 < raw.length; index += 2) {
        const name = raw[index]
        const value = raw[index + 1]
        const lower = String(name).toLowerCase()
        if (lower === 'host') lines.push(`Host: ${target.host}`)
        else if (lower === 'cookie') lines.push(`Cookie: ${cookie}`)
        else if (lower === 'origin') lines.push(`Origin: ${target.origin}`)
        else if (lower === 'referer') lines.push(`Referer: ${target.origin}/`)
        else lines.push(`${name}: ${value}`)
      }
      upstream.write(`${lines.join('\r\n')}\r\n\r\n`)
      if (head && head.length > 0) upstream.write(head)
      upstream.pipe(socket)
      socket.pipe(upstream)
    })

    upstream.on('error', (error) => {
      log(`WebSocket 代理失败：${error?.message ?? error}`)
      try {
        socket.destroy()
      } catch {
        /* 忽略 */
      }
    })
    socket.on('error', () => {
      try {
        upstream.destroy()
      } catch {
        /* 忽略 */
      }
    })
    socket.on('close', () => {
      try {
        upstream.destroy()
      } catch {
        /* 忽略 */
      }
    })
  }

  return { handleHttp, handleUpgrade }
}
