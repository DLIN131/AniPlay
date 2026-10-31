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
// 網站有些文字是 HTML 跳脫過的（例如標題裡的 &amp;），還原成一般字元
const unescapeHtml = (s) => (s.includes('&') ? s.replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
  .replace(/&quot;/g, '"')
  .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
  .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
  .replace(/&amp;/g, '&') : s)
const str = (v) => unescapeHtml((v == null ? '' : String(v)).trim())
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
    vod_name: toSimplified(q.name), vod_class: q.cls, vod_year: q.year, vod_area: q.month, vod_version: q.version,
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
    sub: str(o.vod_sub),
    blurb: stripHtml(o.vod_content) || o.vod_blurb || '',
    classes: (o.vod_class || '').split(/[,，/]/).map((s) => s.trim()).filter(Boolean),
    area: o.vod_area || '', lang: o.vod_lang || '', version: o.vod_version || '',
    director: str(o.vod_director), actors: str(o.vod_actor), weekday: str(o.vod_weekday),
    sources: parseSources(o.vod_play_from, o.vod_play_url),
    // 最後更新、上架時間（毫秒），用來判斷本週有沒有停更
    updatedAt: (Number(o.vod_time) || 0) * 1000,
    addedAt: (Number(o.vod_time_add) || 0) * 1000,
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
const SPRITES_PER_FRAME = 12

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
  let active = [], lanes = {}, next = 0, lastPos = NaN, raf = 0, W = 0, H = 0, dpr = 1

  const reset = () => { active = []; lanes = { scroll: [], top: [], bottom: [] }; lastPos = NaN }

  // 平滑時鐘：video.currentTime 是一格一格跳的（不是每一幀都更新），直接拿來算位置彈幕會一頓一頓。
  // 播放中用系統時間往前推算，再慢慢修正到影片時間；差太多（拖動、卡住緩衝）就直接對齊。
  let clock = { media: 0, at: 0 }
  function mediaTime(t) {
    const vt = video.currentTime
    const stalled = video.paused || video.seeking || (video.readyState !== undefined && video.readyState < 3)
    if (stalled || !clock.at) { clock = { media: vt, at: t }; return vt }
    const predicted = clock.media + ((t - clock.at) / 1000) * (video.playbackRate || 1)
    if (Math.abs(predicted - vt) > 0.25) { clock = { media: vt, at: t }; return vt }
    clock = { media: predicted + (vt - predicted) * 0.05, at: t }
    return clock.media
  }

  // 每則彈幕出現時先畫好一張小圖（描邊＋填色只畫一次），之後每一幀只貼圖。
  // 以前每一幀都重畫全部文字的描邊，彈幕一多就超過一幀的時間（實測最密集時平均 23 ms、最慢 138 ms）
  function sprite(d, w, fs) {
    const pad = Math.ceil(Math.max(1.5, fs / 12)) + 1
    const cw = Math.ceil(w) + pad * 2, ch = Math.ceil(fs * 1.25) + pad * 2
    // 用 OffscreenCanvas 畫完轉成 ImageBitmap：不可變、可以留在 GPU 上，
    // 貼圖時不用每幀重新上傳（一般的小 canvas 放在 CPU，每次貼都要上傳一次，反而更慢）
    const c = new OffscreenCanvas(Math.ceil(cw * dpr), Math.ceil(ch * dpr))
    const g = c.getContext('2d')
    g.scale(dpr, dpr)
    g.font = ctx.font
    g.textBaseline = 'alphabetic'
    g.lineJoin = 'round'
    g.lineWidth = Math.max(1.5, fs / 12)
    g.strokeStyle = 'rgba(0,0,0,0.8)'
    g.fillStyle = d.color
    const baseY = pad + fs
    g.strokeText(d.text, pad, baseY)
    g.fillText(d.text, pad, baseY)
    return { img: c.transferToImageBitmap(), cw, ch, pad, baseY }
  }
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
    const add = (lane, list) => {
      const a = { d, w, lane, s: null } // 小圖在畫的時候才產生（每幀有上限）
      list[lane] = a
      active.push(a)
    }
    if (d.type === 'scroll') {
      const count = Math.max(1, Math.floor((H * st.area) / line))
      for (let i = 0; i < count; i++) {
        const last = lanes.scroll[i]
        // 同軌道上一則的尾巴已經離開右邊，而且新的在上一則離開畫面前追不上它
        const ok = !last || (
          W - (d.time - last.d.time) * speed(last.w) + last.w + 16 <= W &&
          W - (last.d.time + SCROLL_SEC - d.time) * speed(w) >= 0
        )
        if (ok) { add(i, lanes.scroll); return }
      }
    } else {
      const list = lanes[d.type]
      const count = Math.max(1, Math.floor((H * 0.3) / line))
      for (let i = 0; i < count; i++) {
        const last = list[i]
        if (!last || d.time - last.d.time >= FIXED_SEC) { add(i, list); return }
      }
    }
    // 軌道都滿了就不顯示，避免疊在一起
  }

  function frame(t) {
    raf = requestAnimationFrame(frame)
    const ratio = window.devicePixelRatio || 1
    const cw = canvas.clientWidth, ch = canvas.clientHeight
    if (cw !== W || ch !== H || ratio !== dpr) {
      W = cw; H = ch; dpr = ratio
      canvas.width = Math.round(cw * dpr); canvas.height = Math.round(ch * dpr)
      reset()
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, W, H)
    if (!st.on || !st.items.length || !W) return

    const pos = mediaTime(t ?? performance.now()) - st.offset
    // 往回拖或往前跳超過 1 秒：重新排，「現在應該在畫面上」的彈幕也補上
    if (Number.isNaN(lastPos) || pos < lastPos - 0.05 || pos - lastPos > 1) {
      reset()
      next = lowerBound(pos - SCROLL_SEC)
    }
    lastPos = pos

    const fs = fontSize(), line = lineH()
    ctx.font = `bold ${fs}px "Microsoft JhengHei", "PingFang TC", sans-serif`
    ctx.globalAlpha = st.alpha

    while (next < st.items.length && st.items[next].time <= pos) {
      const d = st.items[next++]
      const age = pos - d.time
      if (age < (d.type === 'scroll' ? SCROLL_SEC : FIXED_SEC)) spawn(d)
    }

    // 原地移除過期的，不每幀建新陣列
    // 每幀最多新畫 12 張小圖：開始播放、拖動進度時畫面上一下子要補幾十則，分散到後面幾幀才不會卡一下
    let budget = SPRITES_PER_FRAME
    let k = 0
    for (let i = 0; i < active.length; i++) {
      const a = active[i]
      const age = pos - a.d.time
      let x, y
      if (a.d.type === 'scroll') {
        if (age > SCROLL_SEC) continue
        x = W - age * speed(a.w); y = line * (a.lane + 1) - line * 0.22
      } else {
        if (age > FIXED_SEC) continue
        x = (W - a.w) / 2
        y = a.d.type === 'top' ? line * (a.lane + 1) - line * 0.22 : H - line * a.lane - line * 0.35
      }
      active[k++] = a
      if (!a.s) {
        if (budget <= 0) continue
        budget--
        a.s = sprite(a.d, a.w, fs)
      }
      // 對齊到實際像素，貼圖不用重新取樣、文字也比較銳利
      const s = a.s
      const dx = Math.round((x - s.pad) * dpr) / dpr
      const dy = Math.round((y - s.baseY) * dpr) / dpr
      ctx.drawImage(s.img, dx, dy, s.cw, s.ch)
    }
    active.length = k
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
  // 播放進度存成「秒數/片長」，例如 "754/1420"（舊版只有秒數，一樣讀得到）
  pos(id, s, e) { return this.posInfo(id, s, e).sec },
  posInfo(id, s, e) {
    const [sec, dur] = String(localStorage.getItem(`pos_${id}_${s}_${e}`) || '').split('/').map(Number)
    return { sec: sec || 0, dur: dur || 0 }
  },
  savePos(id, s, e, sec, dur = 0) {
    const k = `pos_${id}_${s}_${e}`
    const v = dur > 0 ? `${Math.floor(sec)}/${Math.floor(dur)}` : String(Math.floor(sec))
    try { sec > 0 ? localStorage.setItem(k, v) : localStorage.removeItem(k) } catch {}
  },
  words() { return this.get('search_words', []) },
  addWord(w) { this.set('search_words', [w, ...this.words().filter((x) => x !== w)].slice(0, 20)) },
  /** 觀看紀錄最多 300 部，被擠出紀錄的作品，播放進度也一起刪掉，不要越積越多 */
  prunePositions() {
    const ids = new Set(this.history().map((h) => String(h.anime.id)))
    try {
      Object.keys(localStorage)
        .filter((k) => k.startsWith('pos_') && !ids.has(k.split('_')[1]))
        .forEach((k) => localStorage.removeItem(k))
    } catch {}
  },
}
store.prunePositions()

