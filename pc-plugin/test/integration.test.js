import assert from 'node:assert/strict'
import http from 'node:http'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { apply } from '../index.js'

function listen(server) {
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)))
}

function close(server) {
  for (const socket of server.__sockets || []) {
    try {
      socket.destroy()
    } catch {
      /* 忽略 */
    }
  }
  return new Promise((resolve) => {
    let done = false
    const finish = () => {
      if (done) return
      done = true
      resolve()
    }
    try {
      server.close(finish)
      server.closeAllConnections?.()
    } catch {
      finish()
    }
    const timer = setTimeout(finish, 1000)
    timer.unref?.()
  })
}

function track(server) {
  const sockets = new Set()
  server.__sockets = sockets
  server.on('connection', (socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
  })
  return server
}

function call(port, method, path, { body, headers, raw } = {}) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? undefined : JSON.stringify(body)
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port,
        method,
        path,
        headers: {
          host: `127.0.0.1:${port}`,
          ...(payload ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) } : {}),
          ...(headers || {}),
        },
      },
      (res) => {
        const chunks = []
        res.on('data', (chunk) => chunks.push(chunk))
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8')
          let json = null
          try {
            json = JSON.parse(text)
          } catch {
            json = null
          }
          resolve({ status: res.statusCode, headers: res.headers, text, json })
        })
      },
    )
    req.on('error', reject)
    if (raw !== undefined) req.end(raw)
    else req.end(payload)
  })
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function callForm(port, path, fields) {
  const payload = new URLSearchParams(fields || {}).toString()
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port,
        method: 'POST',
        path,
        headers: {
          host: `127.0.0.1:${port}`,
          'content-type': 'application/x-www-form-urlencoded',
          'content-length': Buffer.byteLength(payload),
        },
      },
      (res) => {
        const chunks = []
        res.on('data', (chunk) => chunks.push(chunk))
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text: Buffer.concat(chunks).toString('utf8') }))
      },
    )
    req.on('error', reject)
    req.end(payload)
  })
}

async function waitFor(fn, timeoutMs = 5000, intervalMs = 40) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const value = await fn()
    if (value) return value
    if (Date.now() > deadline) throw new Error('waitFor timeout')
    await delay(intervalMs)
  }
}

function freePort() {
  return new Promise((resolve) => {
    const server = http.createServer()
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port
      server.close(() => resolve(port))
    })
  })
}

