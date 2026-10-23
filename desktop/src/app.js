'use strict'

// ================= API =================

const BASE = atob('aHR0cHM6Ly9tM3U4LmdpcmlnaXJpbG92ZS5jb20vYXBpLnBocC8=')
const IMG = atob('aHR0cHM6Ly9qcGcuZ2lyaWdpcmlsb3ZlLmNvbS8=')

const TYPES = [[2, '日番'], [21, '劇場版'], [3, '美番'], [20, '真人番劇'], [24, 'BD副音軌'], [26, '演唱會及其他']]
const ORDERS = [['pubdate', '最新'], ['hits', '總熱度'], ['hits_week', '本週熱門'], ['hits_month', '本月熱門'], ['hits_day', '今日熱門'], ['score', '評分']]
const WEEK = ['一', '二', '三', '四', '五', '六', '日']
const PAGE = 36

async function api(path, params = {}) {
  const url = new URL(BASE + path)
  for (const [k, v] of Object.entries(params)) if (v !== '' && v != null) url.searchParams.set(k, v)
  let res
  try {
    res = await fetch(url, { headers: { Accept: 'application/json' } })
  } catch {
    throw new Error('網路錯誤，請檢查連線')
  }
  if (!res.ok) throw new Error(`伺服器錯誤（HTTP ${res.status}）`)
  const json = await res.json().catch(() => { throw new Error('伺服器回應格式錯誤') })
  if (json.code !== 1) throw new Error(json.msg || '載入失敗')
  return json.info
}

function img(p) {
  if (!p) return ''
  if (p.startsWith('http')) return p
  if (p.startsWith('//')) return 'https:' + p
  return IMG + p.replace(/^\/+/, '')
}

// 有些欄位會是數字（例如時間表的 vod_time），一律轉成字串
const str = (v) => (v == null ? '' : String(v)).trim()
const anime = (o) => ({
  id: o.vod_id,
  name: str(o.vod_name),
  cover: img(str(o.vod_pic)),
  remarks: str(o.vod_remarks) || (typeof o.vod_time === 'string' ? str(o.vod_time) : ''),
  year: str(o.vod_year),
  score: str(o.vod_score),
})

// 繁體 → 簡體（逐字，對照表在 t2s.js）。網站資料都是簡體，搜尋時先轉換，打繁體也找得到
let t2sMap = null
function toSimplified(s) {
  if (!s) return s
  if (!t2sMap) {
    t2sMap = new Map()
    const a = [...(window.T2S_PAIRS || '')]
    for (let i = 0; i + 1 < a.length; i += 2) t2sMap.set(a[i], a[i + 1])
  }
  return [...s].map((c) => t2sMap.get(c) || c).join('')
}

async function list(q, offset, limit = PAGE) {
  const info = await api('Vod/get_list', {
    type_id: q.typeId, offset, limit, orderby: q.orderby || 'pubdate',
    vod_name: toSimplified(q.name), vod_class: q.cls, vod_year: q.year, vod_version: q.version,
  })
  return { items: (info.rows || []).map(anime), total: info.total || 0 }
}

function parseSources(from, urls) {
  const names = (from || '').split('$$$')
  return (urls || '').split('$$$').map((group, i) => {
    const eps = group.split('#').map((part) => {
      const idx = part.indexOf('$')
      const name = idx >= 0 ? part.slice(0, idx).trim() : ''
      const url = (idx >= 0 ? part.slice(idx + 1) : part).trim()
      return url.startsWith('http') ? { name: name || '播放', url } : null
    }).filter(Boolean)
    const code = (names[i] || '').toLowerCase()
    const label = code === 'cht' ? '繁體中文' : code === 'chs' ? '簡體中文' : code || `線路 ${i + 1}`
    return eps.length ? { name: label, episodes: eps } : null
  }).filter(Boolean)
}

const stripHtml = (s) => (s || '').replace(/<br\s*\/?>/gi, '\n').replace(/<[^>]+>/g, '')
  .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').trim()

const detailCache = new Map()
async function detail(id) {
  if (detailCache.has(id)) return detailCache.get(id)
  const o = await api('Vod/get_detail', { vod_id: id })
  const d = {
    anime: anime(o),
    sub: o.vod_sub || '',
    blurb: stripHtml(o.vod_content) || o.vod_blurb || '',
    classes: (o.vod_class || '').split(/[,，/]/).map((s) => s.trim()).filter(Boolean),
    area: o.vod_area || '', lang: o.vod_lang || '', version: o.vod_version || '',
    director: o.vod_director || '', actors: o.vod_actor || '', weekday: o.vod_weekday || '',
    sources: parseSources(o.vod_play_from, o.vod_play_url),
  }
  detailCache.set(id, d)
  return d
}

// ================= 彈幕 =================
// 兩個來源合併：站內使用者彈幕 + 外部彙整彈幕 XML（各彈幕站匯總，B 站格式）。
// 時間都是對應同一個影片檔的秒數。