// ================= 畫面工具 =================

const view = document.getElementById('view')
// 捲動的是外層 main（裡面有返回列和 view）
const scroller = document.getElementById('scroller')
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])
const h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild }
const loading = () => '<div class="center"><div class="spinner"></div></div>'

function errorBox(msg, retry) {
  const el = h(`<div class="center"><div>${esc(msg)}</div><button>重試</button></div>`)
  el.querySelector('button').onclick = retry
  return el
}

/**
 * 作品卡片。opts：
 * - href：點了去哪（預設作品頁）
 * - progress：0～1，封面底部顯示觀看進度條
 * - status：{ cls, text }，封面左上角的狀態標籤（本週停更等）
 */
function card(a, sub, opts = {}) {
  const score = a.score && a.score !== '0.0' ? `<span class="score">${esc(a.score)}</span>` : ''
  const badge = a.remarks ? `<div class="badge">${esc(a.remarks)}</div>` : ''
  const progress = opts.progress > 0 ? `<div class="progress"><i style="width:${Math.min(100, opts.progress * 100).toFixed(1)}%"></i></div>` : ''
  const status = opts.status ? `<span class="status ${opts.status.cls}">${esc(opts.status.text)}</span>` : ''
  const el = h(`<div class="card${opts.status?.cls === 'st-paused' ? ' paused' : ''}">
    <div class="cover"><img loading="lazy" src="${esc(a.cover)}" alt="">${badge}${progress}${status}${score}</div>
    <div class="name">${esc(a.name)}</div>
    ${sub ? `<div class="sub">${esc(sub)}</div>` : ''}
  </div>`)
  el.onclick = () => go(opts.href || `#/detail/${a.id}`)
  return el
}

