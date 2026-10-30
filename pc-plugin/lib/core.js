/**
 * 纯函数层：配置、地址判定、cookie、代理头改写、通知请求构造。
 *
 * 这里刻意不 import 任何 @deepseek-ai/*，也不 import node:http：
 * 插件要能在 Desktop 宿主里裸加载，单测也要能在普通 node 下直接跑。
 */

import { createHash, timingSafeEqual } from 'node:crypto'

/** 配置默认值。字段名会出现在设置页与 config.json 里，改名要同步 client.js。 */
export const DEFAULTS = Object.freeze({
  enabled: true,
  bindHost: '',
  port: 8765,
  publicOrigin: '',
  allowNonTailscaleBind: false,
  sharedDir: '',
  notifyChannel: 'off',
  notifyEndpoint: '',
  notifyTopic: '',
  notifyToken: '',
  notifyChatId: '',
  notifyDone: true,
  notifyError: true,
  notifyApproval: true,
  notifyQuestion: true,
  notifyQuietHours: '',
  notifyMinRunMs: 5000,
})

export const STRING_KEYS = Object.freeze([
  'bindHost',
  'publicOrigin',
  'sharedDir',
  'notifyChannel',
  'notifyEndpoint',
  'notifyTopic',
  'notifyToken',
  'notifyChatId',
  'notifyQuietHours',
])

export const BOOLEAN_KEYS = Object.freeze([
  'enabled',
  'allowNonTailscaleBind',
  'notifyDone',
  'notifyError',
  'notifyApproval',
  'notifyQuestion',
])

export const NUMBER_KEYS = Object.freeze(['port', 'notifyMinRunMs'])

export const NOTIFY_CHANNELS = Object.freeze(['off', 'ntfy', 'bark', 'telegram'])

const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-connection',
  'transfer-encoding',
  'upgrade',
  'te',
  'trailer',
])

/**
 * 把任意来源的配置夹成可用的形状：未知键丢弃、类型不对回退、范围钳制。
 * @param {Record<string, unknown>} raw 新的覆盖层
 * @param {Record<string, unknown>} base 回退基准（通常是当前配置）
 * @returns {typeof DEFAULTS}
 */
export function clampConfig(raw, base = DEFAULTS) {
  const src = raw && typeof raw === 'object' ? raw : {}
  const fallback = base && typeof base === 'object' ? base : DEFAULTS
  const out = { ...DEFAULTS }

  for (const key of STRING_KEYS) {
    const value = src[key] !== undefined ? src[key] : fallback[key]
    out[key] = typeof value === 'string' ? value.trim() : DEFAULTS[key]
  }
  for (const key of BOOLEAN_KEYS) {
    const value = src[key] !== undefined ? src[key] : fallback[key]
    out[key] = typeof value === 'boolean' ? value : Boolean(DEFAULTS[key])
  }
  for (const key of NUMBER_KEYS) {
    const value = src[key] !== undefined ? src[key] : fallback[key]
    const num = Number(value)
    out[key] = Number.isFinite(num) ? Math.trunc(num) : DEFAULTS[key]
  }

  out.port = Math.min(65535, Math.max(1, out.port || DEFAULTS.port))
  out.notifyMinRunMs = Math.min(3_600_000, Math.max(0, out.notifyMinRunMs))
  if (!NOTIFY_CHANNELS.includes(out.notifyChannel)) out.notifyChannel = 'off'
  if (!isQuietRange(out.notifyQuietHours)) out.notifyQuietHours = ''
  return out
}

/**
 * 只挑出配置键，供“部分更新”使用。
 * @param {Record<string, unknown>} patch
 */
export function pickConfigKeys(patch) {
  const out = {}
  if (!patch || typeof patch !== 'object') return out
  for (const key of [...STRING_KEYS, ...BOOLEAN_KEYS, ...NUMBER_KEYS]) {
    if (Object.prototype.hasOwnProperty.call(patch, key)) out[key] = patch[key]
  }
  return out
}

/** @param {string} host */
export function isLoopbackHost(host) {
  const value = String(host || '').trim().toLowerCase()
  if (value === 'localhost' || value === '::1' || value === '[::1]') return true
  return /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(value)
}

/** @param {string} host */
export function isWildcardHost(host) {
  const value = String(host || '').trim().toLowerCase()
  return value === '' || value === '0.0.0.0' || value === '::' || value === '[::]'
}

/**
 * Tailscale 的 IPv4 落在 100.64.0.0/10（CGNAT 段）。
 * @param {string} address
 */
