import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DEFAULTS,
  buildNotifyRequest,
  clampConfig,
  detectTailscaleHost,
  deviceTokenFromHeaders,
  hostForUrl,
  isQuietHours,
  isTailscaleAddress,
  normalizeOrigin,
  notifyConfigForClient,
  parseCookieHeader,
  rewriteLocation,
  sanitizeRequestHeaders,
  sha256Hex,
} from '../lib/core.js'

test('clampConfig 丢弃未知键并钳制类型/范围', () => {
  const cfg = clampConfig({
    port: '99999',
    enabled: 'yes',
    notifyChannel: 'pigeon',
    notifyMinRunMs: -5,
    nope: 'x',
  })
  assert.equal(cfg.port, 65535)
  assert.equal(cfg.enabled, true) // 非布尔回退默认 true
  assert.equal(cfg.notifyChannel, 'off')
  assert.equal(cfg.notifyMinRunMs, 0)
  assert.equal('nope' in cfg, false)
})

test('clampConfig 保留合法覆盖层', () => {
  const cfg = clampConfig({ bindHost: ' 100.64.1.2 ', port: 8765, notifyChannel: 'ntfy', notifyQuietHours: '23:00-08:00' })
  assert.equal(cfg.bindHost, '100.64.1.2')
  assert.equal(cfg.notifyChannel, 'ntfy')
  assert.equal(cfg.notifyQuietHours, '23:00-08:00')
  assert.equal(cfg.enabled, DEFAULTS.enabled)
})

test('Tailscale 地址判定与自动探测', () => {
  assert.equal(isTailscaleAddress('100.64.0.1'), true)
  assert.equal(isTailscaleAddress('100.127.255.254'), true)
  assert.equal(isTailscaleAddress('100.128.0.1'), false)
  assert.equal(isTailscaleAddress('192.168.1.2'), false)
  const host = detectTailscaleHost({
    Ethernet: [{ address: '192.168.1.9', family: 'IPv4', internal: false }],
    Tailscale: [{ address: '100.101.2.3', family: 'IPv4', internal: false }],
  })
  assert.equal(host, '100.101.2.3')
  assert.equal(detectTailscaleHost({ Ethernet: [{ address: '192.168.1.9', family: 'IPv4', internal: false }] }), undefined)
})

test('hostForUrl / normalizeOrigin', () => {
  assert.equal(hostForUrl('100.64.0.1'), '100.64.0.1')
  assert.equal(hostForUrl('fd7a::1'), '[fd7a::1]')
  assert.equal(normalizeOrigin('http://pc.tail.ts.net:8765/'), 'http://pc.tail.ts.net:8765')
  assert.equal(normalizeOrigin('javascript:alert(1)'), '')
})

test('cookie 与设备凭证解析', () => {
  assert.deepEqual(parseCookieHeader('a=1; dshmb=abc; b=2'), { a: '1', dshmb: 'abc', b: '2' })
  assert.equal(deviceTokenFromHeaders({ cookie: 'x=1; dshmb=tok' }), 'tok')
  assert.equal(deviceTokenFromHeaders({ authorization: 'Bearer tok2' }), 'tok2')
  assert.equal(deviceTokenFromHeaders({}), '')
})

test('代理请求头改写：换成内部 Host/Cookie/Origin，去掉逐跳头', () => {
  const headers = sanitizeRequestHeaders(
    {
      host: '100.64.0.1:8765',
      origin: 'http://100.64.0.1:8765',
      referer: 'http://100.64.0.1:8765/chat',
      cookie: 'dshmb=device',
      connection: 'keep-alive',
      'x-forwarded-for': '1.2.3.4',
      accept: 'text/html',
    },
    { host: '127.0.0.1:59551', origin: 'http://127.0.0.1:59551' },
    'dsh-browser=internal',
  )
  assert.equal(headers.host, '127.0.0.1:59551')
  assert.equal(headers.cookie, 'dsh-browser=internal')
  assert.equal(headers.origin, 'http://127.0.0.1:59551')
  assert.equal(headers.referer, 'http://127.0.0.1:59551/')
  assert.equal(headers.accept, 'text/html')
  assert.equal('connection' in headers, false)
  assert.equal('x-forwarded-for' in headers, false)
})