/** 觀看紀錄的一筆 → 「第 03 集 12:34 / 23:40」和進度（0～1） */
function watchInfo(x) {
  const p = store.posInfo(x.anime.id, x.source, x.episode)
  let text = `第 ${x.episodeName} 集`
  if (p.sec > 0) text += ` ${fmt(p.sec)}${p.dur ? ` / ${fmt(p.dur)}` : ''}`
  return { text, progress: p.sec > 0 && p.dur > 0 ? p.sec / p.dur : 0 }
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
      if (!done && scroller.scrollHeight <= scroller.clientHeight + 300) more()
    } catch (e) {
      foot.replaceChildren(errorBox(e.message, () => { busy = false; more() }))
    } finally {
      busy = false
    }
  }
  route.onScroll = () => { if (scroller.scrollTop + scroller.clientHeight > scroller.scrollHeight - 600) more() }
  more()
}

// ================= 路由 =================

const route = { token: null, onScroll: null, cleanup: null }
scroller.addEventListener('scroll', () => route.onScroll && route.onScroll())

function go(hash) { location.hash = hash }

// ---------- 返回 ----------
// 自己記走過的頁面：hash 路由的 history.back() 本身可以用，但要知道「有沒有上一頁」才能決定顯示返回列
const navStack = []
let replacing = false

/** 換掉目前這一頁、不留在返回紀錄裡（例如播放器裡換集） */
function goReplace(hash) {
  replacing = true
  location.replace(hash)
}

function trackNav() {
  const cur = location.hash || '#/home'
  if (replacing) {
    replacing = false
    if (navStack.length) navStack[navStack.length - 1] = cur
    else navStack.push(cur)
  } else if (navStack.length >= 2 && navStack[navStack.length - 2] === cur) {
    navStack.pop() // 按了返回（或瀏覽器的上一頁）
  } else if (navStack[navStack.length - 1] !== cur) {
    navStack.push(cur)
  }
}

const canGoBack = () => navStack.length > 1

