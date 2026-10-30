import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable, PassThrough } from 'node:stream'
import test from 'node:test'

import { createFileService, normalizeRelative } from '../lib/files.js'

test('相对路径拒绝越界/绝对路径', () => {
  assert.equal(normalizeRelative('a/b.txt'), 'a/b.txt')
  assert.equal(normalizeRelative('a\\b.txt'), 'a/b.txt')
  assert.equal(normalizeRelative(''), '')
  assert.throws(() => normalizeRelative('../x'))
  assert.throws(() => normalizeRelative('/etc/passwd'))
  assert.throws(() => normalizeRelative('C:\\Windows'))
})

test('文件服务：列表、上传、下载、建目录、路径越界', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'dsh-files-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  await writeFile(join(root, 'hello.txt'), 'hello world')
  const service = createFileService({ getRoot: () => root })

  const listed = await service.list('')
  assert.equal(listed.ok, true)
  assert.deepEqual(listed.entries.map((entry) => entry.name), ['hello.txt'])
  assert.equal(listed.entries[0].size, 11)

  await service.mkdir('', 'sub')
  assert.equal((await service.list('sub')).path, 'sub')

  const uploadReq = Readable.from([Buffer.from('uploaded')])
  uploadReq.headers = { 'content-length': '8' }
  const uploaded = await service.upload('sub', 'a.txt', uploadReq)
  assert.equal(uploaded.size, 8)
  assert.equal(await readFile(join(root, 'sub', 'a.txt'), 'utf8'), 'uploaded')

  const chunks = []
  const res = new PassThrough()
  res.writeHead = () => {}
  res.on('data', (chunk) => chunks.push(chunk))
  await service.download('sub/a.txt', res)
  assert.equal(Buffer.concat(chunks).toString('utf8'), 'uploaded')

  await assert.rejects(() => service.list('../'), /不能包含|越界/u)
  await assert.rejects(() => service.download('../hello.txt', new PassThrough()), /不能包含|越界/u)
})