const hexColor = (v) => {
  if (typeof v === 'string') {
    const t = v.trim()
    if (/^#[0-9a-f]{6}$/i.test(t)) return t.toLowerCase()
    if (/^[0-9a-f]{6}$/i.test(t)) return '#' + t.toLowerCase()
    if (t && Number.isFinite(Number(t))) return hexColor(Number(t))
  }
  if (typeof v === 'number' && Number.isFinite(v)) return '#' + Math.max(0, Math.min(0xffffff, Math.trunc(v))).toString(16).padStart(6, '0')
  return '#ffffff'
}

async function postForm(path, playUrl) {
  const fd = new FormData()
  fd.append('play_url', playUrl)
  const res = await fetch(BASE + path, { method: 'POST', body: fd, headers: { Accept: 'application/json' } })
  const json = await res.json()
  return json.code === 1 ? json.info : null
}

async function danmakuInternal(playUrl) {
  const arr = await postForm('Scrolling/getScrolling', playUrl).catch(() => null)
  return (Array.isArray(arr) ? arr : []).map((o) => {
    let j = o.scroll_json
    if (typeof j === 'string') { try { j = JSON.parse(j) } catch { j = null } }
    if (!j) return null
    const time = Number(j.time)
    const text = String(j.content ?? j.text ?? '').trim()
    if (!Number.isFinite(time) || time < 0 || !text) return null
    const mode = Number(j.mode)
    return { time, text, color: hexColor(j.style?.color ?? j.color), type: mode === 2 ? 'top' : mode === 3 ? 'bottom' : 'scroll' }
  }).filter(Boolean)
}

/** 自家 CDN 的 …/01/playlist.m3u8 → …/01.xml（和網站播放器同樣的規則） */
function fallbackXml(playUrl) {
  try {
    const u = new URL(playUrl)
    const site = new URL(BASE).hostname.split('.').slice(1).join('.')
    if (u.protocol !== 'https:' || !u.hostname.endsWith('.' + site) || !u.pathname.startsWith('/zijian/') || !u.pathname.endsWith('/playlist.m3u8')) return null
    return `https://${u.hostname}${u.pathname.replace(/\/playlist\.m3u8$/, '.xml')}`
  } catch {
    return null
  }
}

async function danmakuExternal(playUrl) {
  try {
    const info = await postForm('Scrolling/getVodOutScrolling', playUrl).catch(() => null)
    const url = (typeof info === 'string' && info.trim().startsWith('http') ? info.trim() : null) || fallbackXml(playUrl)
    if (!url) return []
    const res = await fetch(url)
    if (!res.ok) return []
    const doc = new DOMParser().parseFromString(await res.text(), 'text/xml')
    return [...doc.getElementsByTagName('d')].map((d) => {
      const p = (d.getAttribute('p') || '').split(',')
      const time = Number(p[0])
      const text = (d.textContent || '').trim()
      if (!Number.isFinite(time) || time < 0 || !text) return null
      const mode = Number(p[1])
      return { time, text, color: hexColor(Number(p[3])), type: mode === 5 ? 'top' : mode === 4 ? 'bottom' : 'scroll' }
    }).filter(Boolean)
  } catch {
    return []
  }
}

async function loadDanmaku(playUrl) {
  const [a, b] = await Promise.all([danmakuInternal(playUrl), danmakuExternal(playUrl)])
  return [...a, ...b].sort((x, y) => x.time - y.time)
}

const SCROLL_SEC = 8
const FIXED_SEC = 4

/**
 * 彈幕層。每則彈幕的位置都由「影片目前時間 − 彈幕時間」算出來，不用自己的計時器，
 * 所以暫停會停住、拖動會跳到對的位置、倍速時跟著變快，永遠和影片時間軸對齊。
 */
function danmakuLayer(canvas, video) {
  const ctx = canvas.getContext('2d')
  const st = {
    items: [], on: store.get('dm_on', true), size: store.get('dm_size', 1), alpha: store.get('dm_alpha', 0.85),
    area: store.get('dm_area', 0.5), offset: 0,
  }
  let active = [], lanes = {}, next = 0, lastPos = NaN, raf = 0, W = 0, H = 0

  const reset = () => { active = []; lanes = { scroll: [], top: [], bottom: [] }; lastPos = NaN }
  const lowerBound = (t) => {
    let lo = 0, hi = st.items.length
    while (lo < hi) { const m = (lo + hi) >> 1; st.items[m].time < t ? (lo = m + 1) : (hi = m) }
    return lo
  }
  const fontSize = () => Math.max(14, H / 22) * st.size
  const lineH = () => fontSize() * 1.25
  const speed = (w) => (W + w) / SCROLL_SEC

  function spawn(d) {
    const w = ctx.measureText(d.text).width
    const line = lineH()
    if (d.type === 'scroll') {
      const count = Math.max(1, Math.floor((H * st.area) / line))
      for (let i = 0; i < count; i++) {
        const last = lanes.scroll[i]
        // 同軌道上一則的尾巴已經離開右邊，而且新的在上一則離開畫面前追不上它
        const ok = !last || (
          W - (d.time - last.d.time) * speed(last.w) + last.w + 16 <= W &&
          W - (last.d.time + SCROLL_SEC - d.time) * speed(w) >= 0
        )
        if (ok) { const a = { d, w, lane: i }; lanes.scroll[i] = a; active.push(a); return }
      }
    } else {
      const list = lanes[d.type]
      const count = Math.max(1, Math.floor((H * 0.3) / line))
      for (let i = 0; i < count; i++) {
        const last = list[i]
        if (!last || d.time - last.d.time >= FIXED_SEC) { const a = { d, w, lane: i }; list[i] = a; active.push(a); return }
      }
    }
    // 軌道都滿了就不顯示，避免疊在一起
  }

  function frame() {
    raf = requestAnimationFrame(frame)
    const dpr = window.devicePixelRatio || 1
    const cw = canvas.clientWidth, ch = canvas.clientHeight
    if (cw !== W || ch !== H) {
      W = cw; H = ch
      canvas.width = Math.round(cw * dpr); canvas.height = Math.round(ch * dpr)
      reset()
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, W, H)
    if (!st.on || !st.items.length || !W) return

    const pos = video.currentTime - st.offset
    // 往回拖或往前跳超過 1 秒：重新排，「現在應該在畫面上」的彈幕也補上
    if (Number.isNaN(lastPos) || pos < lastPos - 0.05 || pos - lastPos > 1) {
      reset()
      next = lowerBound(pos - SCROLL_SEC)
    }
    lastPos = pos

    const fs = fontSize(), line = lineH()
    ctx.font = `bold ${fs}px "Microsoft JhengHei", "PingFang TC", sans-serif`
    ctx.textBaseline = 'alphabetic'
    ctx.lineJoin = 'round'
    ctx.lineWidth = Math.max(1.5, fs / 12)
    ctx.strokeStyle = 'rgba(0,0,0,0.8)'
    ctx.globalAlpha = st.alpha

    while (next < st.items.length && st.items[next].time <= pos) {
      const d = st.items[next++]
      const age = pos - d.time
      if (age < (d.type === 'scroll' ? SCROLL_SEC : FIXED_SEC)) spawn(d)
    }

    active = active.filter((a) => {
      const age = pos - a.d.time
      let x, y
      if (a.d.type === 'scroll') {
        if (age > SCROLL_SEC) return false
        x = W - age * speed(a.w); y = line * (a.lane + 1) - line * 0.22
      } else {
        if (age > FIXED_SEC) return false
        x = (W - a.w) / 2
        y = a.d.type === 'top' ? line * (a.lane + 1) - line * 0.22 : H - line * a.lane - line * 0.35
      }
      ctx.strokeText(a.d.text, x, y)
      ctx.fillStyle = a.d.color
      ctx.fillText(a.d.text, x, y)
      return true
    })
    ctx.globalAlpha = 1
  }

  reset()
  raf = requestAnimationFrame(frame)
  return {
    st,
    set(items) { st.items = items; reset() },
    update(k, v) { st[k] = v; if (k !== 'offset') store.set('dm_' + k, v); reset() },
    destroy() { cancelAnimationFrame(raf) },
  }
}

// ================= 本機儲存（收藏、紀錄、進度） =================

const store = {
  get(k, def) {
    try { return JSON.parse(localStorage.getItem(k)) ?? def } catch { return def }
  },
  set(k, v) {
    try { localStorage.setItem(k, JSON.stringify(v)) } catch {}
  },
  favorites() { return this.get('favorites', []) },
  isFav(id) { return this.favorites().some((a) => a.id === id) },
  toggleFav(a) {
    let f = this.favorites()
    if (f.some((x) => x.id === a.id)) f = f.filter((x) => x.id !== a.id)
    else f.unshift(a)
    this.set('favorites', f)
  },
  // 待看清單：想看但還沒開始看的作品，新加的在前面
  later() { return this.get('watch_later', []) },
  isLater(id) { return this.later().some((a) => a.id === id) },
  toggleLater(a) {
    let l = this.later()
    if (l.some((x) => x.id === a.id)) l = l.filter((x) => x.id !== a.id)
    else l.unshift(a)
    this.set('watch_later', l)
  },
  history() { return this.get('history', []) },
  last(id) { return this.history().find((h) => h.anime.id === id) },
  addHistory(item) {
    const h = this.history().filter((x) => x.anime.id !== item.anime.id)
    h.unshift(item)
    this.set('history', h.slice(0, 300))
  },
  clearHistory() {
    this.set('history', [])
    Object.keys(localStorage).filter((k) => k.startsWith('pos_')).forEach((k) => localStorage.removeItem(k))
  },
  pos(id, s, e) { return Number(localStorage.getItem(`pos_${id}_${s}_${e}`)) || 0 },
  savePos(id, s, e, sec) {
    const k = `pos_${id}_${s}_${e}`
    try { sec > 0 ? localStorage.setItem(k, String(Math.floor(sec))) : localStorage.removeItem(k) } catch {}
  },
  words() { return this.get('search_words', []) },
  addWord(w) { this.set('search_words', [w, ...this.words().filter((x) => x !== w)].slice(0, 20)) },
}

// ================= 畫面工具 =================

const view = document.getElementById('view')
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
const h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild }
const loading = () => '<div class="center"><div class="spinner"></div></div>'

