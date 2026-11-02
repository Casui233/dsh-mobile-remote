/**
 * 远程审批/回答的一次性动作表。
 *
 * 插件持有 approval/user-questions 的 waterfall 请求，给通知发一个随机 token；
 * 手机 POST 回来时按 token 找到 Promise 并 resolve。token 一次性、默认 2 分钟过期。
 */

import { randomUUID } from 'node:crypto'

const MAX_BODY_BYTES = 512 * 1024

export function createRemoteActions(options = {}) {
  const ttlMs = Number(options.ttlMs) > 0 ? Number(options.ttlMs) : 120000
  const pending = new Map()

  function create(kind, payload) {
    const token = randomUUID().replace(/-/gu, '').slice(0, 24)
    let resolve
    const promise = new Promise((done) => {
      resolve = done
    })
    const timer = setTimeout(() => {
      pending.delete(token)
    }, ttlMs)
    timer.unref?.()
    pending.set(token, {
      kind,
      payload,
      promise,
      resolve,
      createdAt: Date.now(),
      timer,
    })
    return { token, promise }
  }

  function cancel(token) {
    const entry = pending.get(token)
    if (!entry) return
    pending.delete(token)
    clearTimeout(entry.timer)
  }

  function take(token) {
    const entry = pending.get(token)
    if (!entry) return null
    pending.delete(token)
    clearTimeout(entry.timer)
    return entry
  }

  function peek(token) {
    return pending.get(token) || null
  }

  async function handle(req, res, url) {
    try {
      const token = String(url.searchParams.get('token') || '')
      if (!token) {
        send(res, 400, { ok: false, error: '缺少 token' })
        return
      }
      if (req.method === 'GET') {
        const entry = peek(token)
        if (!entry) {
          send(res, 410, { ok: false, error: '动作已过期或已处理' })
          return
        }
        send(res, 200, {
          ok: true,
          kind: entry.kind,
          title: entry.payload?.title || '',
          body: entry.payload?.body || '',
          questions: entry.kind === 'question' ? entry.payload?.questions || [] : [],
        })
        return
      }
      if (req.method === 'POST') {
        const body = await readJson(req)
        const entry = take(token)
        if (!entry) {
          send(res, 410, { ok: false, error: '动作已过期或已处理' })
          return
        }
        if (entry.kind === 'approval') {
          const outcome = String(body?.outcome || '')
          if (outcome !== 'allowed-once' && outcome !== 'rejected') {
            send(res, 400, { ok: false, error: 'outcome 必须是 allowed-once 或 rejected' })
            return
          }
          entry.resolve(outcome)
          send(res, 200, { ok: true, outcome })
          return
        }
        if (entry.kind === 'question') {
          const answers = body?.answers
          if (!Array.isArray(answers)) {
            send(res, 400, { ok: false, error: 'answers 必须是数组' })
            return
          }
          entry.resolve({ answers })
          send(res, 200, { ok: true })
          return
        }
        entry.resolve(null)
        send(res, 200, { ok: true })
        return
      }
      res.writeHead(405, { 'content-type': 'application/json; charset=utf-8', allow: 'GET, POST' })
      res.end(JSON.stringify({ ok: false, error: '请用 GET 或 POST' }))
    } catch (error) {
      const code = error?.code === 'TOO_LARGE' ? 413 : 400
      send(res, code, { ok: false, error: String(error?.message || error) })
    }
  }

  return { create, cancel, peek, handle }
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > MAX_BODY_BYTES) {
        const error = new Error('请求体太大')
        error.code = 'TOO_LARGE'
        reject(error)
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'))
      } catch {
        reject(new Error('JSON 格式不对'))
      }
    })
    req.on('error', reject)
  })
}

function send(res, code, body) {
  res.writeHead(code, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  })
  res.end(JSON.stringify(body))
}
