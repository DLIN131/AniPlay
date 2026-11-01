// 給頁面用的程式內更新、資料存檔介面（頁面本身不能直接碰檔案系統）
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('updater', {
  supported: () => ipcRenderer.invoke('update:supported'),
  download: (url, onProgress) => {
    const listener = (_e, p) => onProgress(p)
    ipcRenderer.on('update:progress', listener)
    return ipcRenderer.invoke('update:download', url).finally(() => ipcRenderer.removeListener('update:progress', listener))
  },
  install: () => ipcRenderer.invoke('update:install'),
})

// 收藏、紀錄、進度存在程式資料夾的 data/aniplay-data.json（見 data-store.js）
contextBridge.exposeInMainWorld('appData', {
  load: () => ipcRenderer.sendSync('data:load'),
  save: (map) => ipcRenderer.send('data:save', map),
  saveNow: (map) => ipcRenderer.sendSync('data:save-now', map),
  info: () => ipcRenderer.invoke('data:info'),
  openFolder: () => ipcRenderer.invoke('data:open-folder'),
  exportTo: () => ipcRenderer.invoke('data:export'),
  restore: (name) => ipcRenderer.invoke('data:restore', name),
})