function errorBox(msg, retry) {
  const el = h(`<div class="center"><div>${esc(msg)}</div><button>重試</button></div>`)
  el.querySelector('button').onclick = retry
  return el
}

function card(a, sub) {
  const score = a.score && a.score !== '0.0' ? `<span class="score">${esc(a.score)}</span>` : ''
  const badge = a.remarks ? `<div class="badge">${esc(a.remarks)}</div>` : ''
  const el = h(`<div class="card">
    <div class="cover"><img loading="lazy" src="${esc(a.cover)}" alt="">${badge}${score}</div>
    <div class="name">${esc(a.name)}</div>
    ${sub ? `<div class="sub">${esc(sub)}</div>` : ''}
  </div>`)
  el.onclick = () => go(`#/detail/${a.id}`)
  return el
}

function chips(label, options, selected, onSelect) {
  const el = h(`<div class="chips">${label ? `<span class="label">${esc(label)}</span>` : ''}</div>`)
  for (const [v, text] of options) {
    const b = h(`<button>${esc(text)}</button>`)
    if (String(v) === String(selected)) b.classList.add('on')
    b.onclick = () => onSelect(v)
    el.appendChild(b)
  }
  return el
}

/** 分頁載入的網格，捲到底自動載下一頁 */
function feed(container, q) {
  const grid = h('<div class="grid"></div>')
  const foot = h('<div></div>')
  container.append(grid, foot)
  let offset = 0, done = false, busy = false
  const token = (route.token = {})
  async function more() {
    if (busy || done || route.token !== token) return
    busy = true
    foot.innerHTML = loading()
    try {
      const p = await list(q, offset)
      if (route.token !== token) return
      p.items.forEach((a) => grid.appendChild(card(a)))
      offset += p.items.length
      done = p.items.length < PAGE || offset >= p.total
      foot.innerHTML = offset === 0 ? '<div class="center">這裡什麼都沒有</div>' : done ? '<div class="center muted">沒有更多了</div>' : ''
      if (!done && view.scrollHeight <= view.clientHeight + 300) more()
    } catch (e) {
      foot.replaceChildren(errorBox(e.message, () => { busy = false; more() }))
    } finally {
      busy = false
    }
  }
  route.onScroll = () => { if (view.scrollTop + view.clientHeight > view.scrollHeight - 600) more() }
  more()
}

// ================= 路由 =================

const route = { token: null, onScroll: null, cleanup: null }
view.addEventListener('scroll', () => route.onScroll && route.onScroll())

function go(hash) { location.hash = hash }

function render() {
  if (route.cleanup) { route.cleanup(); route.cleanup = null }
  route.onScroll = null
  route.token = {}
  view.scrollTop = 0
  const [path, qs] = location.hash.slice(1).split('?')
  const parts = path.split('/').filter(Boolean)
  const params = new URLSearchParams(qs || '')
  const tab = parts[0] || 'home'
  document.querySelectorAll('.sidebar nav a').forEach((a) => a.classList.toggle('active', a.dataset.tab === tab))
  view.innerHTML = ''
  switch (tab) {
    case 'browse': return pageBrowse()
    case 'week': return pageWeek()
    case 'search': return pageSearch(params.get('q') || '')
    case 'favorites': return pageLocal('favorites')
    case 'later': return pageLocal('later')
    case 'history': return pageLocal('history')
    case 'list': return pageList(params.get('title') || '', JSON.parse(params.get('q') || '{}'))
    case 'detail': return pageDetail(Number(parts[1]))
    case 'play': return pagePlay(Number(parts[1]), Number(parts[2]) || 0, Number(parts[3]) || 0)
    default: return pageHome()
  }
}
window.addEventListener('hashchange', render)

// ================= 頁面 =================

const HOME_ROWS = [
  ['本週熱門', { orderby: 'hits_week' }],
  ['最新日番', { typeId: 2, orderby: 'pubdate' }],
  ['劇場版', { typeId: 21, orderby: 'pubdate' }],
  ['美番', { typeId: 3, orderby: 'pubdate' }],
  ['高分推薦', { orderby: 'score' }],
  ['人氣經典', { orderby: 'hits' }],
]

