import { createReadStream, createWriteStream, existsSync, lstatSync, mkdirSync, realpathSync, statSync } from 'node:fs'
import { promises as fsp } from 'node:fs'
import { homedir } from 'node:os'
import { basename, dirname, extname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import { randomBytes } from 'node:crypto'
import { Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'

const MAX_UPLOAD_BYTES = 4 * 1024 * 1024 * 1024

const MIME_BY_EXT = Object.freeze({
  '.apk': 'application/vnd.android.package-archive',
  '.pdf': 'application/pdf',
  '.doc': 'application/msword',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xls': 'application/vnd.ms-excel',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.ppt': 'application/vnd.ms-powerpoint',
  '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.zip': 'application/zip',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.mp4': 'video/mp4',
  '.mp3': 'audio/mpeg',
})

function contentTypeFor(name) {
  return MIME_BY_EXT[extname(String(name || '')).toLowerCase()] || 'application/octet-stream'
}

class FileError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

function sendJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload))
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': body.length,
    'cache-control': 'no-store',
  })
  res.end(body)
}

function defaultRoot() {
  return join(homedir(), 'DSH-Shared')
}

function inside(root, target) {
  const rel = relative(root, target)
  return rel === '' || (!rel.startsWith('..') && !isAbsolute(rel))
}

/** 相对路径规整：拒绝绝对路径、盘符、.. 和 NUL。 */
export function normalizeRelative(input) {
  let value = String(input ?? '').trim()
  if (!value) return ''
  if (value.includes('\0')) throw new FileError(400, '路径包含非法字符')
  value = value.replace(/\\/gu, '/')
  if (value.startsWith('/') || /^[A-Za-z]:/u.test(value)) {
    throw new FileError(400, '只接受共享目录内的相对路径')
  }
  const parts = []
  for (const part of value.split('/')) {
    if (!part || part === '.') continue
    if (part === '..') throw new FileError(400, '路径不能包含 ..')
    parts.push(part)
  }
  return parts.join('/')
}

function ensureRoot(getRoot) {
  const configured = String(getRoot?.() || '').trim()
  const root = resolve(configured || defaultRoot())
  mkdirSync(root, { recursive: true })
  const real = realpathSync(root)
  if (!statSync(real).isDirectory()) throw new FileError(500, '共享路径不是目录')
  return real
}

function resolveTarget(getRoot, rel, options = {}) {
  const root = ensureRoot(getRoot)
  const clean = normalizeRelative(rel)
  const target = resolve(root, clean)
  if (!inside(root, target)) throw new FileError(400, '路径越界')
  if (options.mustExist) {
    let stat
    try {
      stat = lstatSync(target)
    } catch {
      throw new FileError(404, '文件不存在')
    }
    if (stat.isSymbolicLink()) throw new FileError(403, '不处理符号链接')
    const real = realpathSync(target)
    if (!inside(root, real)) throw new FileError(403, '路径越界')
    return { root, rel: clean, target: real, stat: statSync(real) }
  }
  const parent = dirname(target)
  let realParent
  try {
    realParent = realpathSync(parent)
  } catch {
    throw new FileError(404, '父目录不存在')
  }
  if (!inside(root, realParent)) throw new FileError(403, '路径越界')
  return { root, rel: clean, target: join(realParent, basename(target)), stat: null }
}

function safeName(name) {
  const value = basename(String(name || '').replace(/\\/gu, '/')).trim()
  if (!value || value === '.' || value === '..' || value.includes('\0')) {
    throw new FileError(400, '文件名不合法')
  }
  return value
}

function formatSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KiB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MiB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GiB'
}

