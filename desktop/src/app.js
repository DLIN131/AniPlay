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

async function list(q, offset, limit = PAGE) {
  const info = await api('Vod/get_list', {
    type_id: q.typeId, offset, limit, orderby: q.orderby || 'pubdate',
    vod_name: q.name, vod_class: q.cls, vod_year: q.year, vod_version: q.version,
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
    case 'favorites': return pageLocal(true)
    case 'history': return pageLocal(false)
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

function pageLocal(favorites) {
  const head = h(`<h1 style="display:flex;align-items:center">${favorites ? '收藏' : '觀看紀錄'}</h1>`)
  view.appendChild(head)
  const items = favorites
    ? store.favorites().map((a) => [a, null])
    : store.history().map((x) => [{ ...x.anime, remarks: '' }, `看到第 ${x.episodeName} 集`])
  if (!favorites && items.length) {
    const b = h('<button style="margin-left:auto;font-size:13px">清除全部紀錄</button>')
    b.onclick = () => { if (confirm('確定清除所有觀看紀錄和播放進度？')) { store.clearHistory(); render() } }
    head.appendChild(b)
  }
  if (!items.length) {
    view.appendChild(h(`<div class="center">${favorites ? '還沒有收藏，在作品頁按「收藏」加入' : '還沒有觀看紀錄'}</div>`))
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

  const el = h(`<div class="player-page">
    <div class="player-main">
      <div class="video-wrap"><video controls autoplay></video><div class="toast" style="opacity:0"></div></div>
      <div class="player-title">
        <button class="back">‹ 返回</button>
        <h1>${esc(a.name)}${src.episodes.length > 1 ? `　第 ${esc(e.name)} 集` : ''}</h1>
        <button class="prev">⏮ 上一集</button>
        <button class="next">下一集 ⏭</button>
        <select class="speed"></select>
      </div>
      <div class="keys">空白鍵 暫停／播放　← → 快退／快進 10 秒　↑ ↓ 音量　F 全螢幕　N 下一集　P 上一集</div>
    </div>
    <div class="player-side"><b>選集</b>${d.sources.length > 1 ? `<span class="muted">（${esc(src.name)}）</span>` : ''}<div class="episodes" style="margin-top:10px"></div></div>
  </div>`)
  const video = el.querySelector('video')
  const toast = el.querySelector('.toast')
  let toastTimer
  const showToast = (t) => {
    toast.textContent = t
    toast.style.opacity = 1
    clearTimeout(toastTimer)
    toastTimer = setTimeout(() => { toast.style.opacity = 0 }, 1600)
  }

  el.querySelector('.back').onclick = () => go(`#/detail/${id}`)
  const prev = el.querySelector('.prev')
  const next = el.querySelector('.next')
  prev.disabled = ep <= 0
  next.disabled = ep >= src.episodes.length - 1
  prev.style.opacity = prev.disabled ? 0.35 : 1
  next.style.opacity = next.disabled ? 0.35 : 1
  const goEp = (i) => { if (i >= 0 && i < src.episodes.length) go(`#/play/${id}/${source}/${i}`) }
  prev.onclick = () => goEp(ep - 1)
  next.onclick = () => goEp(ep + 1)

  const speed = el.querySelector('.speed')
  speed.style.cssText = 'background:rgba(255,255,255,.08);color:#fff;border:none;border-radius:18px;padding:7px 10px;font:inherit'
  const savedSpeed = Number(localStorage.getItem('speed')) || 1
  ;[0.5, 0.75, 1, 1.25, 1.5, 2].forEach((s) => {
    const o = h(`<option value="${s}" style="background:#1c1c24">${s}x</option>`)
    if (s === savedSpeed) o.selected = true
    speed.appendChild(o)
  })
  speed.onchange = () => { video.playbackRate = Number(speed.value); localStorage.setItem('speed', speed.value) }

  const list = el.querySelector('.player-side .episodes')
  src.episodes.forEach((x, i) => {
    const b = h(`<button title="${esc(x.name)}">${esc(x.name)}</button>`)
    if (i === ep) b.classList.add('on')
    b.onclick = () => goEp(i)
    list.appendChild(b)
  })
  view.replaceChildren(el)
  list.querySelector('.on')?.scrollIntoView({ block: 'center' })

  // 播放：Chromium 不支援 HLS，用 hls.js
  let hls = null
  const start = store.pos(id, source, ep)
  if (window.Hls && Hls.isSupported()) {
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
  video.addEventListener('loadedmetadata', () => {
    video.playbackRate = savedSpeed
    if (start > 0 && start < video.duration - 5) {
      video.currentTime = start
      showToast(`從 ${fmt(start)} 繼續播放`)
    }
  }, { once: true })

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
    if (ev.target.tagName === 'INPUT' || ev.target.tagName === 'SELECT') return
    switch (ev.key) {
      case ' ': case 'k': video.paused ? video.play() : video.pause(); break
      case 'ArrowLeft': video.currentTime = Math.max(0, video.currentTime - 10); showToast('快退 10 秒'); break
      case 'ArrowRight': video.currentTime = Math.min(video.duration || 0, video.currentTime + 10); showToast('快進 10 秒'); break
      case 'ArrowUp': video.volume = Math.min(1, video.volume + 0.1); showToast(`音量 ${Math.round(video.volume * 100)}%`); break
      case 'ArrowDown': video.volume = Math.max(0, video.volume - 0.1); showToast(`音量 ${Math.round(video.volume * 100)}%`); break
      case 'f': case 'F': document.fullscreenElement ? document.exitFullscreen() : el.querySelector('.video-wrap').requestFullscreen(); break
      case 'n': case 'N': goEp(ep + 1); break
      case 'p': case 'P': goEp(ep - 1); break
      default: return
    }
    ev.preventDefault()
  }
  document.addEventListener('keydown', onKey)
  // 雙擊影片切換全螢幕
  video.addEventListener('dblclick', (ev) => {
    ev.preventDefault()
    document.fullscreenElement ? document.exitFullscreen() : el.querySelector('.video-wrap').requestFullscreen()
  })

  route.cleanup = () => {
    save()
    clearInterval(timer)
    document.removeEventListener('keydown', onKey)
    if (document.fullscreenElement) document.exitFullscreen()
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
      <div><b>發現新版本 v1.0.${code}</b>（目前 v1.0.${current}）<div class="notes"></div></div>
      <button class="primary">下載更新</button><button class="skip">略過這個版本</button><button class="later">稍後</button>
    </div>`)
    bar.querySelector('.notes').textContent = notes
    bar.querySelector('.primary').onclick = () => { window.open(asset.browser_download_url); bar.remove() }
    bar.querySelector('.skip').onclick = () => { localStorage.setItem('skipped_version', String(code)); bar.remove() }
    bar.querySelector('.later').onclick = () => bar.remove()
    document.body.appendChild(bar)
  } catch {}
}
checkUpdate()

window.addEventListener('beforeunload', () => route.cleanup && route.cleanup())
if (!location.hash) location.hash = '#/home'
else render()