async function pageHome() {
  const token = route.token
  view.innerHTML = loading()
  try {
    const [banners, rows] = await Promise.all([
      api('App2/bannerList').catch(() => []),
      Promise.all(HOME_ROWS.map(([t, q]) => list(q, 0, 18).then((p) => [t, q, p.items]))),
    ])
    if (route.token !== token) return
    view.innerHTML = ''

    // 輪播只留連到站內作品的
    const bs = (banners || []).map((b) => ({
      title: b.title, desc: b.description, cover: img(b.cover),
      id: Number((String(b.link || '').match(/^\/video\/(\d+)/) || [])[1] || b.video_key) || 0,
    })).filter((b) => b.id)
    if (bs.length) {
      const el = h('<div class="banner"><img alt=""><div class="info"><b></b><p></p></div><div class="dots"></div></div>')
      let i = 0
      const show = () => {
        const b = bs[i]
        el.querySelector('img').src = b.cover
        el.querySelector('b').textContent = b.title
        el.querySelector('p').textContent = b.desc
        el.querySelector('.dots').innerHTML = bs.map((_, j) => `<i class="${j === i ? 'on' : ''}"></i>`).join('')
      }
      show()
      el.onclick = () => go(`#/detail/${bs[i].id}`)
      const timer = setInterval(() => { i = (i + 1) % bs.length; show() }, 5000)
      route.cleanup = () => clearInterval(timer)
      view.appendChild(el)
    }

    const later = store.later().slice(0, 18)
    if (later.length) {
      const head = h('<h2>待看清單<span class="more">全部 ›</span></h2>')
      head.querySelector('.more').onclick = () => go('#/later')
      const row = h('<div class="row"></div>')
      later.forEach((x) => row.appendChild(card(x)))
      view.append(head, row)
    }
    const hist = store.history().slice(0, 18)
    if (hist.length) {
      view.appendChild(h('<h2>繼續觀看</h2>'))
      const row = h('<div class="row"></div>')
      hist.forEach((x) => row.appendChild(card({ ...x.anime, remarks: '' }, `看到第 ${x.episodeName} 集`)))
      view.appendChild(row)
    }
    for (const [title, q, items] of rows) {
      if (!items.length) continue
      const head = h(`<h2>${esc(title)}<span class="more">更多 ›</span></h2>`)
      head.querySelector('.more').onclick = () => go(`#/list?title=${encodeURIComponent(title)}&q=${encodeURIComponent(JSON.stringify(q))}`)
      const row = h('<div class="row"></div>')
      items.forEach((a) => row.appendChild(card(a)))
      view.append(head, row)
    }
  } catch (e) {
    if (route.token === token) view.replaceChildren(errorBox(e.message, render))
  }
}

let tagOptions = null
const browseState = { typeId: 2, orderby: 'pubdate', cls: '', year: '', version: '' }

async function pageBrowse() {
  const token = route.token
  if (!tagOptions) tagOptions = await api('Vod/getAllTagOption').catch(() => null)
  if (route.token !== token) return
  const now = new Date().getFullYear()
  const years = [['', '全部']].concat(Array.from({ length: now - 1999 }, (_, i) => [String(now - i), String(now - i)]))
  const draw = () => {
    route.token = {}
    view.innerHTML = '<h1>分類</h1>'
    const set = (k) => (v) => { browseState[k] = v; draw() }
    view.appendChild(chips('類別', TYPES, browseState.typeId, set('typeId')))
    view.appendChild(chips('排序', ORDERS, browseState.orderby, set('orderby')))
    if (tagOptions) {
      view.appendChild(chips('類型', [['', '全部'], ...tagOptions.vod_class.map((c) => [c, c])], browseState.cls, set('cls')))
      view.appendChild(chips('版本', [['', '全部'], ...tagOptions.vod_version.map((c) => [c, c])], browseState.version, set('version')))
    }
    view.appendChild(chips('年份', years, browseState.year, set('year')))
    view.appendChild(h('<div style="height:10px"></div>'))
    feed(view, { ...browseState })
  }
  draw()
}

function pageList(title, q) {
  view.innerHTML = `<h1>${esc(title)}</h1>`
  feed(view, q)
}

async function pageWeek() {
  const token = route.token
  view.innerHTML = '<h1>新番時間表</h1>' + loading()
  try {
    const days = await api('App2/weekList')
    if (route.token !== token) return
    const today = (new Date().getDay() + 6) % 7
    let day = today
    const draw = () => {
      view.innerHTML = '<h1>新番時間表</h1>'
      view.appendChild(chips('', WEEK.map((n, i) => [i, i === today ? '今天' : `週${n}`]), day, (v) => { day = Number(v); draw() }))
      const grid = h('<div class="grid" style="margin-top:12px"></div>')
      const d = (days || []).find((x) => x.week === day + 1)
      ;(d?.video || []).map(anime).forEach((a) => grid.appendChild(card({ ...a, remarks: '' }, a.remarks)))
      view.appendChild(grid)
    }
    draw()
  } catch (e) {
    if (route.token === token) view.replaceChildren(errorBox(e.message, render))
  }
}

function pageSearch(q) {
  view.innerHTML = '<h1>搜尋</h1>'
  const box = h('<div class="search-box"><input placeholder="輸入作品名稱後按 Enter" /><button class="primary">搜尋</button></div>')
  const input = box.querySelector('input')
  input.value = q
  const submit = () => {
    const w = input.value.trim()
    if (!w) return
    store.addWord(w)
    go(`#/search?q=${encodeURIComponent(w)}`)
  }
  input.onkeydown = (e) => { if (e.key === 'Enter') submit() }
  box.querySelector('button').onclick = submit
  view.appendChild(box)
  if (q) {
    feed(view, { name: q, orderby: 'hits' })
  } else {
    const words = store.words()
    if (words.length) {
      view.appendChild(h('<div class="muted" style="margin-bottom:8px">搜尋紀錄</div>'))
      view.appendChild(chips('', words.map((w) => [w, w]), null, (w) => go(`#/search?q=${encodeURIComponent(w)}`)))
    }
  }
  setTimeout(() => input.focus(), 0)
}

const LOCAL_PAGES = {
  favorites: { title: '收藏', empty: '還沒有收藏，在作品頁按「收藏」加入' },
  later: { title: '待看清單', empty: '待看清單是空的，在作品頁按「＋ 待看」加入' },
  history: { title: '觀看紀錄', empty: '還沒有觀看紀錄' },
}

