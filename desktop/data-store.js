// 收藏、待看、觀看紀錄、播放進度和設定，全部存成程式資料夾裡的 data/aniplay-data.json。
// 不用 localStorage：Chromium 的 localStorage（userData 裡的 leveldb）重開程式時
// 會整段丟掉上次寫入的資料，使用者看到的就是「更新後紀錄不見」。
// 更新時 robocopy 只覆蓋新版有的檔案，data 資料夾不會被動到。
const { app, ipcMain, dialog, shell, BrowserWindow } = require('electron')
const fs = require('original-fs')
const path = require('path')

const FILE_NAME = 'aniplay-data.json'
const KEEP_BACKUPS = 20

let dir = null
let current = {}

/** 打包版：exe 旁邊的 data；開發時：專案的 desktop/data。寫不進去（例如裝在 Program Files）才退回 userData */
function dataDir() {
  const preferred = path.join(app.isPackaged ? path.dirname(process.execPath) : __dirname, 'data')
  for (const d of [preferred, path.join(app.getPath('userData'), 'data')]) {
    try {
      fs.mkdirSync(d, { recursive: true })
      const probe = path.join(d, '.probe')
      fs.writeFileSync(probe, '')
      fs.unlinkSync(probe)
      return d
    } catch {}
  }
  return preferred
}

const file = () => path.join(dir, FILE_NAME)
const backupDir = () => path.join(dir, 'backups')

/**
 * 頁面那邊和 localStorage 一樣是「字串對字串」。存檔時能解析成 JSON 的值直接存成物件，
 * 備份檔打開來看得懂；解析出來是字串的保持原樣，讀回來才會一模一樣。
 */
function toFile(map) {
  const data = {}
  for (const [k, v] of Object.entries(map)) {
    let parsed
    try {
      parsed = JSON.parse(v)
    } catch {
      parsed = v
    }
    data[k] = typeof parsed === 'string' ? v : parsed
  }
  return { app: 'AniPlay', format: 1, savedAt: new Date().toISOString(), data }
}

function fromFile(obj) {
  if (!obj || typeof obj !== 'object' || obj.app !== 'AniPlay' || !obj.data || typeof obj.data !== 'object') {
    throw new Error('這不是 AniPlay 的備份檔')
  }
  const map = {}
  for (const [k, v] of Object.entries(obj.data)) map[k] = typeof v === 'string' ? v : JSON.stringify(v)
  return map
}

function readFile(f) {
  let obj
  try {
    obj = JSON.parse(fs.readFileSync(f, 'utf8'))
  } catch (e) {
    throw new Error(e.code === 'ENOENT' ? '找不到這個備份檔' : '備份檔壞掉了，讀不出內容')
  }
  return fromFile(obj)
}

/** 先寫暫存檔再改名，寫到一半當機也不會把原本的檔案弄壞 */
function writeAtomic(f, map) {
  const tmp = f + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(toFile(map), null, 1), 'utf8')
  fs.renameSync(tmp, f)
}

function save(map) {
  current = map
  try {
    writeAtomic(file(), map)
  } catch (e) {
    console.error('save data failed', e)
  }
}

const stamp = (d = new Date()) => {
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 每天第一次開程式時，把資料檔複製一份到 data/backups，只留最新 20 份 */
function dailyBackup() {
  try {
    if (!fs.existsSync(file())) return
    fs.mkdirSync(backupDir(), { recursive: true })
    const target = path.join(backupDir(), `${stamp()}.json`)
    if (!fs.existsSync(target)) fs.copyFileSync(file(), target)
    pruneBackups()
  } catch {}
}

function listBackups() {
  try {
    return fs.readdirSync(backupDir())
      .filter((n) => n.endsWith('.json'))
      .map((name) => {
        const st = fs.statSync(path.join(backupDir(), name))
        return { name, size: st.size, time: st.mtimeMs }
      })
      .sort((a, b) => b.time - a.time)
  } catch {
    return []
  }
}

function pruneBackups() {
  for (const b of listBackups().slice(KEEP_BACKUPS)) {
    try { fs.unlinkSync(path.join(backupDir(), b.name)) } catch {}
  }
}

function summary(map) {
  const count = (k) => {
    try { return JSON.parse(map[k] || '[]').length } catch { return 0 }
  }
  return { favorites: count('favorites'), later: count('watch_later'), history: count('history') }
}

/** 用備份檔取代目前資料；取代前先把目前的資料存一份，還原錯了還能救回來 */
function restoreFrom(f) {
  const map = readFile(f)
  try {
    fs.mkdirSync(backupDir(), { recursive: true })
    const t = new Date()
    const name = `還原前-${stamp(t)}-${String(t.getHours()).padStart(2, '0')}${String(t.getMinutes()).padStart(2, '0')}${String(t.getSeconds()).padStart(2, '0')}.json`
    writeAtomic(path.join(backupDir(), name), current)
    pruneBackups()
  } catch {}
  save(map)
  return map
}

function setup() {
  dir = dataDir()
  let found = false
  try {
    current = readFile(file())
    found = true
  } catch (e) {
    if (fs.existsSync(file())) {
      // 檔案壞掉：改名保留起來，改用最近一份自動備份
      try { fs.renameSync(file(), `${file()}.broken-${Date.now()}`) } catch {}
      for (const b of listBackups()) {
        try {
          current = readFile(path.join(backupDir(), b.name))
          found = true
          save(current)
          break
        } catch {}
      }
    }
  }
  dailyBackup()

  // 頁面一開始就要用到資料，同步回傳；found=false 代表還沒有資料檔，頁面會把舊版 localStorage 的資料搬過來
  ipcMain.on('data:load', (e) => { e.returnValue = { found, data: current } })
  ipcMain.on('data:save', (_e, map) => save(map))
  ipcMain.on('data:save-now', (e, map) => { save(map); e.returnValue = true })

  ipcMain.handle('data:info', () => ({ dir, file: file(), backups: listBackups(), summary: summary(current) }))
  ipcMain.handle('data:open-folder', () => shell.openPath(dir))

  ipcMain.handle('data:export', async (e) => {
    const win = BrowserWindow.fromWebContents(e.sender)
    const r = await dialog.showSaveDialog(win, {
      title: '匯出 AniPlay 紀錄',
      defaultPath: path.join(app.getPath('documents'), `AniPlay-備份-${stamp()}.json`),
      filters: [{ name: 'AniPlay 備份', extensions: ['json'] }],
    })
    if (r.canceled || !r.filePath) return null
    writeAtomic(r.filePath, current)
    return r.filePath
  })

  // name：自動備份的檔名；沒給就跳出選檔視窗
  ipcMain.handle('data:restore', async (e, name) => {
    let f
    if (name) {
      f = path.join(backupDir(), path.basename(name))
    } else {
      const r = await dialog.showOpenDialog(BrowserWindow.fromWebContents(e.sender), {
        title: '選擇要還原的 AniPlay 備份',
        defaultPath: app.getPath('documents'),
        filters: [{ name: 'AniPlay 備份', extensions: ['json'] }],
        properties: ['openFile'],
      })
      if (r.canceled || !r.filePaths[0]) return null
      f = r.filePaths[0]
    }
    return restoreFrom(f)
  })
}

module.exports = { setup }
