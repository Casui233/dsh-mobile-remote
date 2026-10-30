/**
 * dsh-mobile-bridge —— 浏览器半端（设置 → 手机连接）。
 *
 * client bundle 不是 ESM，是自注册脚本：window.__ModuleLoader__.load({ id, factory })。
 * 设置页注册进 `settings.section`（label 就是左侧导航那一项）。
 * 数据全部走宿主侧 /api/mobile/*（已过 DSH 信任栅栏）。
 */
window.__ModuleLoader__.load({
  id: '@local/dsh-mobile-bridge',
  factory: (require) => {
    const React = require('react')
    const h = React.createElement
    const { useEffect, useState } = React

    const API = '/api/mobile'

    const CSS = `
[data-mb-page]{display:flex;flex-direction:column;gap:16px;padding:2px 2px 10px;max-width:680px;}
[data-mb-card]{display:flex;flex-direction:column;gap:12px;padding:14px;border-radius:12px;
  border:1px solid var(--dsw-alias-bg-layer-3,rgba(255,255,255,.10));
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.06));}
[data-mb-title]{font-size:13px;font-weight:600;letter-spacing:.02em;}
[data-mb-row]{display:flex;align-items:center;gap:12px;}
[data-mb-grow]{flex:1;min-width:0;}
[data-mb-label]{font-size:13px;line-height:20px;}
[data-mb-note]{font-size:11px;line-height:16px;color:var(--dsw-alias-label-tertiary,#9aa0a6);word-break:break-word;}
[data-mb-input]{width:100%;box-sizing:border-box;padding:7px 9px;border-radius:8px;font-size:12px;
  border:1px solid var(--dsw-alias-bg-layer-3,rgba(255,255,255,.14));
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.12));color:inherit;font-family:inherit;}
[data-mb-input]:focus{outline:none;border-color:#3b82f6;}
[data-mb-input][data-mb-small]{max-width:130px;}
[data-mb-select]{padding:7px 9px;border-radius:8px;font-size:12px;color:inherit;font-family:inherit;
  border:1px solid var(--dsw-alias-bg-layer-3,rgba(255,255,255,.14));
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.12));}
[data-mb-btn]{padding:7px 12px;border-radius:8px;font-size:12px;cursor:pointer;font-family:inherit;white-space:nowrap;
  border:1px solid var(--dsw-alias-bg-layer-3,rgba(255,255,255,.16));
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.14));color:inherit;}
[data-mb-btn]:hover{background:var(--dsw-alias-interactive-bg-hover,rgba(128,128,128,.24));}
[data-mb-btn][data-mb-primary]{background:#3b82f6;border-color:#3b82f6;color:#fff;}
[data-mb-btn][disabled]{opacity:.45;cursor:default;}
[data-mb-sw]{flex:none;width:38px;height:22px;border-radius:11px;border:0;cursor:pointer;position:relative;
  background:var(--dsw-alias-interactive-bg-hover,rgba(128,128,128,.35));transition:background .15s;padding:0;}
[data-mb-sw][data-on]{background:#3b82f6;}
[data-mb-sw] i{position:absolute;top:3px;left:3px;width:16px;height:16px;border-radius:50%;background:#fff;
  transition:left .15s;display:block;}
[data-mb-sw][data-on] i{left:19px;}
[data-mb-sw][disabled]{opacity:.45;cursor:default;}
[data-mb-code]{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:30px;letter-spacing:.32em;
  font-weight:700;color:#8ab4f8;padding:6px 0;}
[data-mb-qr]{display:block;width:200px;height:200px;margin:10px auto 6px;background:#fff;border-radius:12px;padding:8px;}
[data-mb-msg]{font-size:11px;line-height:16px;padding:7px 9px;border-radius:8px;word-break:break-word;}
[data-mb-msg][data-ok]{background:rgba(52,168,83,.16);color:#7ee2a8;}
[data-mb-msg][data-bad]{background:rgba(234,67,53,.16);color:#ff9b90;}
[data-mb-dev]{display:flex;align-items:center;gap:10px;padding:8px 0;border-top:1px solid var(--dsw-alias-bg-layer-3,rgba(255,255,255,.08));}
[data-mb-dev]:first-of-type{border-top:0;}
[data-mb-devname]{font-size:12.5px;}
[data-mb-pill]{font-size:10px;padding:1px 7px;border-radius:999px;background:rgba(52,168,83,.18);color:#7ee2a8;}
[data-mb-pill][data-off]{background:rgba(234,67,53,.18);color:#ff9b90;}
[data-mb-grid]{display:grid;grid-template-columns:1fr 1fr;gap:10px;}
[data-mb-hr]{height:1px;background:var(--dsw-alias-bg-layer-3,rgba(255,255,255,.08));margin:2px 0;}
@media (max-width:520px){[data-mb-grid]{grid-template-columns:1fr;}}
`

    const asJson = async (res) => {
      let body = null
      try {
        body = await res.json()
      } catch {
        body = null
      }
      if (!res.ok || (body && body.ok === false)) {
        const why = body && body.error ? body.error : `HTTP ${res.status}`
        if (res.status === 401) throw new Error(`未登录或没有权限（${why}）`)
        if (res.status === 403) throw new Error(`被宿主拦住了（${why}）`)
        throw new Error(why)
      }
      return body
    }
    const jget = async (path) => asJson(await fetch(API + path, { credentials: 'same-origin' }))
    const jpost = async (path, body) => asJson(await fetch(API + path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body || {}),
    }))

    function useStatus() {
      const [state, setState] = useState({ loading: true, data: null, error: '' })
      const load = React.useCallback(async () => {
        try {
          const data = await jget('/status')
          setState({ loading: false, data, error: '' })
        } catch (error) {
          setState((prev) => ({ loading: false, data: prev.data, error: String(error?.message ?? error) }))
        }
      }, [])
      useEffect(() => {
        load()
        const timer = setInterval(load, 5000)
        return () => clearInterval(timer)
      }, [load])
      return { state, load, setState }
    }

    function Field(props) {
      return h('div', { 'data-mb-grow': '' },
        h('div', { 'data-mb-label': '' }, props.label),
        props.hint ? h('div', { 'data-mb-note': '' }, props.hint) : null,
        h('input', {
          'data-mb-input': '',
          'data-mb-small': props.small ? '' : undefined,
          type: props.type || 'text',
          value: props.value == null ? '' : String(props.value),
          placeholder: props.placeholder || '',
          disabled: props.disabled || false,
          onChange: (event) => props.onChange(event.target.value),
        }),
      )
    }

    function Toggle(props) {
      return h('div', { 'data-mb-row': '' },
        h('div', { 'data-mb-grow': '' },
          h('div', { 'data-mb-label': '' }, props.label),
          props.hint ? h('div', { 'data-mb-note': '' }, props.hint) : null),
        h('button', {
          'data-mb-sw': '',
          'data-on': props.value ? '' : undefined,
          disabled: props.disabled || false,
          onClick: () => props.onChange(!props.value),
          'aria-label': props.label,
        }, h('i', null)),
      )
    }

    function DeviceRow(props) {
      const device = props.device
      const seen = device.lastSeenAt ? new Date(device.lastSeenAt).toLocaleString() : '从未连接'
      return h('div', { 'data-mb-dev': '' },
        h('div', { 'data-mb-grow': '' },
          h('div', { 'data-mb-devname': '' }, device.name, ' ',
            h('span', { 'data-mb-pill': '' }, device.platform || 'device')),
          h('div', { 'data-mb-note': '' }, '最后活跃：' + seen),
        ),
        h('button', {
          'data-mb-btn': '',
          disabled: props.busy,
          onClick: () => props.onRevoke(device),
        }, '撤销'),
      )
    }

    function Panel() {
      const { state, load, setState } = useStatus()
      const [form, setForm] = useState(null)
      const [notice, setNotice] = useState(null)
      const [busy, setBusy] = useState('')
      const [now, setNow] = useState(Date.now())

      useEffect(() => {
        const timer = setInterval(() => setNow(Date.now()), 1000)
        return () => clearInterval(timer)
      }, [])

      const st = state.data
      useEffect(() => {
        if (st && !form) setForm({ ...st.config })
      }, [st, form])

      const act = async (name, fn, okText) => {
        setBusy(name)
        setNotice(null)
        try {
          const result = await fn()
          setNotice({ ok: true, text: typeof okText === 'function' ? okText(result) : (okText || '完成') })
          await load()
          return result
        } catch (error) {
          setNotice({ ok: false, text: String(error?.message ?? error) })
          return null
        } finally {
          setBusy('')
        }
      }

      const update = (key, value) => setForm((prev) => ({ ...prev, [key]: value }))

      const saveConnection = () => act('save-conn', async () => {
        const result = await jpost('/config', {
          config: {
            enabled: form.enabled,
            bindHost: form.bindHost,
            port: Number(form.port) || 8765,
            publicOrigin: form.publicOrigin,
            allowNonTailscaleBind: Boolean(form.allowNonTailscaleBind),
          },
        })
        if (result && result.config) setForm({ ...result.config })
        return result
      }, (result) => (result && result.listenerChanged ? '已保存，手机侧监听正在重启' : '已保存'))

      const saveNotify = () => act('save-notify', async () => {
        const config = {
          notifyChannel: form.notifyChannel,
          notifyEndpoint: form.notifyEndpoint,
          notifyTopic: form.notifyTopic,
          notifyChatId: form.notifyChatId,
          notifyDone: Boolean(form.notifyDone),
          notifyError: Boolean(form.notifyError),
          notifyApproval: Boolean(form.notifyApproval),
          notifyQuestion: Boolean(form.notifyQuestion),
          notifyQuietHours: form.notifyQuietHours,
          notifyMinRunMs: Number(form.notifyMinRunMs) || 0,
        }
        if (form.notifyToken) config.notifyToken = form.notifyToken
        const result = await jpost('/config', { config })
        if (result && result.config) setForm({ ...result.config, notifyToken: '' })
        return result
      }, '通知设置已保存')

      const clearToken = () => act('clear-token', async () => {
        const result = await jpost('/config', { clearNotifyToken: true })
        if (result && result.config) setForm({ ...result.config, notifyToken: '' })
        return result
      }, '已清除保存的推送密钥')

      const generateCode = () => act('pair', async () => {
        const result = await jpost('/pair-code', {})
        return result
      }, '配对码已生成，5 分钟内有效')

      const revoke = (device) => {
        if (!window.confirm(`撤销「${device.name}」？这台手机下次请求会被拒绝。`)) return
        act('revoke-' + device.id, () => jpost('/revoke', { deviceId: device.id }), '已撤销')
      }

      const revokeAll = () => {
        if (!window.confirm('撤销所有已配对设备？')) return
        act('revoke-all', () => jpost('/revoke', { all: true }), '已全部撤销')
      }

      const testNotify = () => act('test', () => jpost('/test-notify', {}), (result) => (
        result && result.ok ? '测试通知已发出' : `没发出去：${(result && result.error) || '未知原因'}`
      ))

      if (!st || !form) {
        return h('div', { 'data-mb-page': '' },
          h('div', { 'data-mb-card': '' },
            h('div', { 'data-mb-title': '' }, '手机连接'),
            h('div', { 'data-mb-note': '' }, state.error || '正在读取桥接状态…'),
          ),
        )
      }

      const pairing = st.pairing
      const remain = pairing ? Math.max(0, Math.floor((pairing.expiresAt - now) / 1000)) : 0
      const source = st.listener?.healthy ? '运行中' : (st.listener?.error || '未运行')

      return h('div', { 'data-mb-page': '' },

        notice ? h('div', { 'data-mb-msg': '', 'data-ok': notice.ok ? '' : undefined, 'data-bad': notice.ok ? undefined : '' }, notice.text) : null,
        state.error ? h('div', { 'data-mb-msg': '', 'data-bad': '' }, state.error) : null,

        h('div', { 'data-mb-card': '' },
          h('div', { 'data-mb-title': '' }, '桥接状态'),
          h('div', { 'data-mb-row': '' },
            h('div', { 'data-mb-grow': '' },
              h('div', { 'data-mb-label': '' }, st.listener?.url || '还没有可用的手机地址'),
              h('div', { 'data-mb-note': '' }, `监听：${source}　·　内部登录态：${st.internalAuth?.ok ? '正常' : (st.internalAuth?.error || '未建立')}`),
            ),
            h('span', { 'data-mb-pill': '', 'data-off': st.listener?.healthy ? undefined : '' }, st.listener?.healthy ? '在线' : '离线'),
          ),
          h('div', { 'data-mb-note': '' },
            '手机和电脑都要登录同一个 Tailscale。手机 App 里填这个地址 + 配对码即可；地址变了就重新配对。'),
        ),

        h('div', { 'data-mb-card': '' },
          h('div', { 'data-mb-title': '' }, '监听设置'),
          h(Toggle, {
            label: '启用手机监听',
            hint: '关闭后不再接受手机请求（管理接口仍可用）',
            value: Boolean(form.enabled),
            onChange: (value) => update('enabled', value),
          }),
          h('div', { 'data-mb-grid': '' },
            h(Field, {
              label: '绑定地址',
              hint: '留空自动检测 Tailscale（100.64.0.0/10）',
              value: form.bindHost,
              placeholder: st.detect?.tailscale || '100.x.y.z',
              onChange: (value) => update('bindHost', value),
            }),
            h(Field, {
              label: '端口',
              hint: '手机端地址用这个端口',
              small: true,
              value: form.port,
              onChange: (value) => update('port', value),
            }),
          ),
          h(Field, {
            label: '对外地址（publicOrigin）',
            hint: '留空 = http://绑定地址:端口；建议填 Tailscale MagicDNS 名，IP 变了也不用重配',
            value: form.publicOrigin,
            placeholder: 'http://my-pc.tailxxxx.ts.net:8765',
            onChange: (value) => update('publicOrigin', value),
          }),
          h(Field, {
            label: '文件共享目录',
            hint: '手机文件页只暴露这个绝对路径；留空默认 ~/DSH-Shared。建议填 DSH 工作区下的 shared/',
            value: form.sharedDir,
            placeholder: 'C:\\Users\\me\\Documents\\deepseek-harness\\default-workspace\\shared',
            onChange: (value) => update('sharedDir', value),
          }),
          h(Toggle, {
            label: '允许绑定非 Tailscale 地址',
            hint: '默认拒绝；只在明确知道风险时打开',
            value: Boolean(form.allowNonTailscaleBind),
            onChange: (value) => update('allowNonTailscaleBind', value),
          }),
          h('div', { 'data-mb-row': '' },
            h('button', {
              'data-mb-btn': '',
              'data-mb-primary': '',
              disabled: busy === 'save-conn',
              onClick: saveConnection,
            }, busy === 'save-conn' ? '保存中…' : '保存并应用'),
          ),
        ),

        h('div', { 'data-mb-card': '' },
          h('div', { 'data-mb-title': '' }, '配对手机'),
          h('div', { 'data-mb-note': '' }, '点一下生成 6 位配对码，5 分钟内有效、只能成功一次。'),
          pairing
            ? h('div', null,
                h('div', { 'data-mb-code': '' }, pairing.code),
                h('img', {
                  'data-mb-qr': '',
                  src: '/api/mobile/pair-qr?v=' + pairing.expiresAt,
                  alt: '配对二维码',
                }),
                h('div', { 'data-mb-note': '' }, `剩余 ${Math.floor(remain / 60)}:${String(remain % 60).padStart(2, '0')}　·　手机系统相机扫码，或手动输入配对码`),
                h('div', { 'data-mb-note': '' }, '二维码含一次性配对码，别截图外发。'),
              )
            : h('div', { 'data-mb-note': '' }, '当前没有有效配对码。'),
          h('div', { 'data-mb-row': '' },
            h('button', {
              'data-mb-btn': '',
              'data-mb-primary': '',
              disabled: busy === 'pair',
              onClick: generateCode,
            }, pairing ? '重新生成' : '生成配对码'),
          ),
          h('div', { 'data-mb-hr': '' }),
          h('div', { 'data-mb-row': '' },
            h('div', { 'data-mb-grow': '' }, h('div', { 'data-mb-label': '' }, `已配对设备（${st.devices.length}）`)),
            st.devices.length ? h('button', { 'data-mb-btn': '', disabled: busy === 'revoke-all', onClick: revokeAll }, '全部断开') : null,
          ),
          st.devices.length
            ? st.devices.map((device) => h(DeviceRow, {
                key: device.id,
                device,
                busy: busy === 'revoke-' + device.id,
                onRevoke: revoke,
              }))
            : h('div', { 'data-mb-note': '' }, '还没有设备。生成配对码，在手机上添加这台电脑。'),
        ),

        h('div', { 'data-mb-card': '' },
          h('div', { 'data-mb-title': '' }, '任务通知'),
          h('div', { 'data-mb-note': '' },
            '任务完成 / 出错 / 等你批准 / 等你回答时推送到手机。ntfy 与 Bark 用 JSON 推送，中文不会乱码。'),
          h('div', { 'data-mb-row': '' },
            h('div', { 'data-mb-grow': '' }, h('div', { 'data-mb-label': '' }, '推送渠道')),
            h('select', {
              'data-mb-select': '',
              value: form.notifyChannel,
              onChange: (event) => update('notifyChannel', event.target.value),
            },
              h('option', { value: 'off' }, '关闭'),
              h('option', { value: 'ntfy' }, 'ntfy'),
              h('option', { value: 'bark' }, 'Bark（iOS）'),
              h('option', { value: 'telegram' }, 'Telegram'),
            ),
          ),
          form.notifyChannel === 'off' ? null : h('div', null,
            h('div', { 'data-mb-grid': '' },
              h(Field, {
                label: '服务地址',
                hint: form.notifyChannel === 'ntfy' ? '默认 https://ntfy.sh' : form.notifyChannel === 'bark' ? '默认 https://api.day.app' : '默认 https://api.telegram.org',
                value: form.notifyEndpoint,
                placeholder: form.notifyChannel === 'ntfy' ? 'https://ntfy.sh' : form.notifyChannel === 'bark' ? 'https://api.day.app' : 'https://api.telegram.org',
                onChange: (value) => update('notifyEndpoint', value),
              }),
              form.notifyChannel === 'ntfy'
                ? h(Field, {
                    label: 'Topic',
                    hint: '手机 ntfy App 订阅同一个 topic',
                    value: form.notifyTopic,
                    onChange: (value) => update('notifyTopic', value),
                  })
                : form.notifyChannel === 'bark'
                  ? h(Field, {
                      label: 'Bark 设备 key',
                      hint: form.notifyTokenSet ? '已保存；留空保持不变' : 'Bark App 里那串 key',
                      value: form.notifyToken,
                      onChange: (value) => update('notifyToken', value),
                    })
                  : h('div', { 'data-mb-grid': '' },
                      h(Field, {
                        label: 'Bot token',
                        hint: form.notifyTokenSet ? '已保存；留空保持不变' : 'BotFather 给的 token',
                        value: form.notifyToken,
                        onChange: (value) => update('notifyToken', value),
                      }),
                      h(Field, {
                        label: 'Chat ID',
                        hint: '私聊用 /start 后从 getUpdates 取',
                        value: form.notifyChatId,
                        onChange: (value) => update('notifyChatId', value),
                      }),
                    ),
            ),
            h('div', { 'data-mb-grid': '' },
              h(Toggle, { label: '任务完成', value: Boolean(form.notifyDone), onChange: (value) => update('notifyDone', value) }),
              h(Toggle, { label: '任务出错', value: Boolean(form.notifyError), onChange: (value) => update('notifyError', value) }),
              h(Toggle, { label: '等待批准', value: Boolean(form.notifyApproval), onChange: (value) => update('notifyApproval', value) }),
              h(Toggle, { label: '等待回答', value: Boolean(form.notifyQuestion), onChange: (value) => update('notifyQuestion', value) }),
            ),
            h('div', { 'data-mb-grid': '' },
              h(Field, {
                label: '免打扰时段',
                hint: '例 23:00-08:00；期间 ntfy 降为低优先级',
                value: form.notifyQuietHours,
                placeholder: '23:00-08:00',
                onChange: (value) => update('notifyQuietHours', value),
              }),
              h(Field, {
                label: '最短任务时长（毫秒）',
                hint: '太短的任务不发“完成”',
                small: true,
                value: form.notifyMinRunMs,
                onChange: (value) => update('notifyMinRunMs', value),
              }),
            ),
          ),
          h('div', { 'data-mb-row': '' },
            h('button', {
              'data-mb-btn': '',
              'data-mb-primary': '',
              disabled: busy === 'save-notify',
              onClick: saveNotify,
            }, busy === 'save-notify' ? '保存中…' : '保存通知设置'),
            h('button', {
              'data-mb-btn': '',
              disabled: busy === 'test' || form.notifyChannel === 'off',
              onClick: testNotify,
            }, busy === 'test' ? '发送中…' : '发送测试'),
            form.notifyTokenSet
              ? h('button', { 'data-mb-btn': '', disabled: busy === 'clear-token', onClick: clearToken }, '清除密钥')
              : null,
          ),
        ),

        h('div', { 'data-mb-note': '' },
          `版本 ${st.version}　·　设备 ${st.devices.length} 台　·　地址 ${st.publicOrigin || '（未确定）'}`),
      )
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        const style = document.createElement('style')
        style.setAttribute('data-plugin-css', '@local/dsh-mobile-bridge')
        style.textContent = CSS
        document.head.appendChild(style)
        ctx.effect(() => () => style.remove(), 'mobile-bridge: 设置页样式')

        const slots = (typeof ctx.get === 'function' ? ctx.get('slots') : undefined) || ctx.slots
        if (!slots) return
        slots.inject('settings.section', () => slots.register(
          { name: 'settings.section', id: 'mobile-bridge', order: 35, label: '手机连接' },
          (props) => h(Panel, props || {}),
        ))
      },
    }
  },
})