function pageLocal(kind) {
  const page = LOCAL_PAGES[kind]
  const head = h(`<h1 style="display:flex;align-items:center">${page.title}</h1>`)
  view.appendChild(head)
  const items = kind === 'favorites' ? store.favorites().map((a) => [a, null])
    : kind === 'later' ? store.later().map((a) => [a, null])
    : store.history().map((x) => [{ ...x.anime, remarks: '' }, `看到第 ${x.episodeName} 集`])
  if (kind === 'history' && items.length) {
    const b = h('<button style="margin-left:auto;font-size:13px">清除全部紀錄</button>')
    b.onclick = () => { if (confirm('確定清除所有觀看紀錄和播放進度？')) { store.clearHistory(); render() } }
    head.appendChild(b)
  }
  if (!items.length) {
    view.appendChild(h(`<div class="center">${page.empty}</div>`))
    return
  }
  const grid = h('<div class="grid"></div>')
  items.forEach(([a, sub]) => grid.appendChild(card(a, sub)))
  view.appendChild(grid)
}

async function pageDetail(id) {
  const token = route.token
  view.innerHTML = loading()
  let d
  try {
    d = await detail(id)
  } catch (e) {
    if (route.token === token) view.replaceChildren(errorBox(e.message, render))
    return
  }
  if (route.token !== token) return
  const a = d.anime
  const last = store.last(id)
  let source = last && last.source < d.sources.length ? last.source : 0

  const info = [a.year, d.area, d.version, d.lang].filter(Boolean).join(' · ')
  const el = h(`<div>
    <div class="detail-head">
      <img class="poster" src="${esc(a.cover)}" alt="">
      <div class="meta">
        <h1>${esc(a.name)}</h1>
        ${d.sub ? `<div class="muted">${esc(d.sub)}</div>` : ''}
        ${info ? `<div class="muted">${esc(info)}</div>` : ''}
        ${a.remarks ? `<div style="color:var(--accent)">${esc(a.remarks)}${d.weekday ? `　每週${esc(d.weekday)}更新` : ''}</div>` : ''}
        <div class="tags"></div>
        ${d.director ? `<div class="muted">導演：${esc(d.director)}</div>` : ''}
        ${d.actors ? `<div class="muted">聲優：${esc(d.actors)}</div>` : ''}
        <div class="blurb">${esc(d.blurb)}</div>
        <div class="actions"></div>
      </div>
    </div>
    <div class="eps-area"></div>
  </div>`)
  const tags = el.querySelector('.tags')
  d.classes.forEach((c) => {
    const t = h(`<span>${esc(c)}</span>`)
    t.onclick = () => go(`#/list?title=${encodeURIComponent(c)}&q=${encodeURIComponent(JSON.stringify({ cls: c, orderby: 'hits' }))}`)
    tags.appendChild(t)
  })

  const actions = el.querySelector('.actions')
  const area = el.querySelector('.eps-area')
  const draw = () => {
    actions.innerHTML = ''
    if (d.sources.length) {
      const resume = last && last.source === source && last.episode < d.sources[source].episodes.length ? last : null
      const play = h(`<button class="primary">▶ ${resume ? `繼續看第 ${esc(resume.episodeName)} 集` : '開始播放'}</button>`)
      play.onclick = () => go(`#/play/${id}/${source}/${resume ? resume.episode : 0}`)
      actions.appendChild(play)
    }
    const fav = h(`<button>${store.isFav(id) ? '♥ 已收藏' : '♡ 收藏'}</button>`)
    fav.onclick = () => { store.toggleFav({ ...a }); draw() }
    actions.appendChild(fav)
    const later = h(`<button>${store.isLater(id) ? '✓ 已在待看' : '＋ 待看'}</button>`)
    later.onclick = () => { store.toggleLater({ ...a }); draw() }
    actions.appendChild(later)

    area.innerHTML = ''
    if (!d.sources.length) {
      area.appendChild(h('<div class="center">這部作品暫時沒有播放來源</div>'))
      return
    }
    area.appendChild(h('<h2>選集</h2>'))
    if (d.sources.length > 1) area.appendChild(chips('線路', d.sources.map((s, i) => [i, s.name]), source, (v) => { source = Number(v); draw() }))
    const eps = h('<div class="episodes"></div>')
    d.sources[source].episodes.forEach((e, i) => {
      const b = h(`<button title="${esc(e.name)}">${esc(e.name)}</button>`)
      if (last && last.source === source && last.episode === i) b.classList.add('on')
      b.onclick = () => go(`#/play/${id}/${source}/${i}`)
      eps.appendChild(b)
    })
    area.appendChild(eps)
  }
  draw()
  view.replaceChildren(el)
}

