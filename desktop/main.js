const { app, BrowserWindow, shell } = require('electron')
const path = require('path')
const updater = require('./updater')
const netPolicy = require('./net-policy')

// 看過的影片不存磁碟，不然越看占用越多
netPolicy.disableDiskCache()

let mainWin = null

function createWindow() {
  const win = new BrowserWindow({
    width: 1360,
    height: 860,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#121218',
    title: 'AniPlay',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'src', 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  mainWin = win
  win.removeMenu()
  win.loadFile(path.join(__dirname, 'src', 'index.html'))

  // 外部連結用系統瀏覽器開
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
  // F11 全螢幕、F12 開發者工具
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return
    if (input.key === 'F11') {
      win.setFullScreen(!win.isFullScreen())
      e.preventDefault()
    } else if (input.key === 'F12') {
      win.webContents.toggleDevTools()
      e.preventDefault()
    }
  })
}

// 同時只開一份：再開一次就把原本的視窗叫到前面（兩份同時跑會互相清掉對方的更新暫存）
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (!mainWin || mainWin.isDestroyed()) return
    if (mainWin.isMinimized()) mainWin.restore()
    mainWin.show()
    mainWin.focus()
  })
}

app.whenReady().then(() => {
  if (!app.hasSingleInstanceLock()) return
  netPolicy.allowCors()
  netPolicy.removeOldCache()
  updater.setup()
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