/** 返回上一頁；沒有上一頁就去 fallback */
function goBack(fallback = '#/home') {
  if (canGoBack()) history.back()
  else if (location.hash !== fallback) goReplace(fallback)
}

const backbar = document.getElementById('backbar')
backbar.querySelector('.back-btn').onclick = () => goBack()

// 滑鼠側鍵（上一頁／下一頁）、Alt+←、Backspace
window.addEventListener('mouseup', (e) => {
  if (e.button === 3) { e.preventDefault(); goBack() }
  else if (e.button === 4) { e.preventDefault(); history.forward() }
})
window.addEventListener('keydown', (e) => {
  const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName) && e.target.type !== 'range'
  if ((e.altKey && e.key === 'ArrowLeft') || (e.key === 'Backspace' && !typing)) {
    if (document.fullscreenElement) return
    e.preventDefault()
    goBack()
  }
})

function render() {
  if (route.cleanup) { route.cleanup(); route.cleanup = null }
  route.onScroll = null
  route.token = {}
  trackNav()
  scroller.scrollTop = 0
  const [path, qs] = location.hash.slice(1).split('?')
  const parts = path.split('/').filter(Boolean)
  const params = new URLSearchParams(qs || '')
  const tab = parts[0] || 'home'
  // 播放器有自己的返回按鈕
  backbar.classList.toggle('show', canGoBack() && tab !== 'play')
  document.querySelectorAll('.sidebar a[data-tab]').forEach((a) => a.classList.toggle('active', a.dataset.tab === tab))
  view.innerHTML = ''
  switch (tab) {
    case 'browse': return pageBrowse()
    case 'week': return pageWeek()
    case 'search': return pageSearch(params.get('q') || '')
    case 'favorites': return pageLocal('favorites')
    case 'later': return pageLocal('later')
    case 'history': return pageLocal('history')
    case 'changelog': return pageChangelog()
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
      // 點了直接從上次的位置繼續播
      hist.forEach((x) => {
        const w = watchInfo(x)
        row.appendChild(card({ ...x.anime, remarks: '' }, w.text, { progress: w.progress, href: `#/play/${x.anime.id}/${x.source}/${x.episode}` }))
      })
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
const browseState = { typeId: 2, orderby: 'pubdate', cls: '', year: '', month: '', version: '' }
// 網站以季度分新番（存在 vod_area 欄位）
const MONTHS = ['一月', '四月', '七月', '十月']

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
    view.appendChild(chips('月份', [['', '全部'], ...MONTHS.map((m) => [m, m])], browseState.month, set('month')))
    view.appendChild(h('<div style="height:10px"></div>'))
    feed(view, { ...browseState })
  }
  draw()
}

function pageList(title, q) {
  view.innerHTML = `<h1>${esc(title)}</h1>`
  feed(view, q)
}

// ---------- 本週停更 ----------
// 網站時間表沒有「停更」欄位，用作品的「最後更新時間」推算：
// 這週的播出日（時間表上那一天）過了 36 小時還沒更新，就算本週停更。
const HOUR = 3600 * 1000
const DAY = 24 * HOUR
const PAUSE_AFTER = 36 * HOUR
const weekStatus = new Map() // vod_id → { cls, text }
let weekStatusAt = 0

/** 最近一次（今天或之前）的週 w 的 00:00；w：1＝週一 … 7＝週日 */
function lastSlot(w, now = new Date()) {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const todayIdx = (now.getDay() + 6) % 7
  return today - ((todayIdx - (w - 1) + 7) % 7) * DAY
}

function statusOf(a, week, d) {
  if (/完结|完結/.test(a.remarks)) return { cls: 'st-end', text: '已完結' }
  if (/起\s*$/.test(a.remarks)) return { cls: 'st-upcoming', text: '尚未開播' }
  // 只有每週連載（「更新至03集 / 23點30分」）才判斷停更；「10月25日／星期日」這類單次播出的特別篇不算
  if (!/更新至/.test(a.remarks)) return null
  if (!d || !d.updatedAt) return null
  const slot = lastSlot(week)
  // 時間表上的時間常是深夜（例如 00:30 算前一天），更新也可能早半天，往前多算 12 小時
  if (d.updatedAt >= slot - 12 * HOUR || d.addedAt >= slot - 12 * HOUR) return { cls: 'st-updated', text: '本週已更新' }
  if (Date.now() - slot < PAUSE_AFTER) return { cls: 'st-waiting', text: '等待更新' }
  return { cls: 'st-paused', text: '本週停更' }
}