async function pagePlay(id, source, ep) {
  const token = route.token
  view.innerHTML = loading()
  let d
  try {
    d = await detail(id)
  } catch (e) {
    if (route.token === token) view.replaceChildren(errorBox(e.message, render))
    return
  }
  if (route.token !== token) return
  const src = d.sources[source]
  if (!src || !src.episodes[ep]) { go(`#/detail/${id}`); return }
  const a = d.anime
  const e = src.episodes[ep]
  store.addHistory({ anime: { ...a }, source, episode: ep, episodeName: e.name })

  // 控制列全部疊在影片上（video-wrap 裡），全螢幕放大的是整個 video-wrap，
  // 所以全螢幕時彈幕、控制列、彈幕設定、選集都還在
  const multi = src.episodes.length > 1
  const el = h(`<div class="player-page">
    <div class="player-main">
      <div class="video-wrap">
        <video autoplay disablepictureinpicture></video>
        <canvas class="danmaku"></canvas>
        <div class="toast" style="opacity:0"></div>
        <div class="ctrl">
          <div class="ctrl-top"><button class="back-in">‹</button><span class="ctrl-title">${esc(a.name)}${multi ? `　第 ${esc(e.name)} 集` : ''}</span></div>
          <div class="ctrl-click"></div>
          <div class="panel dm-panel">
            <div class="panel-head"><b>彈幕設定</b><span class="muted dm-count"></span><button class="panel-close">✕</button></div>
            <div class="row"><span class="label">彈幕</span><div class="opts dm-on"></div></div>
            <div class="row"><span class="label">時間微調</span><div class="opts"><button class="dm-minus">−0.5s</button><span class="dm-offset"></span><button class="dm-plus">+0.5s</button><button class="dm-zero">歸零</button></div></div>
            <div class="row"><span class="label">字體大小</span><div class="opts dm-size"></div></div>
            <div class="row"><span class="label">不透明度</span><div class="opts dm-alpha"></div></div>
            <div class="row"><span class="label">顯示範圍</span><div class="opts dm-area"></div></div>
          </div>
          <div class="panel ep-panel">
            <div class="panel-head"><b>選集</b>${d.sources.length > 1 ? `<span class="muted">${esc(src.name)}</span>` : ''}<button class="panel-close">✕</button></div>
            <div class="episodes"></div>
          </div>
          <div class="panel speed-panel">
            <div class="panel-head"><b>播放速度</b><button class="panel-close">✕</button></div>
            <div class="opts speed-opts"></div>
          </div>
          <div class="ctrl-bottom">
            <div class="seek"><div class="seek-buf"></div><div class="seek-play"></div><div class="seek-knob"></div><div class="seek-tip"></div></div>
            <div class="ctrl-row">
              <button class="c-play" title="播放／暫停（空白鍵）">▶</button>
              ${multi ? '<button class="c-prev" title="上一集（P）">⏮</button><button class="c-next" title="下一集（N）">⏭</button>' : ''}
              <span class="c-time">0:00 / 0:00</span>
              <button class="c-mute" title="靜音">🔊</button><input class="c-vol" type="range" min="0" max="1" step="0.05">
              <span class="grow"></span>
              <button class="c-dm" title="彈幕開關（D）">彈</button>
              <button class="c-dmset" title="彈幕設定">彈幕設定</button>
              <button class="c-speed" title="播放速度">1x</button>
              ${multi ? '<button class="c-eps" title="選集">選集</button>' : ''}
              <button class="c-fs" title="全螢幕（F／雙擊影片）">⛶</button>
            </div>
          </div>
        </div>
      </div>
      <div class="player-title">
        <button class="back">‹ 返回</button>
        <h1>${esc(a.name)}${multi ? `　第 ${esc(e.name)} 集` : ''}</h1>
      </div>
      <div class="keys">空白鍵 暫停／播放　← → 快退／快進 10 秒　↑ ↓ 音量　F 全螢幕　N 下一集　P 上一集　D 彈幕開關　[ ] 彈幕時間微調　Esc 關閉面板／離開全螢幕</div>
    </div>
    <div class="player-side"><b>選集</b>${d.sources.length > 1 ? `<span class="muted">（${esc(src.name)}）</span>` : ''}<div class="episodes" style="margin-top:10px"></div></div>
  </div>`)
  const $ = (q) => el.querySelector(q)
  const wrap = $('.video-wrap')
  const video = $('video')
  const toast = $('.toast')
  let toastTimer
  const showToast = (t) => {
    toast.textContent = t
    toast.style.opacity = 1
    clearTimeout(toastTimer)
    toastTimer = setTimeout(() => { toast.style.opacity = 0 }, 1600)
  }

  const goEp = (i) => { if (i >= 0 && i < src.episodes.length) go(`#/play/${id}/${source}/${i}`) }
  $('.back').onclick = () => go(`#/detail/${id}`)
  $('.back-in').onclick = () => { if (document.fullscreenElement) document.exitFullscreen(); go(`#/detail/${id}`) }
  if (multi) {
    const p = $('.c-prev'), n = $('.c-next')
    p.disabled = ep <= 0
    n.disabled = ep >= src.episodes.length - 1
    p.onclick = () => goEp(ep - 1)
    n.onclick = () => goEp(ep + 1)
  }

  // 選集：右邊欄和影片上的面板各一份
  for (const box of el.querySelectorAll('.episodes')) {
    src.episodes.forEach((x, i) => {
      const b = h(`<button title="${esc(x.name)}">${esc(x.name)}</button>`)
      if (i === ep) b.classList.add('on')
      b.onclick = () => goEp(i)
      box.appendChild(b)
    })
  }
  view.replaceChildren(el)
  $('.player-side .episodes .on')?.scrollIntoView({ block: 'center' })

  // ---------- 播放：mp4 直接播，m3u8 用 hls.js（Chromium 不支援 HLS） ----------
  let hls = null
  const start = store.pos(id, source, ep)
  if (!/\.m3u8(\?|$)/i.test(e.url)) {
    video.src = e.url
  } else if (window.Hls && Hls.isSupported()) {
    hls = new Hls({ maxBufferLength: 60 })
    hls.loadSource(e.url)
    hls.attachMedia(video)
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (!data.fatal) return
      if (data.type === Hls.ErrorTypes.NETWORK_ERROR) { showToast('網路錯誤，重新連線中…'); hls.startLoad() }
      else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError()
      else showToast('播放失敗')
    })
  } else {
    video.src = e.url
  }
  const savedSpeed = Number(localStorage.getItem('speed')) || 1
  video.volume = Math.min(1, Math.max(0, Number(localStorage.getItem('volume') ?? 1)))
  video.addEventListener('loadedmetadata', () => {
    video.playbackRate = savedSpeed
    if (start > 0 && start < video.duration - 5) {
      video.currentTime = start
      showToast(`從 ${fmt(start)} 繼續播放`)
    }
  }, { once: true })

  // ---------- 控制列 ----------
  const togglePlay = () => { video.paused || video.ended ? video.play() : video.pause() }
  const seekBy = (sec) => {
    if (!video.duration) return
    video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + sec))
    showToast(sec > 0 ? `快進 ${sec} 秒` : `快退 ${-sec} 秒`)
  }
  const setVolume = (v) => {
    video.volume = Math.max(0, Math.min(1, v))
    video.muted = false
    localStorage.setItem('volume', String(video.volume))
  }

  const playBtn = $('.c-play')
  const timeEl = $('.c-time')
  const seek = $('.seek')
  const vol = $('.c-vol')
  const muteBtn = $('.c-mute')
  const drawState = () => {
    playBtn.textContent = video.paused || video.ended ? '▶' : '❚❚'
    const dur = video.duration || 0
    const t = video.currentTime || 0
    timeEl.textContent = `${fmt(t)} / ${dur ? fmt(dur) : '--:--'}`
    const pct = dur ? (t / dur) * 100 : 0
    $('.seek-play').style.width = pct + '%'
    $('.seek-knob').style.left = pct + '%'
    let buf = 0
    for (let i = 0; i < video.buffered.length; i++) if (video.buffered.start(i) <= t + 1) buf = Math.max(buf, video.buffered.end(i))
    $('.seek-buf').style.width = (dur ? (buf / dur) * 100 : 0) + '%'
    vol.value = video.muted ? 0 : video.volume
    muteBtn.textContent = video.muted || video.volume === 0 ? '🔇' : '🔊'
  }
  ;['timeupdate', 'play', 'pause', 'progress', 'durationchange', 'volumechange', 'ended'].forEach((ev) => video.addEventListener(ev, drawState))

  playBtn.onclick = togglePlay
  vol.oninput = () => setVolume(Number(vol.value))
  muteBtn.onclick = () => { video.muted = !video.muted }

  // 進度條：點擊或拖曳跳轉，滑過顯示時間
  const seekRatio = (ev) => {
    const r = seek.getBoundingClientRect()
    return Math.max(0, Math.min(1, (ev.clientX - r.left) / r.width))
  }
  seek.addEventListener('pointerdown', (ev) => {
    if (!video.duration) return
    seek.setPointerCapture(ev.pointerId)
    video.currentTime = seekRatio(ev) * video.duration
    const move = (m) => { video.currentTime = seekRatio(m) * video.duration }
    const up = () => { seek.removeEventListener('pointermove', move); seek.removeEventListener('pointerup', up) }
    seek.addEventListener('pointermove', move)
    seek.addEventListener('pointerup', up)
  })
  seek.addEventListener('pointermove', (ev) => {
    if (!video.duration) return
    const tip = $('.seek-tip')
    tip.textContent = fmt(seekRatio(ev) * video.duration)
    tip.style.left = seekRatio(ev) * 100 + '%'
  })

  // 面板：彈幕設定、選集、倍速，同時只開一個
  const panels = { dm: $('.dm-panel'), ep: $('.ep-panel'), speed: $('.speed-panel') }
  const openPanel = (name) => {
    for (const [k, p] of Object.entries(panels)) p.classList.toggle('open', k === name && !p.classList.contains('open'))
    if (name === 'ep') panels.ep.querySelector('.on')?.scrollIntoView({ block: 'center' })
    showCtrl()
  }
  const anyPanel = () => Object.values(panels).some((p) => p.classList.contains('open'))
  el.querySelectorAll('.panel-close').forEach((b) => { b.onclick = () => openPanel(null) })
  $('.c-dmset').onclick = () => openPanel('dm')
  $('.c-speed').onclick = () => openPanel('speed')
  if (multi) $('.c-eps').onclick = () => openPanel('ep')

  const speedBtn = $('.c-speed')
  const speedOpts = $('.speed-opts')
  const drawSpeed = () => {
    speedBtn.textContent = `${video.playbackRate}x`
    speedOpts.querySelectorAll('button').forEach((b) => b.classList.toggle('on', Number(b.dataset.v) === video.playbackRate))
  }
  ;[0.5, 0.75, 1, 1.25, 1.5, 2].forEach((v) => {
    const b = h(`<button data-v="${v}">${v}x</button>`)
    b.onclick = () => { video.playbackRate = v; localStorage.setItem('speed', String(v)); drawSpeed() }
    speedOpts.appendChild(b)
  })
  video.addEventListener('ratechange', drawSpeed)

  // 控制列自動隱藏：滑鼠不動 3 秒、正在播放、沒開面板時隱藏（滑鼠游標也一起藏）
  let hideTimer
  let overBar = false
  const showCtrl = () => {
    wrap.classList.add('show')
    clearTimeout(hideTimer)
    hideTimer = setTimeout(() => {
      if (!video.paused && !anyPanel() && !overBar) wrap.classList.remove('show')
    }, 3000)
  }
  wrap.addEventListener('mousemove', showCtrl)
  $('.ctrl-bottom').addEventListener('mouseenter', () => { overBar = true })
  $('.ctrl-bottom').addEventListener('mouseleave', () => { overBar = false; showCtrl() })
  wrap.addEventListener('mouseleave', () => { if (!video.paused && !anyPanel()) wrap.classList.remove('show') })
  video.addEventListener('pause', showCtrl)
  video.addEventListener('play', showCtrl)
  showCtrl()

  // 點影片：關掉面板或暫停／播放；雙擊：全螢幕
  const clickArea = $('.ctrl-click')
  let clickTimer = null
  clickArea.addEventListener('click', () => {
    if (anyPanel()) { openPanel(null); return }
    clearTimeout(clickTimer)
    clickTimer = setTimeout(togglePlay, 220) // 等一下確定不是雙擊
  })
  clickArea.addEventListener('dblclick', (ev) => {
    ev.preventDefault()
    clearTimeout(clickTimer)
    toggleFs()
  })

  // 全螢幕放大整個 video-wrap（影片＋彈幕＋控制列）
  const toggleFs = () => {
    if (document.fullscreenElement) document.exitFullscreen()
    else wrap.requestFullscreen().catch(() => {})
  }
  $('.c-fs').onclick = toggleFs
  const onFsChange = () => {
    $('.c-fs').textContent = document.fullscreenElement ? '⛶ 離開' : '⛶'
    showCtrl()
  }
  document.addEventListener('fullscreenchange', onFsChange)

  // ---------- 彈幕 ----------
  const dm = danmakuLayer($('canvas.danmaku'), video)
  const offsetKey = `dm_offset_${id}`
  dm.update('offset', Number(localStorage.getItem(offsetKey)) || 0)
  const fmtOffset = (v) => (v === 0 ? '不調整' : v > 0 ? `延後 ${v.toFixed(1)} 秒` : `提前 ${(-v).toFixed(1)} 秒`)
  const optGroups = [
    ['.dm-size', 'size', [[0.75, '小'], [1, '中'], [1.25, '大'], [1.5, '特大']]],
    ['.dm-alpha', 'alpha', [[1, '100%'], [0.85, '85%'], [0.7, '70%'], [0.5, '50%'], [0.3, '30%']]],
    ['.dm-area', 'area', [[0.25, '1/4'], [0.5, '半屏'], [0.75, '3/4'], [1, '全屏']]],
  ]
  const drawDm = () => {
    $('.c-dm').classList.toggle('on', dm.st.on)
    $('.c-dm').textContent = dm.st.on ? '彈' : '彈̸'
    $('.dm-offset').textContent = fmtOffset(dm.st.offset)
    $('.dm-on').querySelectorAll('button').forEach((b) => b.classList.toggle('on', (b.dataset.v === '1') === dm.st.on))
    for (const [cls, key] of optGroups) {
      $(cls).querySelectorAll('button').forEach((b) => b.classList.toggle('on', Math.abs(Number(b.dataset.v) - dm.st[key]) < 0.01))
    }
  }
  const toggleDm = () => { dm.update('on', !dm.st.on); drawDm(); showToast(dm.st.on ? '彈幕已開啟' : '彈幕已關閉') }
  // 正數＝彈幕延後出現；這部作品的每一集都套用
  const setOffset = (v) => {
    v = Math.round(Math.max(-30, Math.min(30, v)) * 10) / 10
    dm.update('offset', v)
    v ? localStorage.setItem(offsetKey, String(v)) : localStorage.removeItem(offsetKey)
    drawDm()
    showToast(`彈幕${fmtOffset(v)}`)
  }
  ;[['1', '開'], ['0', '關']].forEach(([v, t]) => {
    const b = h(`<button data-v="${v}">${t}</button>`)
    b.onclick = () => { if ((v === '1') !== dm.st.on) toggleDm() }
    $('.dm-on').appendChild(b)
  })
  for (const [cls, key, opts] of optGroups) {
    for (const [v, t] of opts) {
      const b = h(`<button data-v="${v}">${t}</button>`)
      b.onclick = () => { dm.update(key, v); drawDm() }
      $(cls).appendChild(b)
    }
  }
  $('.c-dm').onclick = toggleDm
  $('.dm-minus').onclick = () => setOffset(dm.st.offset - 0.5)
  $('.dm-plus').onclick = () => setOffset(dm.st.offset + 0.5)
  $('.dm-zero').onclick = () => setOffset(0)
  drawDm()
  drawSpeed()
  drawState()
  const dmCount = $('.dm-count')
  dmCount.textContent = '載入中…'
  loadDanmaku(e.url).then((items) => {
    if (route.token !== token) return
    dm.set(items)
    dmCount.textContent = items.length ? `共 ${items.length} 則` : '這集沒有彈幕'
    if (items.length && dm.st.on) showToast(`已載入 ${items.length} 則彈幕`)
  })

  // 進度：每 5 秒存一次，看到最後 30 秒就當作看完
  const save = () => {
    if (!video.duration) return
    const done = video.duration - video.currentTime < 30
    store.savePos(id, source, ep, done ? 0 : video.currentTime)
  }
  const timer = setInterval(save, 5000)
  video.addEventListener('ended', () => {
    store.savePos(id, source, ep, 0)
    if (ep < src.episodes.length - 1) { showToast('自動播放下一集'); setTimeout(() => goEp(ep + 1), 1200) }
  })

  const onKey = (ev) => {
    if (ev.target.tagName === 'INPUT' && ev.target.type !== 'range') return
    if (ev.target.tagName === 'SELECT') return
    switch (ev.key) {
      case ' ': case 'k': togglePlay(); break
      case 'ArrowLeft': seekBy(-10); break
      case 'ArrowRight': seekBy(10); break
      case 'ArrowUp': setVolume(video.volume + 0.1); showToast(`音量 ${Math.round(video.volume * 100)}%`); break
      case 'ArrowDown': setVolume(video.volume - 0.1); showToast(`音量 ${Math.round(video.volume * 100)}%`); break
      case 'f': case 'F': toggleFs(); break
      case 'n': case 'N': goEp(ep + 1); break
      case 'p': case 'P': goEp(ep - 1); break
      case 'd': case 'D': toggleDm(); break
      case '[': setOffset(dm.st.offset - 0.5); break
      case ']': setOffset(dm.st.offset + 0.5); break
      case 'Escape': if (anyPanel()) openPanel(null); else return; break
      default: return
    }
    ev.preventDefault()
    showCtrl()
  }
  document.addEventListener('keydown', onKey)

  route.cleanup = () => {
    save()
    clearInterval(timer)
    clearTimeout(hideTimer)
    document.removeEventListener('keydown', onKey)
    document.removeEventListener('fullscreenchange', onFsChange)
    if (document.fullscreenElement) document.exitFullscreen()
    dm.destroy()
    if (hls) hls.destroy()
    video.removeAttribute('src')
    video.load()
  }
}