export function isTailscaleAddress(address) {
  const parts = String(address || '').trim().split('.')
  if (parts.length !== 4) return false
  const nums = parts.map((part) => (/^\d{1,3}$/.test(part) ? Number(part) : NaN))
  if (nums.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return false
  return nums[0] === 100 && nums[1] >= 64 && nums[1] <= 127
}

/**
 * 从 networkInterfaces() 里挑一个 Tailscale IPv4；优先接口名带 tailscale 的。
 * @param {NodeJS.Dict<import('node:os').NetworkInterfaceInfo[]>} interfaces
 * @returns {string | undefined}
 */
export function detectTailscaleHost(interfaces) {
  const hits = []
  for (const [name, infos] of Object.entries(interfaces || {})) {
    for (const info of infos || []) {
      const family = typeof info.family === 'string' ? info.family : String(info.family)
      if (family !== 'IPv4' && family !== '4') continue
      if (info.internal) continue
      if (!isTailscaleAddress(info.address)) continue
      hits.push({ name, address: info.address })
    }
  }
  if (hits.length === 0) return undefined
  const named = hits.find((hit) => /tailscale/i.test(hit.name))
  return (named || hits[0]).address
}

/** URL 里的 IPv6 要加方括号。 */
export function hostForUrl(host) {
  const value = String(host || '')
  return value.includes(':') && !value.startsWith('[') ? `[${value}]` : value
}

/** @param {string} origin */
export function normalizeOrigin(origin) {
  const value = String(origin || '').trim()
  if (!value) return ''
  try {
    const url = new URL(value)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return ''
    return url.origin
  } catch {
    return ''
  }
}

/** @param {string} text */
export function sha256Hex(text) {
  return createHash('sha256').update(String(text)).digest('hex')
}

/**
 * 定长十六进制哈希的常量时间比较。
 * @param {string} a
 * @param {string} b
 */
export function safeEqualHex(a, b) {
  const left = String(a || '')
  const right = String(b || '')
  if (left.length !== right.length || left.length === 0) return false
  if (!/^[0-9a-f]+$/i.test(left) || !/^[0-9a-f]+$/i.test(right)) return false
  return timingSafeEqual(Buffer.from(left, 'hex'), Buffer.from(right, 'hex'))
}

/**
 * 解析 Cookie 头。
 * @param {string | undefined} header
 * @returns {Record<string, string>}
 */
export function parseCookieHeader(header) {
  const out = {}
  for (const chunk of String(header || '').split(';')) {
    const index = chunk.indexOf('=')
    if (index <= 0) continue
    const name = chunk.slice(0, index).trim()
    const value = chunk.slice(index + 1).trim()
    if (name) out[name] = value
  }
  return out
}

/**
 * 手机设备凭证：优先 cookie `dshmb`，其次 Bearer。
 * @param {Record<string, unknown>} headers
 */
export function deviceTokenFromHeaders(headers) {
  const cookie = parseCookieHeader(/** @type {string} */ (headers?.cookie))
  if (cookie.dshmb) return cookie.dshmb
  const auth = String(headers?.authorization || '')
  const match = /^Bearer\s+(.+)$/i.exec(auth)
  return match ? match[1].trim() : ''
}

/**
 * 把浏览器请求头改写成“内部 loopback 请求”的头。
 * @param {Record<string, unknown>} headers
 * @param {{ host: string, origin: string }} target
 * @param {string} internalCookie
 */
export function sanitizeRequestHeaders(headers, target, internalCookie) {
  const out = {}
  for (const [name, value] of Object.entries(headers || {})) {
    if (value === undefined) continue
    const key = name.toLowerCase()
    if (HOP_BY_HOP.has(key)) continue
    if (key === 'host' || key === 'cookie' || key === 'origin' || key === 'referer') continue
    if (key.startsWith('x-forwarded-')) continue
    out[key] = value
  }
  out.host = target.host
  out.cookie = internalCookie
  if (headers?.origin !== undefined) out.origin = target.origin
  if (headers?.referer !== undefined) out.referer = `${target.origin}/`
  return out
}

/**
 * 内部服务的绝对跳转不能把 loopback 地址漏给手机。
 * @param {string} location
 * @param {string} internalOrigin
 */
export function rewriteLocation(location, internalOrigin) {
  const value = String(location || '')
  if (!value) return value
  try {
    const url = new URL(value, internalOrigin)
    if (url.origin === internalOrigin) return `${url.pathname}${url.search}${url.hash}`
  } catch {
    /* 保持原样 */
  }
  return value
}

/**
 * “23:00-08:00”这种跨午夜区间。
 * @param {string} range
 * @param {Date} now
 */
export function isQuietHours(range, now = new Date()) {
  const match = /^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$/.exec(String(range || '').trim())
  if (!match) return false
  const start = Number(match[1]) * 60 + Number(match[2])
  const end = Number(match[3]) * 60 + Number(match[4])
  if (start > 1439 || end > 1439) return false
  const minute = now.getHours() * 60 + now.getMinutes()
  return start <= end ? minute >= start && minute < end : minute >= start || minute < end
}

/** @param {string} range */
export function isQuietRange(range) {
  const value = String(range || '').trim()
  if (!value) return true
  const match = /^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$/.exec(value)
  if (!match) return false
  const start = Number(match[1]) * 60 + Number(match[2])
  const end = Number(match[3]) * 60 + Number(match[4])
  return start <= 1439 && end <= 1439
}

/**
 * 构造一次推送请求。返回 null 表示没配好/关闭。
 * 所有渠道都用 JSON body，避免把中文塞进 HTTP 头（Node 会拒绝非 Latin-1 头）。
 *
 * @param {typeof DEFAULTS} cfg
 * @param {{ title: string, body: string, click?: string, quiet?: boolean }} note
 * @returns {{ channel: string, url: string, init: RequestInit, redactedUrl: string } | null}
 */
export function buildNotifyRequest(cfg, note) {
  const channel = cfg.notifyChannel
  if (channel === 'off') return null
  const title = String(note.title || 'DSH')
  const body = String(note.body || '')
  const click = note.click ? String(note.click) : undefined
  const quiet = Boolean(note.quiet)

  if (channel === 'ntfy') {
    const topic = cfg.notifyTopic.trim()
    if (!topic) return null
    const base = (cfg.notifyEndpoint.trim() || 'https://ntfy.sh').replace(/\/+$/u, '')
    const payload = {
      topic,
      title,
      message: body,
      priority: quiet ? 2 : 3,
      tags: ['robot'],
    }
    if (click) payload.click = click
    return {
      channel,
      url: base,
      redactedUrl: base,
      init: {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      },
    }
  }

  if (channel === 'bark') {
    const key = cfg.notifyToken.trim()
    if (!key) return null
    const base = (cfg.notifyEndpoint.trim() || 'https://api.day.app').replace(/\/+$/u, '')
    const payload = { title, body, group: 'dsh' }
    if (click) payload.url = click
    return {
      channel,
      url: `${base}/${encodeURIComponent(key)}`,
      redactedUrl: `${base}/***`,
      init: {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      },
    }
  }

  if (channel === 'telegram') {
    const token = cfg.notifyToken.trim()
    const chatId = cfg.notifyChatId.trim()
    if (!token || !chatId) return null
    const base = (cfg.notifyEndpoint.trim() || 'https://api.telegram.org').replace(/\/+$/u, '')
    const payload = {
      chat_id: chatId,
      text: body ? `${title}\n${body}` : title,
      disable_web_page_preview: true,
    }
    return {
      channel,
      url: `${base}/bot${token}/sendMessage`,
      redactedUrl: `${base}/bot***/sendMessage`,
      init: {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(payload),
      },
    }
  }

  return null
}

/**
 * 给已配对手机客户端的通知订阅配置。只暴露 ntfy 的 endpoint/topic；
 * Bark/Telegram 永远不透出 token，非 ntfy 只回 channel 供 App 显示。
 * @param {typeof DEFAULTS} cfg
 * @returns {{ channel: string, endpoint?: string, topic?: string }}
 */
export function notifyConfigForClient(cfg) {
  const channel = String(cfg?.notifyChannel || 'off')
  if (channel !== 'ntfy') return { channel }
  const topic = String(cfg?.notifyTopic || '').trim()
  if (!topic) return { channel: 'off' }
  const endpoint = (String(cfg?.notifyEndpoint || '').trim() || 'https://ntfy.sh').replace(/\/+$/u, '')
  return { channel, endpoint, topic }
}

/** 设备名清洗：去掉控制字符，限长。 */
export function sanitizeDeviceName(name) {
  const value = String(name || '')
    .replace(/[\u0000-\u001f\u007f]/gu, '')
    .trim()
  return value.slice(0, 40) || '未命名设备'
}

/** 极简 JSON 校验：给路径参数用。 */
export function shortId(value) {
  return String(value || '').slice(0, 12)
}