/** 同時最多 n 個請求 */
async function pool(items, n, fn) {
  let i = 0
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) { const x = items[i++]; await fn(x).catch(() => {}) }
  }))
}

async function pageWeek() {
  const token = route.token
  view.innerHTML = '<h1>本季新番時間表</h1>' + loading()
  try {
    const days = await api('App2/weekList')
    if (route.token !== token) return
    const today = (new Date().getDay() + 6) % 7
    let day = today
    let checking = false
    let checked = 0
    const shows = (days || []).flatMap((d) => (d.video || []).map((v) => ({ week: d.week, a: anime(v) })))
    const ongoing = shows.filter((x) => /更新至/.test(x.a.remarks))
    const ids = [...new Set(ongoing.map((x) => x.a.id))]

    const draw = () => {
      if (route.token !== token) return
      const y = scroller.scrollTop
      view.innerHTML = '<h1>本季新番時間表</h1>'

      // 上面列出本週停更的作品，點名字直接跳到作品頁
      const paused = shows.filter((x) => weekStatus.get(x.a.id)?.cls === 'st-paused')
      const uniq = [...new Map(paused.map((x) => [x.a.id, x])).values()]
      const sum = h('<div class="week-summary"></div>')
      if (checking) sum.appendChild(h(`<span>正在檢查本週更新狀態… ${checked} / ${ids.length}</span>`))
      else if (uniq.length) {
        sum.appendChild(h(`<span>本週停更 <span class="paused-count">${uniq.length}</span> 部：</span>`))
        uniq.forEach((x) => {
          const n = h(`<span class="paused-name" title="週${WEEK[x.week - 1]}">${esc(x.a.name)}</span>`)
          n.onclick = () => go(`#/detail/${x.a.id}`)
          sum.appendChild(n)
        })
      } else sum.appendChild(h('<span>本週沒有停更的作品</span>'))
      view.appendChild(sum)

      // 星期選單：有停更的那天標出數量
      const pausedByDay = (i) => paused.filter((x) => x.week === i + 1).length
      view.appendChild(chips('', WEEK.map((n, i) => {
        const p = pausedByDay(i)
        return [i, `${i === today ? '今天' : `週${n}`}${p ? `（${p} 停更）` : ''}`]
      }), day, (v) => { day = Number(v); scroller.scrollTop = 0; draw() }))

      const grid = h('<div class="grid" style="margin-top:12px"></div>')
      const d = (days || []).find((x) => x.week === day + 1)
      ;(d?.video || []).map(anime).forEach((a) => {
        const st = weekStatus.get(a.id) || statusOf(a, day + 1, null)
        grid.appendChild(card({ ...a, remarks: '' }, a.remarks, { status: st }))
      })
      view.appendChild(grid)
      scroller.scrollTop = y
    }

    // 10 分鐘內查過就直接用，不然重新查每部作品的最後更新時間
    if (Date.now() - weekStatusAt > 10 * 60 * 1000) {
      weekStatus.clear()
      checking = true
      draw()
      let lastDraw = 0
      await pool(ids, 6, async (id) => {
        const dt = await detail(id)
        for (const x of shows) if (x.a.id === id) {
          const st = statusOf(x.a, x.week, dt)
          // 同一部在兩天都有播：任一天停更就算停更
          if (st && weekStatus.get(id)?.cls !== 'st-paused') weekStatus.set(id, st)
        }
        checked++
        if (Date.now() - lastDraw > 400) { lastDraw = Date.now(); draw() }
      })
      for (const x of shows) if (!weekStatus.has(x.a.id)) { const st = statusOf(x.a, x.week, null); if (st) weekStatus.set(x.a.id, st) }
      weekStatusAt = Date.now()
      checking = false
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
    : store.history().map((x) => {
      const w = watchInfo(x)
      return [{ ...x.anime, remarks: '' }, w.text, { progress: w.progress }]
    })
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
  items.forEach(([a, sub, opts]) => grid.appendChild(card(a, sub, opts)))
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

  // 換集用 replace：返回時直接回到進播放器前的那一頁，不會一集一集往回退
  const goEp = (i) => { if (i >= 0 && i < src.episodes.length) goReplace(`#/play/${id}/${source}/${i}`) }
  $('.back').onclick = () => goBack(`#/detail/${id}`)
  $('.back-in').onclick = () => { if (document.fullscreenElement) document.exitFullscreen(); goBack(`#/detail/${id}`) }
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
    // 往前最多預載 60 秒；已經播過的只留 90 秒在記憶體（hls.js 預設是全部留著，長片會越看越吃記憶體）
    hls = new Hls({ maxBufferLength: 60, backBufferLength: 90 })
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
    store.savePos(id, source, ep, done ? 0 : video.currentTime, video.duration)
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

const RELEASES_API = 'https://api.github.com/repos/DLIN131/AniPlay/releases'

/** 發布說明裡 --- 以下是固定的下載資訊，Co-Authored-By 也不用給使用者看 */
const cleanNotes = (body) => String(body || '').split('\n---')[0].split('\n').filter((l) => !l.startsWith('Co-Authored-By')).join('\n').trim()

/** 所有版本的更新內容，新的在前面（就是每個 GitHub Release 的說明） */
async function fetchChangelog() {
  const res = await fetch(`${RELEASES_API}?per_page=50`, { headers: { Accept: 'application/vnd.github+json' } })
  if (!res.ok) throw new Error(`無法取得更新紀錄（HTTP ${res.status}）`)
  return (await res.json()).map((r) => ({
    code: Number(String(r.tag_name || '').split('.').pop()) || 0,
    version: r.tag_name || '',
    date: String(r.published_at || '').slice(0, 10),
    notes: cleanNotes(r.body),
  })).filter((n) => n.code).sort((a, b) => b.code - a.code)
}

async function pageChangelog() {
  const token = route.token
  const current = Number(window.APP_VERSION) || 0
  view.innerHTML = `<h1>更新紀錄</h1><div class="muted" style="margin:-4px 0 14px">目前版本 ${current ? 'v1.0.' + current : '開發版'}</div>` + loading()
  try {
    const list = await fetchChangelog()
    if (route.token !== token) return
    view.querySelector('.center')?.remove()
    for (const n of list) {
      const el = h(`<div class="note-card">
        <div class="note-head"><b>${esc(n.version)}</b>${n.code === current ? '<span class="note-current">目前版本</span>' : ''}<span class="muted">${esc(n.date)}</span></div>
        <div class="note-body"></div>
      </div>`)
      el.querySelector('.note-body').textContent = n.notes || '（這個版本沒有寫更新說明）'
      view.appendChild(el)
    }
  } catch (e) {
    if (route.token === token) view.replaceChildren(errorBox(e.message, render))
  }
}

/** 剛更新完第一次開啟：顯示這次更新了什麼。第一次安裝不顯示 */
async function checkWhatsNew() {
  const current = Number(window.APP_VERSION) || 0
  if (!current) return
  const last = Number(localStorage.getItem('last_seen_version')) || 0
  if (last === current) return
  localStorage.setItem('last_seen_version', String(current))
  if (!last) return
  try {
    const note = (await fetchChangelog()).find((n) => n.code === current)
    if (!note) return
    const dlg = h(`<div class="modal-backdrop"><div class="modal">
      <h2 style="margin:0 0 4px">已更新到 ${esc(note.version)}</h2>
      <div class="muted" style="margin-bottom:12px">這次更新了什麼</div>
      <div class="note-body"></div>
      <div class="modal-actions"><button class="more">所有更新紀錄</button><button class="primary ok">知道了</button></div>
    </div></div>`)
    dlg.querySelector('.note-body').textContent = note.notes || '（這個版本沒有寫更新說明）'
    const close = () => dlg.remove()
    dlg.querySelector('.ok').onclick = close
    dlg.querySelector('.more').onclick = () => { close(); go('#/changelog') }
    dlg.onclick = (e) => { if (e.target === dlg) close() }
    document.body.appendChild(dlg)
  } catch {}
}
checkWhatsNew()

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
    const notes = cleanNotes(r.body)
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