test('端到端：健康检查 → 配对 → 设备代理 → 完成通知', async (t) => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-mobile-bridge-it-'))
  const previousHome = process.env.DSH_HOME
  process.env.DSH_HOME = dir

  /** @type {Map<string, any>} */
  const routes = new Map()
  const disposers = []
  const listeners = new Map()
  const cleanup = []
  let internalCookie = ''
  const internalRequests = []

  const internal = track(http.createServer((req, res) => {
    internalRequests.push({ url: req.url, host: req.headers.host, cookie: req.headers.cookie, origin: req.headers.origin })
    const url = new URL(req.url || '/', 'http://127.0.0.1')
    if (url.pathname.startsWith('/api/mobile')) {
      const route = routes.get('/api/mobile')
      if (!route) {
        res.writeHead(404)
        res.end()
        return
      }
      route.handler(req, res)
      return
    }
    if (url.pathname === '/' && url.searchParams.get('token') === 'launch-token') {
      internalCookie = 'dsh-session=internal'
      res.writeHead(302, { location: './', 'set-cookie': `${internalCookie}; Path=/; HttpOnly` })
      res.end()
      return
    }
    if (req.headers.cookie !== internalCookie) {
      res.writeHead(401)
      res.end('unauthorized')
      return
    }
    if (req.method === 'GET' && url.pathname === '/') {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      res.end('<html>DSH</html>')
      return
    }
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' })
    res.end(`PROXIED:${url.pathname}|host=${req.headers.host}|origin=${req.headers.origin || ''}`)
  }))
  const internalPort = await listen(internal)
  const internalOrigin = `http://127.0.0.1:${internalPort}`

  const bridgePort = await freePort()

  const ctx = {
    webServer: {
      getOrigin: () => internalOrigin,
      register: (route) => {
        routes.set(route.path, route)
        disposers.push(() => routes.delete(route.path))
        return () => routes.delete(route.path)
      },
    },
    connection: {
      authenticatedUrl: (base) => `${base}/?token=launch-token`,
      requestRejection: (request) => {
        const cookie = String(request?.headers?.cookie || '')
        // 管理接口只接受带内部登录 cookie 的“已登录页面”
        if (!cookie.includes('dsh-session=internal')) return 401
        return undefined
      },
    },
    on: (event, handler) => {
      if (!listeners.has(event)) listeners.set(event, [])
      listeners.get(event).push(handler)
      return () => {
        const list = listeners.get(event) || []
        const index = list.indexOf(handler)
        if (index >= 0) list.splice(index, 1)
      }
    },
    effect: (fn) => {
      const disposer = fn()
      cleanup.push(disposer)
      return disposer
    },
  }

  const emit = (event, ...args) => {
    for (const handler of listeners.get(event) || []) handler(...args)
  }

  apply(ctx, { enabled: true, bindHost: '127.0.0.1', port: bridgePort, notifyMinRunMs: 0 })

  t.after(async () => {
    for (const dispose of cleanup) {
      try {
        if (typeof dispose === 'function') dispose()
      } catch {
        /* 忽略 */
      }
    }
    for (const dispose of disposers) {
      try {
        dispose()
      } catch {
        /* 忽略 */
      }
    }
    await close(internal)
    if (previousHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = previousHome
    rmSync(dir, { recursive: true, force: true })
  })

  // 1. 健康检查（未配对也允许）
  await waitFor(async () => {
    try {
      const res = await call(bridgePort, 'GET', '/health')
      return res.status === 200
    } catch {
      return false
    }
  })
  const health = await call(bridgePort, 'GET', '/health')
  assert.equal(health.json.ok, true)

  // 2. 未带设备 cookie → 401
  const denied = await call(bridgePort, 'GET', '/')
  assert.equal(denied.status, 401)

  // 3. 没生成配对码时 /pair 拒绝
  const noCode = await call(bridgePort, 'POST', '/pair', { body: { code: '123456', name: 'Pixel' } })
  assert.equal(noCode.status, 403)

  // 4. 管理接口没内部 cookie → 401
  const mgmtDenied = await call(internalPort, 'POST', '/api/mobile/pair-code', { body: {} })
  assert.equal(mgmtDenied.status, 401)

  // 5. 已登录页面生成配对码
  const internalHeaders = { cookie: 'dsh-session=internal' }
  const minted = await call(internalPort, 'POST', '/api/mobile/pair-code', { body: {}, headers: internalHeaders })
  assert.equal(minted.status, 200)
  assert.match(minted.json.code, /^\d{6}$/u)

  // 5.1 配对二维码：只给本机已登录页面，内容是 deh://pair 深链
  const qrDenied = await call(internalPort, 'GET', '/api/mobile/pair-qr')
  assert.equal(qrDenied.status, 401)
  const qr = await call(internalPort, 'GET', '/api/mobile/pair-qr', { headers: internalHeaders })
  assert.equal(qr.status, 200)
  assert.match(String(qr.headers['content-type']), /image\/svg\+xml/u)
  assert.match(qr.text, /<svg/u)

  // 6. 错误配对码拒绝，正确配对码签发 token
  const wrong = await call(bridgePort, 'POST', '/pair', { body: { code: '000000', name: 'Pixel' } })
  assert.equal(wrong.status, 403)
  const paired = await call(bridgePort, 'POST', '/pair', {
    body: { code: minted.json.code, name: 'Pixel 9', platform: 'android' },
  })
  assert.equal(paired.status, 200)
  assert.ok(paired.json.token)
  assert.equal(paired.json.notify.channel, 'off', '默认关闭通知时只回 channel')
  const deviceCookie = `dshmb=${paired.json.token}`

  // 7. 设备 cookie 代理到内部 DSH，并带上内部 cookie / 改写的 Origin
  const proxied = await call(bridgePort, 'GET', '/chat', {
    headers: { cookie: deviceCookie, origin: 'http://100.64.0.1:8765' },
  })
  assert.equal(proxied.status, 200)
  assert.match(proxied.text, /PROXIED:\/chat/u)
  assert.match(proxied.text, new RegExp(`host=127\\.0\\.0\\.1:${internalPort}`))
  assert.match(proxied.text, new RegExp(`origin=${internalOrigin.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')}`))
  assert.equal(proxied.headers['set-cookie'], undefined)

  // 8. 状态接口能看到设备和配对信息
  const status = await call(internalPort, 'GET', '/api/mobile/status', { headers: internalHeaders })
  assert.equal(status.status, 200)
  assert.equal(status.json.devices.length, 1)
  assert.equal(status.json.devices[0].name, 'Pixel 9')
  assert.equal(JSON.stringify(status.json).includes(paired.json.token), false, '状态接口不能回明文 token')

  // 8.5 浏览器直接配对：根路径改跳配对页，表单提交后种 cookie
  const rootRedirect = await call(bridgePort, 'GET', '/', { headers: { accept: 'text/html' } })
  assert.equal(rootRedirect.status, 302)
  assert.equal(rootRedirect.headers.location, '/pair')

  const pairPage = await call(bridgePort, 'GET', '/pair')
  assert.equal(pairPage.status, 200)
  assert.match(pairPage.text, /form method="post" action="\/pair"/u)

  const mintedForBrowser = await call(internalPort, 'POST', '/api/mobile/pair-code', { body: {}, headers: internalHeaders })
  const wrongForm = await callForm(bridgePort, '/pair', { code: '000000', name: '手机浏览器' })
  assert.equal(wrongForm.status, 403)
  assert.match(wrongForm.text, /配对码/u)

  const browserPair = await callForm(bridgePort, '/pair', {
    code: mintedForBrowser.json.code,
    name: '手机浏览器',
  })
  assert.equal(browserPair.status, 302)
  assert.equal(browserPair.headers.location, '/')
  assert.match(String(browserPair.headers['set-cookie']), /dshmb=/u)
  const browserCookie = String(browserPair.headers['set-cookie']).split(';')[0]
  const browserPage = await call(bridgePort, 'GET', '/chat', { headers: { cookie: browserCookie } })
  assert.equal(browserPage.status, 200)
  assert.match(browserPage.text, /PROXIED:\/chat/u)

  // 9. 配置 ntfy → 任务完成事件 → 推送落在 stub
  const pushBodies = []
  let pushResolve
  const pushSeen = new Promise((resolve) => {
    pushResolve = resolve
  })
  const pushStub = track(http.createServer((req, res) => {
    const chunks = []
    req.on('data', (chunk) => chunks.push(chunk))
    req.on('end', () => {
      pushBodies.push(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      res.end('ok')
      pushResolve()
    })
  }))
  const pushPort = await listen(pushStub)
  t.after(() => close(pushStub))

  const configured = await call(internalPort, 'POST', '/api/mobile/config', {
    headers: internalHeaders,
    body: {
      config: {
        notifyChannel: 'ntfy',
        notifyEndpoint: `http://127.0.0.1:${pushPort}`,
        notifyTopic: 'dsh-test',
        notifyMinRunMs: 0,
      },
    },
  })
  assert.equal(configured.status, 200)
  assert.equal(configured.json.config.notifyTokenSet, false)

  // 9.1 已配对设备能读取通知订阅配置，未配对设备不行
  const notifyInfo = await call(bridgePort, 'GET', '/notify', { headers: { cookie: deviceCookie } })
  assert.equal(notifyInfo.status, 200)
  assert.equal(notifyInfo.json.notify.channel, 'ntfy')
  assert.equal(notifyInfo.json.notify.topic, 'dsh-test')
  assert.equal(notifyInfo.json.notify.endpoint, `http://127.0.0.1:${pushPort}`)
  assert.equal(JSON.stringify(notifyInfo.json).includes('notifyToken'), false)
  const notifyDenied = await call(bridgePort, 'GET', '/notify')
  assert.equal(notifyDenied.status, 401)

  emit('api-session/added', { sessionId: 'sess-1', cwd: 'C:\\work\\demo' })
  emit('api-session/status', 'sess-1', true)
  await delay(20)
  emit('api-session/status', 'sess-1', false)
  await Promise.race([pushSeen, delay(3000).then(() => { throw new Error('等待推送超时') })])
  assert.equal(pushBodies.length, 1)
  assert.equal(pushBodies[0].topic, 'dsh-test')
  assert.equal(pushBodies[0].title, '任务完成')
  assert.match(pushBodies[0].message, /demo/u)

  // 10. 撤销设备后立即 401
  const revoked = await call(internalPort, 'POST', '/api/mobile/revoke', {
    headers: internalHeaders,
    body: { all: true },
  })
  assert.equal(revoked.json.revoked, 2)
  const afterRevoke = await call(bridgePort, 'GET', '/chat', { headers: { cookie: deviceCookie } })
  assert.equal(afterRevoke.status, 401)
})
