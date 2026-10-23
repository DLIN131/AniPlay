const { app, BrowserWindow, session, shell } = require('electron')
const path = require('path')

// 影片 CDN 只對特定來源回 CORS，本機 file:// 頁面的 Origin 是 null，統一補上允許標頭
function allowCors() {
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    const headers = { ...details.responseHeaders }
    for (const k of Object.keys(headers)) {
      if (k.toLowerCase().startsWith('access-control-allow-')) delete headers[k]
    }
    headers['Access-Control-Allow-Origin'] = ['*']
    headers['Access-Control-Allow-Headers'] = ['*']
    callback({ responseHeaders: headers })
  })
}

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
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
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

app.whenReady().then(() => {
  allowCors()
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
