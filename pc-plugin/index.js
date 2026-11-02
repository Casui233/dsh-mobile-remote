/**
 * dsh-mobile-bridge —— Desktop 半端。
 *
 * 做四件事：
 *   1. 在 Tailscale 地址上开第二个监听（DSH 自己的监听器只绑 loopback，Desktop
 *      又不给用户传 --host/--trusted-host 的入口，所以只能由插件自己开）；
 *   2. 用 6 位配对码换设备 token，并把设备请求反向代理到本机 loopback 的 DSH；
 *   3. 在 /api/mobile/* 暴露一组“只有本机已登录页面才能调”的管理接口（设置页用）；
 *   4. 监听 Session 事件，把“完成 / 出错 / 待审批 / 待回答”推到 ntfy / Bark / Telegram。
 *
 * 设计取舍见 README.md；这里不 import 任何 @deepseek-ai/*，避免 Desktop 的
 * pnpm 布局把整条插件卡在 import 阶段。
 */

import http from 'node:http'
import os from 'node:os'
import QRCode from 'qrcode'
import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs'
import { basename, join } from 'node:path'

import {
  DEFAULTS,
  clampConfig,
  detectTailscaleHost,
  deviceTokenFromHeaders,
  hostForUrl,
  isLoopbackHost,
  isQuietHours,
  isTailscaleAddress,
  isWildcardHost,
  normalizeOrigin,
  pickConfigKeys,
  buildNotifyRequest,
  notifyConfigForClient,
} from './lib/core.js'
import { createDeviceStore, createPairingCodes } from './lib/store.js'
import { createFileService } from './lib/files.js'
import { createClipboard } from './lib/clipboard.js'
import { createRemoteActions } from './lib/remote-actions.js'
import { createProxy, sendJson } from './lib/proxy.js'

export const name = 'mobile-bridge'
/** Desktop 的 web 组合里这两个服务一定在；缺任何一个说明装错了 profile。 */
export const inject = ['webServer', 'connection']

const INTERNAL_COOKIE_MAX_AGE_MS = 6 * 3600 * 1000
const PAIR_RATE_WINDOW_MS = 10 * 60 * 1000
const PAIR_RATE_MAX = 8
const LOG_MAX_BYTES = 1024 * 1024