function contentDisposition(name) {
  const ascii = name.replace(/[^\x20-\x7e]/gu, '_').replace(/["\\]/gu, '_')
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(name)}`
}

export function createFileService(options = {}) {
  const getRoot = options.getRoot || (() => '')
  const maxUploadBytes = Number(options.maxUploadBytes) > 0 ? Number(options.maxUploadBytes) : MAX_UPLOAD_BYTES
  const log = typeof options.log === 'function' ? options.log : () => {}

  async function list(rel) {
    const info = resolveTarget(getRoot, rel, { mustExist: true })
    if (!info.stat.isDirectory()) throw new FileError(400, '不是目录')
    const entries = []
    for (const entry of await fsp.readdir(info.target, { withFileTypes: true })) {
      const full = join(info.target, entry.name)
      let stat
      try {
        stat = lstatSync(full)
      } catch {
        continue
      }
      if (stat.isSymbolicLink()) continue
      const type = stat.isDirectory() ? 'dir' : stat.isFile() ? 'file' : 'other'
      if (type === 'other') continue
      entries.push({
        name: entry.name,
        path: info.rel ? `${info.rel}/${entry.name}` : entry.name,
        type,
        size: type === 'file' ? stat.size : 0,
        mtime: stat.mtimeMs,
      })
    }
    entries.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'dir' ? -1 : 1
      return a.name.localeCompare(b.name, 'zh-CN')
    })
    return {
      ok: true,
      root: info.root,
      path: info.rel,
      parent: info.rel ? info.rel.split('/').slice(0, -1).join('/') : null,
      entries,
    }
  }

  async function download(rel, res) {
    const info = resolveTarget(getRoot, rel, { mustExist: true })
    if (!info.stat.isFile()) throw new FileError(400, '只能下载文件')
    res.writeHead(200, {
      'content-type': contentTypeFor(info.target),
      'content-length': info.stat.size,
      'content-disposition': contentDisposition(basename(info.target)),
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    })
    await pipeline(createReadStream(info.target), res).catch((error) => {
      if (error?.code !== 'ERR_STREAM_PREMATURE_CLOSE') log(`下载中断：${error?.message ?? error}`)
    })
  }

  async function upload(dirRel, name, req) {
    const info = resolveTarget(getRoot, dirRel, { mustExist: true })
    if (!info.stat.isDirectory()) throw new FileError(400, '上传目标不是目录')
    const fileName = safeName(name)
    const target = join(info.target, fileName)
    if (!inside(info.root, target)) throw new FileError(400, '路径越界')
    if (existsSync(target)) {
      const existing = lstatSync(target)
      if (existing.isSymbolicLink()) throw new FileError(403, '不覆盖符号链接')
      if (existing.isDirectory()) throw new FileError(409, '同名目录已存在')
    }
    const declared = Number(req.headers['content-length'] || 0)
    if (declared > maxUploadBytes) throw new FileError(413, '文件超过大小限制')
    const temp = join(info.target, `.dsh-upload-${randomBytes(8).toString('hex')}.part`)
    let size = 0
    try {
      const output = createWriteStream(temp, { flags: 'wx' })
      const limiter = new Transform({
        transform(chunk, encoding, callback) {
          size += chunk.length
          if (size > maxUploadBytes) {
            callback(new FileError(413, '文件超过大小限制'))
            return
          }
          callback(null, chunk)
        },
      })
      await pipeline(req, limiter, output)
      await fsp.rm(target, { force: true })
      await fsp.rename(temp, target)
      return { ok: true, name: fileName, path: info.rel ? `${info.rel}/${fileName}` : fileName, size }
    } catch (error) {
      await fsp.rm(temp, { force: true }).catch(() => {})
      if (error instanceof FileError) throw error
      throw new FileError(400, `上传失败：${error?.message ?? error}`)
    }
  }

  async function mkdir(dirRel, name) {
    const info = resolveTarget(getRoot, dirRel, { mustExist: true })
    if (!info.stat.isDirectory()) throw new FileError(400, '目标不是目录')
    const folder = safeName(name)
    const target = join(info.target, folder)
    if (!inside(info.root, target)) throw new FileError(400, '路径越界')
    try {
      await fsp.mkdir(target)
    } catch (error) {
      if (error?.code === 'EEXIST') throw new FileError(409, '目录已存在')
      throw new FileError(400, `创建目录失败：${error?.message ?? error}`)
    }
    return { ok: true, name: folder, path: info.rel ? `${info.rel}/${folder}` : folder }
  }

  async function handleApi(req, res, url) {
    const action = url.pathname.slice('/files/api/'.length)
    const rel = url.searchParams.get('path') || ''
    if (req.method === 'GET' && action === 'list') {
      sendJson(res, 200, await list(rel))
      return
    }
    if (req.method === 'GET' && action === 'download') {
      await download(rel, res)
      return
    }
    if (req.method === 'POST') {
      if (String(req.headers['x-dsh-files'] || '') !== '1') {
        throw new FileError(403, '缺少文件操作标记')
      }
      if (action === 'upload') {
        sendJson(res, 200, await upload(url.searchParams.get('dir') || '', url.searchParams.get('name') || '', req))
        return
      }
      if (action === 'mkdir') {
        sendJson(res, 200, await mkdir(url.searchParams.get('dir') || '', url.searchParams.get('name') || ''))
        return
      }
    }
    throw new FileError(404, '未知文件接口')
  }

  return {
    page,
    list,
    download,
    upload,
    mkdir,
    async handle(req, res, url) {
      try {
        await handleApi(req, res, url)
      } catch (error) {
        if (res.headersSent) {
          try {
            res.destroy()
          } catch {
            /* ignore */
          }
          return
        }
        if (error instanceof FileError) {
          sendJson(res, error.status, { ok: false, error: error.message })
        } else {
          log(`文件接口异常：${error?.message ?? error}`)
          sendJson(res, 500, { ok: false, error: '文件操作失败' })
        }
      }
    },
  }
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

/** 手机友好的文件页；不依赖外部资源。 */
export function page() {
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>文件传输 · DSH</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box}
body{margin:0;background:#0f1115;color:#e8eaed;font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
header{position:sticky;top:0;z-index:5;background:#12151c;border-bottom:1px solid #252b37;padding:12px 14px}
.title{display:flex;align-items:center;justify-content:space-between;gap:10px}
h1{font-size:17px;margin:0}
a{color:#8ab4f8;text-decoration:none}
.toolbar{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
button{border:0;border-radius:8px;background:#2a3242;color:#e8eaed;padding:8px 11px;font:inherit}
button.primary{background:#3b82f6;color:#fff;font-weight:600}
button:active{opacity:.8}
.crumbs{font-size:12px;color:#9aa3b2;margin-top:8px;word-break:break-all}
main{padding:10px 14px 32px}
.row{display:flex;align-items:center;gap:10px;padding:10px 2px;border-bottom:1px solid #202531}
.icon{width:24px;text-align:center;color:#8ab4f8;font-size:17px}
.grow{flex:1;min-width:0}
.name{word-break:break-all}
.meta{color:#7f8797;font-size:11px;margin-top:2px}
.empty{color:#7f8797;text-align:center;padding:40px 0}
.status{font-size:12px;color:#9aa3b2;margin-left:2px}
.status.bad{color:#ff9b90}
input[type=file]{display:none}
footer{color:#6b7280;font-size:11px;padding:0 14px 24px;word-break:break-all}
</style></head><body>
<header>
  <div class="title"><h1>文件传输</h1><a href="/">返回 DSH</a></div>
  <div class="toolbar">
    <button class="primary" id="pick">上传到当前目录</button>
    <button id="mkdir">新建文件夹</button>
    <button id="refresh">刷新</button>
  </div>
  <div class="crumbs" id="crumbs">/</div>
  <div class="status" id="status"></div>
  <input type="file" id="file" multiple>
</header>
<main id="list"><div class="empty">载入中…</div></main>
<footer id="root"></footer>
<script>
(function () {
  var cwd = new URLSearchParams(location.search).get('path') || ''
  var listEl = document.getElementById('list')
  var crumbs = document.getElementById('crumbs')
  var statusEl = document.getElementById('status')
  var rootEl = document.getElementById('root')
  var fileInput = document.getElementById('file')

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]
    })
  }
  function size(n) {
    if (n < 1024) return n + ' B'
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KiB'
    if (n < 1073741824) return (n / 1048576).toFixed(1) + ' MiB'
    return (n / 1073741824).toFixed(2) + ' GiB'
  }
  function setStatus(text, bad) {
    statusEl.textContent = text || ''
    statusEl.className = 'status' + (bad ? ' bad' : '')
  }
  function openDir(path) {
    location.search = '?path=' + encodeURIComponent(path)
  }
  function renderList(data) {
    cwd = data.path || ''
    rootEl.textContent = '共享目录：' + data.root
    var parts = cwd ? cwd.split('/') : []
    var html = '<a href="#" data-dir="">根目录</a>'
    var acc = ''
    parts.forEach(function (p) {
      acc = acc ? acc + '/' + p : p
      html += ' / <a href="#" data-dir="' + esc(acc) + '">' + esc(p) + '</a>'
    })
    crumbs.innerHTML = html
    crumbs.querySelectorAll('a[data-dir]').forEach(function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault()
        openDir(a.getAttribute('data-dir'))
      })
    })
    if (!data.entries.length) {
      listEl.innerHTML = '<div class="empty">这个目录是空的</div>'
      return
    }
    listEl.innerHTML = data.entries.map(function (e) {
      if (e.type === 'dir') {
        return '<div class="row"><div class="icon">▸</div><div class="grow">' +
          '<div class="name"><a href="#" data-dir="' + esc(e.path) + '">' + esc(e.name) + '</a></div>' +
          '<div class="meta">目录</div></div></div>'
      }
      return '<div class="row"><div class="icon">▣</div><div class="grow">' +
        '<div class="name"><a href="/files/api/download?path=' + encodeURIComponent(e.path) + '">' + esc(e.name) + '</a></div>' +
        '<div class="meta">' + size(e.size) + ' · ' + new Date(e.mtime).toLocaleString() + '</div></div></div>'
    }).join('')
    listEl.querySelectorAll('a[data-dir]').forEach(function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault()
        openDir(a.getAttribute('data-dir'))
      })
    })
  }
  function load() {
    setStatus('读取中…')
    fetch('/files/api/list?path=' + encodeURIComponent(cwd), { credentials: 'same-origin' })
      .then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status)); return j }) })
      .then(function (data) { setStatus(''); renderList(data) })
      .catch(function (e) { setStatus(e.message, true); listEl.innerHTML = '<div class="empty">读取失败</div>' })
  }
  function uploadOne(file, index, total) {
    return new Promise(function (resolve) {
      var xhr = new XMLHttpRequest()
      xhr.open('POST', '/files/api/upload?dir=' + encodeURIComponent(cwd) + '&name=' + encodeURIComponent(file.name))
      xhr.setRequestHeader('X-DSH-Files', '1')
      xhr.upload.onprogress = function (e) {
        if (e.lengthComputable) setStatus('上传 ' + (index + 1) + '/' + total + '：' + Math.round(e.loaded / e.total * 100) + '%')
      }
      xhr.onload = function () {
        var ok = xhr.status >= 200 && xhr.status < 300
        if (!ok) setStatus('上传失败：' + (xhr.responseText || ('HTTP ' + xhr.status)), true)
        resolve(ok)
      }
      xhr.onerror = function () { setStatus('上传网络错误', true); resolve(false) }
      xhr.send(file)
    })
  }
  document.getElementById('pick').addEventListener('click', function () { fileInput.click() })
  fileInput.addEventListener('change', function () {
    var files = Array.prototype.slice.call(fileInput.files || [])
    if (!files.length) return
    var chain = Promise.resolve(true)
    files.forEach(function (file, i) {
      chain = chain.then(function (ok) { return ok ? uploadOne(file, i, files.length) : false })
    })
    chain.then(function (ok) { fileInput.value = ''; if (ok) { setStatus('上传完成'); load() } })
  })
  document.getElementById('mkdir').addEventListener('click', function () {
    var name = prompt('新文件夹名称')
    if (!name) return
    fetch('/files/api/mkdir?dir=' + encodeURIComponent(cwd) + '&name=' + encodeURIComponent(name), {
      method: 'POST', credentials: 'same-origin', headers: { 'X-DSH-Files': '1' }
    }).then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status)); return j }) })
      .then(function () { setStatus('已创建'); load() })
      .catch(function (e) { setStatus(e.message, true) })
  })
  document.getElementById('refresh').addEventListener('click', load)
  load()
})()
</script>
</body></html>`
}
