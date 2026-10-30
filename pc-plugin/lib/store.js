/**
 * 设备表与配对码。存 $DSH_HOME/mobile-bridge/devices.json：
 * 只存 token 的 SHA-256，配对码本身不落盘（重启即失效）。
 */

import { randomBytes, randomInt } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { safeEqualHex, sanitizeDeviceName, sha256Hex } from './core.js'

const FILE_VERSION = 1
const MAX_DEVICES = 32
const REVOKED_RETENTION_MS = 90 * 24 * 3600 * 1000

/**
 * @param {string} dir
 * @param {{ log?: (message: string) => void }} [options]
 */
export function createDeviceStore(dir, options = {}) {
  const log = options.log || (() => {})
  const file = join(dir, 'devices.json')

  /** @type {{ version: number, devices: Array<Record<string, any>> }} */
  let state = { version: FILE_VERSION, devices: [] }

  if (existsSync(file)) {
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf8'))
      if (parsed && Array.isArray(parsed.devices)) {
        state = {
          version: FILE_VERSION,
          devices: parsed.devices.filter((row) => row && typeof row.id === 'string' && typeof row.tokenHash === 'string'),
        }
      }
    } catch (error) {
      log(`设备表读取失败（按空表处理）：${error?.message ?? error}`)
    }
  }

  function persist() {
    mkdirSync(dir, { recursive: true })
    const temp = `${file}.tmp`
    writeFileSync(temp, `${JSON.stringify(state, null, 2)}\n`, 'utf8')
    renameSync(temp, file)
  }

  function prune() {
    const now = Date.now()
    state.devices = state.devices.filter((row) => {
      if (!row.revokedAt) return true
      return now - Number(row.revokedAt) < REVOKED_RETENTION_MS
    })
  }

  function publicView(row) {
    return {
      id: row.id,
      name: row.name,
      platform: row.platform,
      createdAt: row.createdAt,
      lastSeenAt: row.lastSeenAt,
      revokedAt: row.revokedAt || null,
    }
  }

  return {
    get file() {
      return file
    },

    list() {
      return state.devices.filter((row) => !row.revokedAt).map(publicView)
    },

    /**
     * 新建一台设备，返回明文 token（只出现这一次）。
     * @param {{ name?: string, platform?: string }} input
     */
    add(input = {}) {
      prune()
      if (state.devices.filter((row) => !row.revokedAt).length >= MAX_DEVICES) {
        throw new Error(`设备数已达上限（${MAX_DEVICES}），请先撤销旧设备`)
      }
      const token = randomBytes(32).toString('base64url')
      const row = {
        id: `dev_${randomBytes(6).toString('hex')}`,
        name: sanitizeDeviceName(input.name),
        platform: String(input.platform || 'android').slice(0, 20),
        tokenHash: sha256Hex(token),
        createdAt: Date.now(),
        lastSeenAt: Date.now(),
        revokedAt: null,
      }
      state.devices.push(row)
      persist()
      return { device: publicView(row), token }
    },

    /**
     * @param {string} token
     * @returns {Record<string, any> | null}
     */
    verify(token) {
      const value = String(token || '')
      if (!value) return null
      const hash = sha256Hex(value)
      for (const row of state.devices) {
        if (row.revokedAt) continue
        if (safeEqualHex(row.tokenHash, hash)) return row
      }
      return null
    },

    /** 记录活跃时间；最多每分钟落盘一次，避免每个请求都写文件。 */
    touch(row) {
      if (!row) return
      const now = Date.now()
      row.lastSeenAt = now
      if (!row.persistedSeenAt || now - row.persistedSeenAt > 60_000) {
        row.persistedSeenAt = now
        try {
          persist()
        } catch (error) {
          log(`设备表写入失败：${error?.message ?? error}`)
        }
      }
    },

    /** @param {string} id */
    revoke(id) {
      const row = state.devices.find((item) => item.id === id && !item.revokedAt)
      if (!row) return false
      row.revokedAt = Date.now()
      persist()
      return true
    },

    revokeAll() {
      let count = 0
      for (const row of state.devices) {
        if (row.revokedAt) continue
        row.revokedAt = Date.now()
        count += 1
      }
      if (count > 0) persist()
      return count
    },
  }
}

/**
 * 6 位配对码：默认 5 分钟、5 次失败作废、一次成功即消费。
 * @param {{ ttlMs?: number, maxAttempts?: number, now?: () => number }} [options]
 */
export function createPairingCodes(options = {}) {
  const ttlMs = options.ttlMs ?? 5 * 60 * 1000
  const maxAttempts = options.maxAttempts ?? 5
  const now = options.now || (() => Date.now())

  /** @type {{ code: string, hash: string, expiresAt: number, attempts: number } | null} */
  let current = null

  function expired() {
    return !current || now() > current.expiresAt
  }

  return {
    mint() {
      const code = String(randomInt(0, 1_000_000)).padStart(6, '0')
      current = { code, hash: sha256Hex(code), expiresAt: now() + ttlMs, attempts: 0 }
      return { code, expiresAt: current.expiresAt }
    },

    /** 只给设置页展示用；过期返回 null。 */
    peek() {
      if (expired()) return null
      return { code: current.code, expiresAt: current.expiresAt }
    },

    /**
     * @param {string} code
     * @returns {{ ok: true } | { ok: false, reason: string }}
     */
    verify(code) {
      if (expired()) {
        current = null
        return { ok: false, reason: '配对码已过期，请在电脑上重新生成' }
      }
      const value = String(code || '').replace(/\D/gu, '')
      if (!safeEqualHex(current.hash, sha256Hex(value))) {
        current.attempts += 1
        if (current.attempts >= maxAttempts) {
          current = null
          return { ok: false, reason: '配对码尝试次数过多，已作废，请重新生成' }
        }
        return { ok: false, reason: '配对码不正确' }
      }
      current = null
      return { ok: true }
    },

    clear() {
      current = null
    },
  }
}