export function apply(ctx, config) {
  const home = resolveDshHome()
  const stateDir = join(home, 'mobile-bridge')
  try {
    mkdirSync(stateDir, { recursive: true })
  } catch {
    /* 目录建不出来时后续写文件会报错，日志里能看到 */
  }

  const logFile = join(stateDir, 'bridge.log')
  const log = (message) => {
    const line = `${new Date().toISOString()} ${message}\n`
    try {
      if (existsSync(logFile) && statSync(logFile).size > LOG_MAX_BYTES) writeFileSync(logFile, '', 'utf8')
      appendFileSync(logFile, line, 'utf8')
    } catch {
      /* 日志失败不影响主流程 */
    }
    console.log(`[mobile-bridge] ${message}`)
  }

  const configFile = join(stateDir, 'config.json')
  let cfg = loadConfig(configFile, config, log)
  log(`启动：state=${stateDir} bind=${cfg.bindHost || '(自动)'} port=${cfg.port}`)

  const store = createDeviceStore(stateDir, { log })
  const pairing = createPairingCodes()
  const files = createFileService({ getRoot: () => cfg.sharedDir, log })
  const clipboard = createClipboard()
  const remoteActions = createRemoteActions({ ttlMs: 120000 })
  /** @type {Map<string, { count: number, resetAt: number }>} */
  const pairAttempts = new Map()

  /** @type {string | null} */
  let internalCookie = null
  let internalCookieAt = 0
  let internalCookieError = null
  /** @type {Promise<string> | null} */
  let refreshing = null

  const summaries = new Map()
  const runningSince = new Map()

  const listener = {
    server: /** @type {import('node:http').Server | null} */ (null),
    host: /** @type {string | null} */ (null),
    port: /** @type {number | null} */ (null),
    error: /** @type {string | null} */ (null),
    /** Node 不把 upgrade 后的 socket 计入 server.close() 的等待集合，必须自己收。 */
    sockets: new Set(),
  }

  // ── 内部 loopback 目标与登录态 ────────────────────────────────────────────

  function getTarget() {
    const origin = ctx.webServer.getOrigin()
    const url = new URL(origin)
    return {
      protocol: url.protocol,
      hostname: url.hostname,
      port: Number(url.port || (url.protocol === 'https:' ? 443 : 80)),
      host: url.host,
      origin: url.origin,
    }
  }

  async function ensureInternalCookie(force = false) {
    if (!force && internalCookie && Date.now() - internalCookieAt < INTERNAL_COOKIE_MAX_AGE_MS) {
      return internalCookie
    }
    if (refreshing) return refreshing

    refreshing = (async () => {
      const target = getTarget()
      if (typeof ctx.connection?.authenticatedUrl !== 'function') {
        throw new Error('当前 DSH 的 connection 服务没有 authenticatedUrl（版本不匹配？）')
      }
      const tokenUrl = ctx.connection.authenticatedUrl(target.origin)
      const response = await fetch(tokenUrl, {
        redirect: 'manual',
        headers: { accept: 'text/html' },
        signal: AbortSignal.timeout(8000),
      })
      const raw = typeof response.headers.getSetCookie === 'function'
        ? response.headers.getSetCookie()
        : (response.headers.get('set-cookie') ? [String(response.headers.get('set-cookie'))] : [])
      const pairs = raw
        .map((line) => String(line).split(';')[0])
        .filter((pair) => pair.includes('='))
      if (pairs.length === 0) {
        throw new Error(`DSH 没有返回登录 cookie（HTTP ${response.status}）；启动 token 兑换可能变了`)
      }
      const cookie = pairs.join('; ')

      const check = await fetch(`${target.origin}/`, {
        redirect: 'manual',
        headers: { cookie },
        signal: AbortSignal.timeout(8000),
      })
      if (check.status === 401) throw new Error('内部 cookie 回读校验 401')

      internalCookie = cookie
      internalCookieAt = Date.now()
      internalCookieError = null
      log(`内部登录态已建立（${target.origin}）`)
      return cookie
    })()
      .catch((error) => {
        internalCookie = null
        internalCookieError = String(error?.message ?? error)
        log(`内部登录态建立失败：${internalCookieError}`)
        throw error
      })
      .finally(() => {
        refreshing = null
      })

    return refreshing
  }

  const proxy = createProxy({
    getTarget,
    getCookie: () => internalCookie,
    refreshCookie: async () => ensureInternalCookie(true),
    log,
  })

  // ── 手机侧监听器 ──────────────────────────────────────────────────────────

  function resolveBindHost() {
    const configured = cfg.bindHost.trim()
    return configured || detectTailscaleHost(os.networkInterfaces()) || ''
  }

  function publicOrigin() {
    const configured = normalizeOrigin(cfg.publicOrigin)
    if (configured) return configured
    const host = listener.host || resolveBindHost()
    if (!host) return ''
    return `http://${hostForUrl(host)}:${cfg.port}`
  }

  function startListener() {
    if (listener.server) return
    listener.error = null
    if (!cfg.enabled) {
      listener.error = '已禁用'
      return
    }
    const host = resolveBindHost()
    if (!host) {
      listener.error = '没找到 Tailscale 地址：请安装/登录 Tailscale，或在设置页填写绑定地址'
      log(listener.error)
      return
    }
    if (isWildcardHost(host)) {
      listener.error = '拒绝绑定通配地址；请填一个具体的 Tailscale IPv4'
      log(listener.error)
      return
    }
    if (!isLoopbackHost(host) && !isTailscaleAddress(host) && !cfg.allowNonTailscaleBind) {
      listener.error = `拒绝绑定 ${host}：它不在 Tailscale 网段（100.64.0.0/10）。确要这样做请打开 allowNonTailscaleBind`
      log(listener.error)
      return
    }

    const server = http.createServer((req, res) => {
      Promise.resolve(handleBridgeRequest(req, res)).catch((error) => {
        log(`请求处理异常：${error?.message ?? error}`)
        if (!res.headersSent) sendJson(res, 500, { ok: false, error: '桥接内部错误' })
        else res.destroy()
      })
    })

    server.on('connection', (socket) => {
      listener.sockets.add(socket)
      socket.on('close', () => listener.sockets.delete(socket))
    })

    server.on('upgrade', (req, socket, head) => {
      try {
        if (!authenticateDevice(req)) {
          socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n')
          socket.destroy()
          return
        }
        proxy.handleUpgrade(req, socket, head)
      } catch (error) {
        log(`upgrade 处理异常：${error?.message ?? error}`)
        try {
          socket.destroy()
        } catch {
          /* 忽略 */
        }
      }
    })

    server.on('error', (error) => {
      listener.error = String(error?.message ?? error)
      log(`监听失败：${listener.error}`)
      try {
        server.close()
      } catch {
        /* 忽略 */
      }
      if (listener.server === server) listener.server = null
    })

    server.listen(cfg.port, host, () => {
      listener.server = server
      listener.host = host
      listener.port = cfg.port
      listener.error = null
      log(`手机监听已就绪：http://${hostForUrl(host)}:${cfg.port}`)
      ensureInternalCookie().catch(() => {})
    })
  }

  function stopListener() {
    return new Promise((resolve) => {
      const server = listener.server
      listener.server = null
      listener.host = null
      listener.port = null
      for (const socket of listener.sockets) {
        try {
          socket.destroy()
        } catch {
          /* 忽略 */
        }
      }
      listener.sockets.clear()
      if (!server) {
        resolve()
        return
      }
      try {
        server.close(() => resolve())
        server.closeAllConnections?.()
      } catch {
        resolve()
      }
      // 兜底：upgrade 后的半开连接理论上已 destroy，这里再保一次不挂死卸载流程。
      const timer = setTimeout(resolve, 1500)
      timer.unref?.()
    })
  }

  async function restartListener() {
    await stopListener()
    startListener()
  }

  // ── 手机侧入口：health / pair / 代理 ──────────────────────────────────────

  /**
   * 设备认证：成功时返回设备行，并刷新活跃时间。
   * @param {import('node:http').IncomingMessage} req
   */
  function authenticateDevice(req) {
    const device = store.verify(deviceTokenFromHeaders(req.headers))
    if (!device) return null
    store.touch(device)
    return device
  }

  async function handleBridgeRequest(req, res) {
    const url = new URL(req.url || '/', 'http://bridge.local')

    if (url.pathname === '/health') {
      sendJson(res, 200, {
        ok: true,
        service: 'dsh-mobile-bridge',
        version: '0.1.1',
        name: 'DSH Mobile Bridge',
        features: ['clipboard', 'files', 'remote-actions'],
      })
      return
    }

    if (url.pathname === '/pair') {
      if (req.method === 'GET' || req.method === 'HEAD') {
        sendHtml(res, 200, pairingPage(''))
        return
      }
      if (req.method !== 'POST') {
        sendJson(res, 405, { ok: false, error: '请用 GET /pair 打开配对页，或 POST /pair 提交' })
        return
      }
      await handlePair(req, res)
      return
    }

    if (url.pathname === '/notify') {
      if (req.method !== 'GET') {
        sendJson(res, 405, { ok: false, error: '请用 GET /notify' })
        return
      }
      if (!authenticateDevice(req)) {
        sendJson(res, 401, { ok: false, error: 'device not paired' })
        return
      }
      sendJson(res, 200, { ok: true, notify: notifyConfigForClient(cfg) })
      return
    }

    if (url.pathname === '/remote-action') {
      if (!authenticateDevice(req)) {
        sendJson(res, 401, { ok: false, error: 'device not paired' })
        return
      }
      await remoteActions.handle(req, res, url)
      return
    }

    if (url.pathname === '/clipboard') {
      if (!authenticateDevice(req)) {
        sendJson(res, 401, { ok: false, error: 'device not paired' })
        return
      }
      await clipboard.handle(req, res, url)
      return
    }

    if (url.pathname === '/files' || url.pathname.startsWith('/files/')) {
      if (!authenticateDevice(req)) {
        sendJson(res, 401, { ok: false, error: 'device not paired' })
        return
      }
      if (url.pathname === '/files' || url.pathname === '/files/') {
        sendHtml(res, 200, files.page())
        return
      }
      await files.handle(req, res, url)
      return
    }

    if (!authenticateDevice(req)) {
      const wantsHtml = String(req.headers.accept || '').includes('text/html')
      if (wantsHtml && req.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
        res.writeHead(302, { location: '/pair', 'cache-control': 'no-store' })
        res.end()
        return
      }
      sendJson(res, 401, {
        ok: false,
        error: '这台设备还没配对：请在手机上重新输入配对码',
      })
      return
    }

    await proxy.handleHttp(req, res)
  }

  async function handlePair(req, res) {
    const contentType = String(req.headers['content-type'] || '')
    const isJson = contentType.includes('application/json')

    const ip = clientIp(req)
    const now = Date.now()
    const rate = pairAttempts.get(ip)
    if (!rate || now > rate.resetAt) {
      pairAttempts.set(ip, { count: 0, resetAt: now + PAIR_RATE_WINDOW_MS })
    }
    const bucket = pairAttempts.get(ip)
    if (bucket.count >= PAIR_RATE_MAX) {
      if (isJson) sendJson(res, 429, { ok: false, error: '配对尝试过于频繁，请 10 分钟后再试' })
      else sendHtml(res, 429, pairingPage('配对尝试过于频繁，请 10 分钟后再试'))
      return
    }
    bucket.count += 1

    let body
    try {
      if (isJson) {
        body = await readJsonBody(req, 8192)
      } else {
        const text = await readBodyText(req, 8192)
        const params = new URLSearchParams(text)
        body = { code: params.get('code'), name: params.get('name'), platform: 'browser' }
      }
    } catch (error) {
      if (isJson) sendJson(res, 400, { ok: false, error: String(error?.message ?? error) })
      else sendHtml(res, 400, pairingPage(String(error?.message ?? error)))
      return
    }

    const result = pairing.verify(body?.code)
    if (!result.ok) {
      if (isJson) sendJson(res, 403, { ok: false, error: result.reason })
      else sendHtml(res, 403, pairingPage(result.reason))
      return
    }

    try {
      const created = store.add({
        name: body?.name || (isJson ? undefined : '手机浏览器'),
        platform: body?.platform || (isJson ? 'android' : 'browser'),
      })
      log(`新设备配对：${created.device.name}（${created.device.platform}）`)
      if (isJson) {
        sendJson(res, 200, {
          ok: true,
          deviceId: created.device.id,
          token: created.token,
          origin: publicOrigin(),
          notify: notifyConfigForClient(cfg),
        })
        return
      }
      res.writeHead(302, {
        location: '/',
        'set-cookie': deviceCookieHeader(created.token),
        'cache-control': 'no-store',
      })
      res.end()
    } catch (error) {
      if (isJson) sendJson(res, 400, { ok: false, error: String(error?.message ?? error) })
      else sendHtml(res, 400, pairingPage(String(error?.message ?? error)))
    }
  }

  // ── 管理接口（/api/mobile/*，只有本机已登录页面 / 已代理的手机能调） ──────

  function rejectUntrusted(req, res) {
    try {
      if (typeof ctx.connection?.requestRejection === 'function') {
        const rejection = ctx.connection.requestRejection({ headers: req.headers })
        if (rejection !== undefined) {
          res.writeHead(typeof rejection === 'number' ? rejection : 403)
          res.end()
          return true
        }
      }
    } catch (error) {
      log(`信任栅栏调用失败：${error?.message ?? error}`)
      res.writeHead(403)
      res.end()
      return true
    }

    const host = String(req.headers.host || '')
    const authority = host.replace(/:\d+$/u, '').toLowerCase()
    if (host && !['127.0.0.1', 'localhost', '[::1]', '::1'].includes(authority)) {
      res.writeHead(403)
      res.end()
      return true
    }
    const origin = String(req.headers.origin || req.headers.referer || '')
    if (origin) {
      try {
        const parsed = new URL(origin)
        const name = parsed.hostname.replace(/^\[|\]$/gu, '').toLowerCase()
        if (!['127.0.0.1', 'localhost', '::1'].includes(name)) {
          res.writeHead(403)
          res.end()
          return true
        }
      } catch {
        res.writeHead(403)
        res.end()
        return true
      }
    }
    return false
  }

  function publicStatus() {
    const devices = store.list()
    const pairingPeek = pairing.peek()
    return {
      ok: true,
      enabled: cfg.enabled,
      listener: {
        host: listener.host,
        port: listener.port,
        url: publicOrigin(),
        error: listener.error,
        healthy: Boolean(listener.server),
      },
      internalAuth: {
        ok: Boolean(internalCookie),
        error: internalCookieError,
        at: internalCookieAt || null,
      },
      detect: {
        tailscale: detectTailscaleHost(os.networkInterfaces()) || '',
      },
      publicOrigin: publicOrigin(),
      devices,
      pairing: pairingPeek,
      config: publicConfig(cfg),
      version: '0.1.0',
    }
  }

  function registerManagementRoute() {
    return ctx.webServer.register({
      kind: 'prefix',
      path: '/api/mobile',
      handler: async (req, res) => {
        if (rejectUntrusted(req, res)) return
        const url = new URL(req.url || '/', 'http://127.0.0.1')
        try {
          if (req.method === 'GET' && url.pathname === '/api/mobile/status') {
            sendJson(res, 200, publicStatus())
            return
          }

          if (req.method === 'GET' && url.pathname === '/api/mobile/pair-qr') {
            const current = pairing.peek()
            const origin = publicOrigin()
            if (!current || !origin) {
              sendJson(res, 404, { ok: false, error: '当前没有有效配对码' })
              return
            }
            const payload = `deh://pair?origin=${encodeURIComponent(origin)}&code=${current.code}`
            const svg = await QRCode.toString(payload, {
              type: 'svg',
              margin: 1,
              width: 240,
              errorCorrectionLevel: 'M',
              color: { dark: '#0f1115', light: '#ffffff' },
            })
            res.writeHead(200, {
              'content-type': 'image/svg+xml; charset=utf-8',
              'cache-control': 'no-store',
            })
            res.end(svg)
            return
          }

          if (req.method === 'POST' && url.pathname === '/api/mobile/pair-code') {
            const minted = pairing.mint()
            log('生成新的配对码')
            sendJson(res, 200, { ok: true, ...minted })
            return
          }

          if (req.method === 'POST' && url.pathname === '/api/mobile/revoke') {
            const body = await readJsonBody(req, 8192)
            let count = 0
            if (body?.all === true) count = store.revokeAll()
            else if (body?.deviceId) count = store.revoke(String(body.deviceId)) ? 1 : 0
            log(`撤销设备：${count} 台`)
            sendJson(res, 200, { ok: true, revoked: count })
            return
          }

          if (req.method === 'POST' && url.pathname === '/api/mobile/config') {
            const body = await readJsonBody(req, 16 * 1024)
            const result = applyConfigUpdate(body)
            sendJson(res, 200, { ok: true, ...result })
            return
          }

          if (req.method === 'POST' && url.pathname === '/api/mobile/test-notify') {
            const result = await sendNote(
              {
                title: 'DSH 测试通知',
                body: '看到这条，说明手机推送通了。',
                click: publicOrigin() || undefined,
              },
              { force: true },
            )
            sendJson(res, 200, result)
            return
          }

          sendJson(res, 404, { ok: false, error: 'unknown mobile endpoint' })
        } catch (error) {
          log(`管理接口失败：${error?.message ?? error}`)
          sendJson(res, 500, { ok: false, error: String(error?.message ?? error) })
        }
      },
    })
  }

  function applyConfigUpdate(body) {
    const patch = { ...pickConfigKeys(body?.config || body || {}) }
    if (body && typeof body === 'object') {
      if (typeof body.notifyToken === 'string' && body.notifyToken.trim()) patch.notifyToken = body.notifyToken.trim()
      if (body.clearNotifyToken === true) patch.notifyToken = ''
    }

    const before = { bindHost: cfg.bindHost, port: cfg.port, enabled: cfg.enabled, allowNonTailscaleBind: cfg.allowNonTailscaleBind }
    cfg = clampConfig({ ...cfg, ...patch }, cfg)
    persistConfig(configFile, cfg, log)

    const listenerChanged =
      before.bindHost !== cfg.bindHost ||
      before.port !== cfg.port ||
      before.enabled !== cfg.enabled ||
      before.allowNonTailscaleBind !== cfg.allowNonTailscaleBind

    if (listenerChanged) {
      restartListener().catch((error) => log(`重启监听失败：${error?.message ?? error}`))
    }

    log(`配置已更新：${Object.keys(patch).join(', ') || '(无变化)'}`)
    return { config: publicConfig(cfg), listenerChanged, status: publicStatus() }
  }

  // ── 通知 ─────────────────────────────────────────────────────────────────

  /**
   * @param {{ title: string, body: string, click?: string }} note
   * @param {{ force?: boolean }} [options]
   */
  async function sendNote(note, options = {}) {
    if (!options.force && cfg.notifyChannel === 'off') return { ok: true, skipped: 'channel-off' }
    const quiet = isQuietHours(cfg.notifyQuietHours, new Date())
    const request = buildNotifyRequest(cfg, { ...note, quiet })
    if (!request) return { ok: true, skipped: 'not-configured' }
    try {
      const response = await fetch(request.url, { ...request.init, signal: AbortSignal.timeout(15000) })
      if (!response.ok) {
        log(`通知失败：${request.channel} HTTP ${response.status}`)
        return { ok: false, error: `${request.channel} HTTP ${response.status}` }
      }
      log(`通知已发送：${request.channel}`)
      return { ok: true, channel: request.channel }
    } catch (error) {
      log(`通知异常：${request.channel} ${error?.message ?? error}`)
      return { ok: false, error: String(error?.message ?? error) }
    }
  }

  function sessionLabel(sessionId) {
    const summary = summaries.get(String(sessionId))
    const dir = summary?.cwd ? basename(String(summary.cwd)) : ''
    const short = String(sessionId || '').slice(0, 8)
    return [dir, short ? `会话 ${short}` : ''].filter(Boolean).join(' · ') || 'DSH 会话'
  }

  const NEVER = new Promise(() => {})

  function questionPayload(request) {
    return (request?.questions || []).map((question) => ({
      id: String(question?.id || ''),
      header: String(question?.header || ''),
      question: String(question?.question || ''),
      multiSelect: Boolean(question?.multiSelect),
      options: (question?.options || []).map((option) => ({
        label: String(option?.label || ''),
        description: option?.description ? String(option.description) : '',
      })),
    }))
  }

  async function raceRemoteAction(kind, next, note, payload, actions) {
    const uiRaw = Promise.resolve().then(() => (typeof next === 'function' ? next() : 'unavailable'))
    let uiUnavailable = false
    let uiError = null
    const ui = uiRaw.then((value) => {
      if (kind === 'approval' && value === 'unavailable') {
        uiUnavailable = true
        return NEVER
      }
      return value
    }).catch((error) => {
      uiError = error
      if (kind === 'question') uiUnavailable = true
      return NEVER
    })

    const action = remoteActions.create(kind, payload)
    const sent = await sendNote({ ...note, actions: actions(action.token) })
    if (!sent?.ok) {
      remoteActions.cancel(action.token)
      return await uiRaw
    }

    const timeout = new Promise((resolve) => {
      const timer = setTimeout(() => resolve({ source: 'timeout' }), 120000)
      timer.unref?.()
    })
    const winner = await Promise.race([
      ui.then((value) => ({ source: 'ui', value })),
      action.promise.then((value) => ({ source: 'remote', value })),
      timeout,
    ])
    if (winner.source === 'remote') return winner.value
    if (winner.source === 'ui') {
      remoteActions.cancel(action.token)
      return winner.value
    }
    remoteActions.cancel(action.token)
    if (uiError) throw uiError
    if (uiUnavailable) {
      if (kind === 'approval') return 'unavailable'
      throw new Error('no user-questions answerer')
    }
    return await ui
  }

  function registerEventListeners() {
    const disposers = []

    // 监听器全部常驻，分类开关在回调里读实时 cfg——否则设置页刚打开的通知类型
    // 要等到插件重载才生效。
    disposers.push(ctx.on('api-session/added', (summary) => {
      if (summary?.sessionId) summaries.set(String(summary.sessionId), summary)
    }))
    disposers.push(ctx.on('api-session/removed', (sessionId) => {
      summaries.delete(String(sessionId))
      runningSince.delete(String(sessionId))
    }))

    disposers.push(ctx.on('api-session/status', (sessionId, running) => {
      const id = String(sessionId)
      if (running) {
        runningSince.set(id, Date.now())
        return
      }
      const startedAt = runningSince.get(id)
      runningSince.delete(id)
      if (!cfg.notifyDone || !startedAt) return
      if (Date.now() - startedAt < cfg.notifyMinRunMs) return
      void sendNote({
        title: '任务完成',
        body: sessionLabel(id),
        click: publicOrigin() || undefined,
      })
    }))

    disposers.push(ctx.on('agent/error', (payload) => {
      if (!cfg.notifyError) return
      const id = String(payload?.agent?.id || '')
      const detail = String(payload?.error?.message ?? payload?.error ?? '').slice(0, 200)
      void sendNote({
        title: '任务出错',
        body: [sessionLabel(id), detail].filter(Boolean).join('\n'),
        click: publicOrigin() || undefined,
      })
    }))

    disposers.push(ctx.on('approval/request', (request, next) => {
      try {
        if (!cfg.notifyApproval) return typeof next === 'function' ? next() : undefined
        const reason = request?.displayReason?.zh
          || request?.displayReason?.en
          || request?.reason
          || request?.toolName
        const body = [sessionLabel(request?.agent?.id), reason].filter(Boolean).join('\n')
        const note = {
          title: '需要你批准',
          body,
          click: publicOrigin() || undefined,
        }
        if (cfg.notifyChannel === 'ntfy') {
          return raceRemoteAction('approval', next, note, { title: note.title, body }, (token) => [
            { action: 'view', label: '批准', url: `deh://remote-approval?token=${token}&outcome=allowed-once`, clear: true },
            { action: 'view', label: '拒绝', url: `deh://remote-approval?token=${token}&outcome=rejected`, clear: true },
          ])
        }
        void sendNote(note)
      } catch (error) {
        log(`审批通知失败：${error?.message ?? error}`)
      }
      return typeof next === 'function' ? next() : undefined
    }))

    disposers.push(ctx.on('user-questions/request', (request, next) => {
      try {
        if (!cfg.notifyQuestion) return typeof next === 'function' ? next() : undefined
        const first = request?.questions?.[0]
        const text = [first?.header, first?.question].filter(Boolean).join('：') || '需要你回答一个问题'
        const body = [sessionLabel(request?.agent?.id), text.slice(0, 200)].filter(Boolean).join('\n')
        const note = {
          title: '需要你回答',
          body,
          click: publicOrigin() || undefined,
        }
        if (cfg.notifyChannel === 'ntfy') {
          const questions = questionPayload(request)
          return raceRemoteAction('question', next, note,
            { title: note.title, body, questions },
            (token) => [
              { action: 'view', label: '回答', url: `deh://remote-question?token=${token}`, clear: true },
            ])
        }
        void sendNote(note)
      } catch (error) {
        log(`提问通知失败：${error?.message ?? error}`)
      }
      return typeof next === 'function' ? next() : undefined
    }))

    return () => {
      for (const dispose of disposers) {
        try {
          if (typeof dispose === 'function') dispose()
        } catch {
          /* 忽略 */
        }
      }
    }
  }

  // ── 生命周期 ─────────────────────────────────────────────────────────────

  ctx.effect(() => {
    ensureInternalCookie().catch(() => {})
    const timer = setInterval(() => {
      ensureInternalCookie(true).catch(() => {})
    }, INTERNAL_COOKIE_MAX_AGE_MS)
    timer.unref?.()
    return () => clearInterval(timer)
  }, 'mobile-bridge: internal login refresh')

  ctx.effect(() => {
    startListener()
    return () => {
      stopListener().catch(() => {})
    }
  }, 'mobile-bridge: tailscale listener')

  const disposeRoute = registerManagementRoute()
  const disposeEvents = registerEventListeners()
  ctx.effect(() => () => {
    try {
      disposeRoute()
    } catch {
      /* 忽略 */
    }
    try {
      disposeEvents()
    } catch {
      /* 忽略 */
    }
    void stopListener()
  }, 'mobile-bridge: routes and events')

  log('已挂载')
}

