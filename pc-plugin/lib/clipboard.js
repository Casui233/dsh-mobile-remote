/**
 * 文本剪贴板同步：PC 端读写系统剪贴板。
 *
 * 只通过已配对设备访问；不落盘、不写日志。Windows 用 PowerShell 的
 * Get-Clipboard/Set-Clipboard，macOS 用 pbpaste/pbcopy，Linux 用
 * wl-paste/wl-copy 或 xclip/xsel。
 */

import { execFile } from 'node:child_process'
import { createHash } from 'node:crypto'

const MAX_TEXT_BYTES = 512 * 1024
const MAX_BODY_BYTES = 768 * 1024
const READ_CACHE_MS = 600

export function createClipboard(options = {}) {
  let cachedText = null
  let cachedHash = ''
  let cachedAt = 0
  let updatedAt = 0

  const readOverride = typeof options.read === 'function' ? options.read : null
  const writeOverride = typeof options.write === 'function' ? options.write : null

  function hash(text) {
    return createHash('sha256').update(text, 'utf8').digest('hex')
  }

  async function readText() {
    if (readOverride) return String(await readOverride())
    if (cachedText !== null && Date.now() - cachedAt < READ_CACHE_MS) return cachedText
    const text = await readSystem()
    cachedText = text
    cachedHash = hash(text)
    cachedAt = Date.now()
    updatedAt = Date.now()
    return text
  }

  async function writeText(text) {
    if (writeOverride) {
      await writeOverride(text)
    } else {
      await writeSystem(text)
    }
    cachedText = text
    cachedHash = hash(text)
    cachedAt = Date.now()
    updatedAt = Date.now()
  }

  async function handle(req, res, url) {
    try {
      if (req.method === 'GET') {
        const text = await readText()
        const changed = !url.searchParams.get('since') || url.searchParams.get('since') !== cachedHash
        send(res, 200, {
          ok: true,
          changed,
          hash: cachedHash,
          updatedAt,
          length: Buffer.byteLength(text, 'utf8'),
          ...(changed ? { text } : {}),
        })
        return
      }
      if (req.method === 'POST') {
        const body = await readJson(req)
        if (typeof body?.text !== 'string') {
          send(res, 400, { ok: false, error: '需要 JSON: {"text":"..."}' })
          return
        }
        if (Buffer.byteLength(body.text, 'utf8') > MAX_TEXT_BYTES) {
          send(res, 413, { ok: false, error: '剪贴板文本超过 512 KiB' })
          return
        }
        await writeText(body.text)
        send(res, 200, { ok: true, hash: cachedHash, updatedAt })
        return
      }
      res.writeHead(405, { 'content-type': 'application/json; charset=utf-8', allow: 'GET, POST' })
      res.end(JSON.stringify({ ok: false, error: '请用 GET 或 POST' }))
    } catch (error) {
      const message = String(error?.message || error)
      const code = error?.code === 'NO_CLIPBOARD' ? 501 : 500
      send(res, code, { ok: false, error: message })
    }
  }

  return { handle, readText, writeText }
}

async function readSystem() {
  if (process.platform === 'win32') {
    return await run('powershell.exe', [
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      '[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; Get-Clipboard -Raw',
    ])
  }
  if (process.platform === 'darwin') {
    return await run('pbpaste', [])
  }
  return await readLinux()
}

async function writeSystem(text) {
  if (process.platform === 'win32') {
    await run(
      'powershell.exe',
      [
        '-NoProfile',
        '-NonInteractive',
        '-Command',
        '[Console]::InputEncoding=[System.Text.Encoding]::UTF8; $t=[Console]::In.ReadToEnd(); Set-Clipboard -Value $t',
      ],
      text,
    )
    return
  }
  if (process.platform === 'darwin') {
    await run('pbcopy', [], text)
    return
  }
  await writeLinux(text)
}

async function readLinux() {
  const attempts = [
    ['wl-paste', ['-n']],
    ['xclip', ['-selection', 'clipboard', '-o']],
    ['xsel', ['--clipboard', '--output']],
  ]
  let lastError = null
  for (const [command, args] of attempts) {
    try {
      return await run(command, args)
    } catch (error) {
      lastError = error
      if (error?.code !== 'ENOENT') throw error
    }
  }
  const error = new Error('没有可用的剪贴板工具：请安装 wl-clipboard 或 xclip')
  error.code = 'NO_CLIPBOARD'
  throw error
}

async function writeLinux(text) {
  const attempts = [
    ['wl-copy', []],
    ['xclip', ['-selection', 'clipboard']],
    ['xsel', ['--clipboard', '--input']],
  ]
  let lastError = null
  for (const [command, args] of attempts) {
    try {
      await run(command, args, text)
      return
    } catch (error) {
      lastError = error
      if (error?.code !== 'ENOENT') throw error
    }
  }
  const error = new Error('没有可用的剪贴板工具：请安装 wl-clipboard 或 xclip')
  error.code = 'NO_CLIPBOARD'
  throw error
}

function run(command, args, input) {
  return new Promise((resolve, reject) => {
    const child = execFile(
      command,
      args,
      {
        encoding: 'utf8',
        timeout: 6000,
        maxBuffer: MAX_TEXT_BYTES * 2,
        windowsHide: true,
      },
      (error, stdout) => {
        if (error) {
          reject(error)
          return
        }
        resolve(String(stdout ?? ''))
      },
    )
    if (input !== undefined) child.stdin.end(input, 'utf8')
  })
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
      } catch (error) {
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
