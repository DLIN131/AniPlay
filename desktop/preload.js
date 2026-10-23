// 給頁面用的程式內更新介面（頁面本身不能直接碰檔案系統）
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