// ── 文件与工具函数 ─────────────────────────────────────────────────────────

function resolveDshHome() {
  const env = String(process.env.DSH_HOME || '').trim()
  return env || join(os.homedir(), '.dsh')
}

function loadConfig(file, rowConfig, log) {
  let saved = {}
  if (existsSync(file)) {
    try {
      const text = readFileSync(file, 'utf8').replace(/^\uFEFF/u, '')
      saved = JSON.parse(text)
    } catch (error) {
      log(`配置文件读取失败（用默认值）：${error?.message ?? error}`)
    }
  }
  return clampConfig({ ...DEFAULTS, ...(rowConfig || {}), ...(saved || {}) }, DEFAULTS)
}

function persistConfig(file, config, log) {
  try {
    const temp = `${file}.tmp`
    writeFileSync(temp, `${JSON.stringify(config, null, 2)}\n`, 'utf8')
    renameSync(temp, file)
  } catch (error) {
    log(`配置文件写入失败：${error?.message ?? error}`)
  }
}

function publicConfig(config) {
  const { notifyToken, ...rest } = config
  return { ...rest, notifyTokenSet: Boolean(notifyToken) }
}

function deviceCookieHeader(token) {
  return `dshmb=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000`
}

/** @param {import('node:http').ServerResponse} res */
function sendHtml(res, status, html) {
  res.writeHead(status, {
    'content-type': 'text/html; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': Buffer.byteLength(html),
  })
  res.end(html)
}