test('内部跳转不把 loopback 地址漏出去', () => {
  assert.equal(rewriteLocation('http://127.0.0.1:59551/chat?a=1', 'http://127.0.0.1:59551'), '/chat?a=1')
  assert.equal(rewriteLocation('https://example.com/x', 'http://127.0.0.1:59551'), 'https://example.com/x')
  assert.equal(rewriteLocation('./', 'http://127.0.0.1:59551'), '/')
})

test('免打扰时段支持跨午夜', () => {
  assert.equal(isQuietHours('23:00-08:00', new Date(2026, 0, 1, 23, 30)), true)
  assert.equal(isQuietHours('23:00-08:00', new Date(2026, 0, 1, 7, 59)), true)
  assert.equal(isQuietHours('23:00-08:00', new Date(2026, 0, 1, 8, 0)), false)
  assert.equal(isQuietHours('09:00-18:00', new Date(2026, 0, 1, 12, 0)), true)
  assert.equal(isQuietHours('', new Date()), false)
  assert.equal(isQuietHours('乱写', new Date()), false)
})

test('ntfy 走 JSON 发布，中文不进 HTTP 头', () => {
  const req = buildNotifyRequest(
    clampConfig({ notifyChannel: 'ntfy', notifyTopic: 'dsh-abc', notifyEndpoint: 'https://ntfy.sh/' }),
    { title: '任务完成', body: '会话 abc', click: 'http://100.64.0.1:8765', quiet: true },
  )
  assert.ok(req)
  assert.equal(req.url, 'https://ntfy.sh')
  assert.equal(req.init.method, 'POST')
  const payload = JSON.parse(req.init.body)
  assert.equal(payload.topic, 'dsh-abc')
  assert.equal(payload.title, '任务完成')
  assert.equal(payload.priority, 2)
  assert.equal(payload.click, 'http://100.64.0.1:8765')
  for (const value of Object.values(req.init.headers)) {
    assert.doesNotMatch(String(value), /[\u4e00-\u9fff]/u)
  }
})

test('Bark 与 Telegram 请求形状，且日志用的 URL 已脱敏', () => {
  const bark = buildNotifyRequest(
    clampConfig({ notifyChannel: 'bark', notifyToken: 'secret-key' }),
    { title: 't', body: 'b' },
  )
  assert.ok(bark)
  assert.match(bark.url, /secret-key$/)
  assert.equal(bark.redactedUrl.includes('secret-key'), false)

  const tg = buildNotifyRequest(
    clampConfig({ notifyChannel: 'telegram', notifyToken: 'bot-token', notifyChatId: '42' }),
    { title: 't', body: 'b' },
  )
  assert.ok(tg)
  assert.match(tg.url, /botbot-token\/sendMessage$/)
  assert.equal(tg.redactedUrl.includes('bot-token'), false)
  assert.equal(JSON.parse(tg.init.body).chat_id, '42')
})

test('通知订阅配置只给 ntfy endpoint/topic，不泄露密钥', () => {
  assert.deepEqual(
    notifyConfigForClient(clampConfig({
      notifyChannel: 'ntfy',
      notifyEndpoint: 'https://ntfy.example/',
      notifyTopic: 'dsh-abc',
      notifyToken: 'secret',
    })),
    { channel: 'ntfy', endpoint: 'https://ntfy.example', topic: 'dsh-abc' },
  )
  assert.deepEqual(notifyConfigForClient(clampConfig({ notifyChannel: 'bark', notifyToken: 'secret' })), { channel: 'bark' })
  assert.deepEqual(notifyConfigForClient(clampConfig({ notifyChannel: 'telegram', notifyToken: 'secret' })), { channel: 'telegram' })
  assert.deepEqual(notifyConfigForClient(clampConfig({ notifyChannel: 'ntfy', notifyTopic: '' })), { channel: 'off' })
  assert.deepEqual(notifyConfigForClient(clampConfig({ notifyChannel: 'off' })), { channel: 'off' })
})

test('没配好时通知返回 null', () => {
  assert.equal(buildNotifyRequest(clampConfig({ notifyChannel: 'ntfy', notifyTopic: '' }), { title: 't', body: 'b' }), null)
  assert.equal(buildNotifyRequest(clampConfig({ notifyChannel: 'off' }), { title: 't', body: 'b' }), null)
})

test('sha256Hex 稳定', () => {
  assert.equal(sha256Hex('123456'), sha256Hex('123456'))
  assert.equal(sha256Hex('123456').length, 64)
})
