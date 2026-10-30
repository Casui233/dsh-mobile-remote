import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { createDeviceStore, createPairingCodes } from '../lib/store.js'

function tempDir() {
  return mkdtempSync(join(tmpdir(), 'dsh-mobile-bridge-'))
}

test('设备：签发、校验、撤销、重启后仍有效', () => {
  const dir = tempDir()
  try {
    const store = createDeviceStore(dir)
    const created = store.add({ name: 'Pixel 9', platform: 'android' })
    assert.match(created.token, /^[A-Za-z0-9_-]{40,}$/u)
    assert.equal(store.verify(created.token)?.id, created.device.id)
    assert.equal(store.verify('wrong'), null)

    const raw = JSON.parse(readFileSync(join(dir, 'devices.json'), 'utf8'))
    assert.equal(raw.devices[0].tokenHash.length, 64)
    assert.equal(JSON.stringify(raw).includes(created.token), false, '明文 token 不应落盘')

    // 重新加载仍可用
    const store2 = createDeviceStore(dir)
    assert.equal(store2.verify(created.token)?.name, 'Pixel 9')

    assert.equal(store2.revoke(created.device.id), true)
    assert.equal(store2.verify(created.token), null)
    assert.equal(store2.revoke(created.device.id), false)
    assert.equal(store2.list().length, 0)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('设备：revokeAll 与设备名清洗', () => {
  const dir = tempDir()
  try {
    const store = createDeviceStore(dir)
    const a = store.add({ name: '  iPhone\u0000 15  ', platform: 'ios' })
    const b = store.add({})
    assert.equal(store.list().length, 2)
    assert.equal(store.list()[0].name, 'iPhone 15')
    assert.equal(store.list()[1].name, '未命名设备')
    assert.equal(store.revokeAll(), 2)
    assert.equal(store.verify(a.token), null)
    assert.equal(store.verify(b.token), null)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('配对码：一次性、过期、失败上限', () => {
  let now = 1_000_000
  const pairing = createPairingCodes({ ttlMs: 60_000, maxAttempts: 3, now: () => now })

  const first = pairing.mint()
  assert.match(first.code, /^\d{6}$/u)
  assert.deepEqual(pairing.verify(first.code), { ok: true })
  assert.equal(pairing.verify(first.code).ok, false, '成功一次后作废')

  const second = pairing.mint()
  assert.equal(pairing.verify('000000').ok, false)
  assert.equal(pairing.verify('111111').ok, false)
  const third = pairing.verify('222222')
  assert.equal(third.ok, false)
  assert.match(third.reason, /次数过多/u)
  assert.equal(pairing.verify(second.code).ok, false, '达到上限后正确码也失效')

  const fresh = pairing.mint()
  now += 61_000
  assert.equal(pairing.peek(), null)
  const expired = pairing.verify(fresh.code)
  assert.equal(expired.ok, false)
  assert.match(expired.reason, /过期/u)
})

test('设备数上限', () => {
  const dir = tempDir()
  try {
    const store = createDeviceStore(dir)
    for (let index = 0; index < 32; index += 1) store.add({ name: `phone-${index}` })
    assert.throws(() => store.add({ name: 'overflow' }), /上限/u)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