function htmlEscape(value) {
  return String(value).replace(/[&<>"']/gu, (ch) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  })[ch])
}

/** 浏览器直接配对的页面（不依赖 App）。 */
function pairingPage(error) {
  const message = error
    ? `<p class="error">${htmlEscape(error)}</p>`
    : ''
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>配对 · DSH 手机桥接</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
  background:#0f1115;color:#e8eaed;font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;padding:24px}
main{width:100%;max-width:380px;background:#171a21;border:1px solid #262b36;border-radius:14px;padding:22px}
h1{font-size:18px;margin:0 0 6px}
.hint{color:#9aa3b2;font-size:13px;margin:0 0 16px}
.error{background:rgba(234,67,53,.16);color:#ff9b90;font-size:13px;padding:8px 10px;border-radius:8px;margin:0 0 14px}
label{display:block;font-size:13px;color:#c7ccd6;margin:12px 0 6px}
input{width:100%;padding:10px 12px;border-radius:9px;border:1px solid #2b3242;background:#0f1115;color:inherit;font:inherit}
input:focus{outline:none;border-color:#3b82f6}
button{width:100%;margin-top:18px;padding:11px;border:0;border-radius:9px;background:#3b82f6;color:#fff;font:inherit;font-weight:600;cursor:pointer}
button:active{opacity:.85}
button.scan{background:#2a3242;color:#c7ccd6;margin-top:10px}
</style></head><body><main>
<h1>连接电脑上的 DSH</h1>
<p class="hint">在电脑上的 DSH「设置 → 手机连接」里生成 6 位配对码，然后填在这里。</p>
${message}
<form method="post" action="/pair">
  <label for="code">配对码</label>
  <input id="code" name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required>
  <label for="name">设备名（可选）</label>
  <input id="name" name="name" maxlength="40" placeholder="手机浏览器">
  <button type="button" id="scan" class="scan">扫码配对（App）</button>
  <button type="submit">配对</button>
</form>
<script>
(function () {
  var button = document.getElementById('scan')
  if (!window.DSHMobile || typeof window.DSHMobile.scanPair !== 'function') {
    button.style.display = 'none'
    return
  }
  button.addEventListener('click', function () {
    window.DSHMobile.scanPair()
  })
})()
</script>
</main></body></html>`
}

function clientIp(req) {
  const raw = String(req.socket?.remoteAddress || '')
  return raw.replace(/^::ffff:/u, '')
}

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {number} limit
 */
function readBodyText(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > limit) {
        reject(new Error('请求体过大'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8').trim()))
    req.on('error', reject)
  })
}

/**
 * @param {import('node:http').IncomingMessage} req
 * @param {number} limit
 */
async function readJsonBody(req, limit) {
  const text = await readBodyText(req, limit)
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    throw new Error('请求体不是合法 JSON')
  }
}