function fmt(sec) {
  sec = Math.floor(sec)
  const hh = Math.floor(sec / 3600), mm = Math.floor(sec / 60) % 60, ss = sec % 60
  return hh ? `${hh}:${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}` : `${mm}:${String(ss).padStart(2, '0')}`
}

// ================= 更新 =================

// version.js 由 CI 寫入（APP_VERSION = run_number），本機開發是 0，不檢查
async function checkUpdate() {
  const current = Number(window.APP_VERSION) || 0
  if (!current) return
  try {
    const res = await fetch('https://api.github.com/repos/DLIN131/AniPlay/releases/latest', {
      headers: { Accept: 'application/vnd.github+json' },
    })
    if (!res.ok) return
    const r = await res.json()
    const code = Number(String(r.tag_name || '').split('.').pop()) || 0
    const asset = (r.assets || []).find((a) => a.name === 'AniPlay-Windows.zip')
    if (code <= current || !asset || Number(localStorage.getItem('skipped_version')) === code) return
    // 發布說明裡 --- 以下是固定的下載資訊，Co-Authored-By 也不用給使用者看
    const notes = String(r.body || '').split('\n---')[0].split('\n').filter((l) => !l.startsWith('Co-Authored-By')).join('\n').trim()
    const bar = h(`<div class="update-bar">
      <div><b>發現新版本 v1.0.${code}</b>（目前 v1.0.${current}）<div class="notes"></div><div class="status"></div></div>
      <button class="primary">立即更新</button><button class="skip">略過這個版本</button><button class="later">稍後</button>
    </div>`)
    bar.querySelector('.notes').textContent = notes
    const status = bar.querySelector('.status')
    const primary = bar.querySelector('.primary')
    const inApp = window.updater && (await window.updater.supported().catch(() => false))
    let busy = false
    primary.onclick = async () => {
      // 不是打包版（例如開發時）就退回用瀏覽器下載
      if (!inApp) { window.open(asset.browser_download_url); bar.remove(); return }
      if (busy) return
      busy = true
      primary.textContent = '下載中…'
      bar.querySelectorAll('.skip, .later').forEach((b) => { b.disabled = true })
      try {
        await window.updater.download(asset.browser_download_url, (p) => {
          status.textContent = p >= 0 ? `下載中 ${p}%` : '下載中…'
          if (p >= 100) status.textContent = '解壓縮中…'
        })
        status.textContent = '下載完成，程式會關閉並自動重新開啟'
        primary.textContent = '安裝中…'
        await window.updater.install()
      } catch (err) {
        busy = false
        primary.textContent = '重試'
        bar.querySelectorAll('.skip, .later').forEach((b) => { b.disabled = false })
        status.textContent = `更新失敗：${String(err.message || err).replace(/^Error invoking remote method '[^']+': (Error: )?/, '')}`
      }
    }
    bar.querySelector('.skip').onclick = () => { localStorage.setItem('skipped_version', String(code)); bar.remove() }
    bar.querySelector('.later').onclick = () => bar.remove()
    document.body.appendChild(bar)
  } catch {}
}
checkUpdate()

window.addEventListener('beforeunload', () => route.cleanup && route.cleanup())
if (!location.hash) location.hash = '#/home'
else render()
