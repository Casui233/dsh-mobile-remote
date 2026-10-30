import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import test from 'node:test'

import { createProxy } from '../lib/proxy.js'

function listen(server) {
  const sockets = new Set()
  server.on('connection', (socket) => {
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
  })
  server.__trackedSockets = sockets
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)))
}

function close(server) {
  for (const socket of server.__trackedSockets || []) {
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
    // upgrade 后的 socket 不在 closeAllConnections 的集合里；别让清理挂死测试。
    const timer = setTimeout(finish, 1000)
    timer.unref?.()
  })
}

function request(port, options = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        hostname: '127.0.0.1',
        port,
        path: options.path || '/chat',
        method: options.method || 'GET',
        headers: options.headers || {},
      },
      (res) => {
        const chunks = []
        res.on('data', (chunk) => chunks.push(chunk))
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }))
      },
    )
    req.on('error', reject)
    req.end(options.body)
  })
}

test('HTTP 代理：改写 Host/Cookie/Origin，剥掉 set-cookie，改写内部 Location', async () => {
  /** @type {Record<string, any>} */
  let seen = {}
  const upstream = http.createServer((req, res) => {
    seen = { host: req.headers.host, cookie: req.headers.cookie, origin: req.headers.origin, forwarded: req.headers['x-forwarded-for'] }
    res.writeHead(302, {
      location: `http://127.0.0.1:${upstream.address().port}/next`,
      'set-cookie': 'internal=1; Path=/; HttpOnly',
    })
    res.end('ok')
  })
  const upstreamPort = await listen(upstream)
  const target = {
    protocol: 'http:',
    hostname: '127.0.0.1',
    port: upstreamPort,
    host: `127.0.0.1:${upstreamPort}`,
    origin: `http://127.0.0.1:${upstreamPort}`,
  }

  const proxy = createProxy({
    getTarget: () => target,
    getCookie: () => 'internal=1',
    refreshCookie: async () => 'internal=1',
    log: () => {},
  })
  const bridge = http.createServer((req, res) => proxy.handleHttp(req, res))
  const bridgePort = await listen(bridge)

  try {
    const res = await request(bridgePort, {
      headers: {
        host: '100.64.0.1:8765',
        origin: 'http://100.64.0.1:8765',
        cookie: 'dshmb=device-token',
        'x-forwarded-for': '1.2.3.4',
      },
    })
    assert.equal(res.status, 302)
    assert.equal(res.headers.location, '/next')
    assert.equal(res.headers['set-cookie'], undefined)
    assert.equal(seen.host, target.host)
    assert.equal(seen.cookie, 'internal=1')
    assert.equal(seen.origin, target.origin)
    assert.equal(seen.forwarded, undefined)
  } finally {
    await close(bridge)
    await close(upstream)
  }
})

test('HTTP 代理：内部 401 时刷新一次 cookie 并重放 GET', async () => {
  let calls = 0
  const cookies = []
  const upstream = http.createServer((req, res) => {
    calls += 1
    cookies.push(req.headers.cookie)
    if (calls === 1) {
      res.writeHead(401)
      res.end('no')
      return
    }
    res.writeHead(200)
    res.end('ok')
  })
  const upstreamPort = await listen(upstream)
  const target = {
    protocol: 'http:',
    hostname: '127.0.0.1',
    port: upstreamPort,
    host: `127.0.0.1:${upstreamPort}`,
    origin: `http://127.0.0.1:${upstreamPort}`,
  }
  let cookie = 'old=1'
  let refreshes = 0
  const proxy = createProxy({
    getTarget: () => target,
    getCookie: () => cookie,
    refreshCookie: async () => {
      refreshes += 1
      cookie = 'new=2'
      return cookie
    },
    log: () => {},
  })
  const bridge = http.createServer((req, res) => proxy.handleHttp(req, res))
  const bridgePort = await listen(bridge)

  try {
    const res = await request(bridgePort)
    assert.equal(res.status, 200)
    assert.equal(res.body, 'ok')
    assert.equal(refreshes, 1)
    assert.deepEqual(cookies, ['old=1', 'new=2'])
  } finally {
    await close(bridge)
    await close(upstream)
  }
})

test('HTTP 代理：没有内部登录态时回 503 JSON', async () => {
  const proxy = createProxy({
    getTarget: () => ({ protocol: 'http:', hostname: '127.0.0.1', port: 9, host: '127.0.0.1:9', origin: 'http://127.0.0.1:9' }),
    getCookie: () => null,
    refreshCookie: async () => '',
  })
  const bridge = http.createServer((req, res) => proxy.handleHttp(req, res))
  const bridgePort = await listen(bridge)
  try {
    const res = await request(bridgePort)
    assert.equal(res.status, 503)
    assert.equal(JSON.parse(res.body).ok, false)
  } finally {
    await close(bridge)
  }
})

test('WebSocket 升级：握手头改写后整段 TCP 透传', async () => {
  let handshake = ''
  const upstream = http.createServer((req, res) => res.end('http'))
  upstream.on('upgrade', (req, socket) => {
    handshake = `${req.headers.host}|${req.headers.cookie}|${req.headers.origin}`
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n')
    socket.write('hello-from-dsh')
  })
  const upstreamPort = await listen(upstream)
  const target = {
    protocol: 'http:',
    hostname: '127.0.0.1',
    port: upstreamPort,
    host: `127.0.0.1:${upstreamPort}`,
    origin: `http://127.0.0.1:${upstreamPort}`,
  }
  const proxy = createProxy({
    getTarget: () => target,
    getCookie: () => 'internal=1',
    refreshCookie: async () => 'internal=1',
  })
  const bridge = http.createServer((req, res) => res.end('no'))
  bridge.on('upgrade', (req, socket, head) => proxy.handleUpgrade(req, socket, head))
  const bridgePort = await listen(bridge)

  try {
    const received = await new Promise((resolve, reject) => {
      const socket = net.connect(bridgePort, '127.0.0.1', () => {
        socket.write(
          [
            'GET /api/remote.mux HTTP/1.1',
            'Host: 100.64.0.1:8765',
            'Upgrade: websocket',
            'Connection: Upgrade',
            'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==',
            'Sec-WebSocket-Version: 13',
            'Origin: http://100.64.0.1:8765',
            'Cookie: dshmb=device-token',
            '',
            '',
          ].join('\r\n'),
        )
      })
      let buffer = ''
      const timer = setTimeout(() => {
        socket.destroy()
        reject(new Error('WebSocket 透传超时'))
      }, 3000)
      socket.on('data', (chunk) => {
        buffer += chunk.toString('utf8')
        if (buffer.includes('hello-from-dsh')) {
          clearTimeout(timer)
          socket.destroy()
          resolve(buffer)
        }
      })
      socket.on('error', (error) => {
        clearTimeout(timer)
        reject(error)
      })
    })
    assert.match(received, /101 Switching Protocols/u)
    assert.match(received, /hello-from-dsh/u)
    assert.equal(handshake, '127.0.0.1:' + upstreamPort + '|internal=1|http://127.0.0.1:' + upstreamPort)
  } finally {
    await close(bridge)
    await close(upstream)
  }
})
