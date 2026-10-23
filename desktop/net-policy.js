// 網路相關設定：補 CORS 標頭、關掉磁碟快取
const { app, session } = require('electron')
const fs = require('fs')
const path = require('path')

/**
 * 關掉 Chromium 的磁碟快取（要在 app ready 之前呼叫）。
 * 不關的話看過的影片片段都會存進 userData/Cache，看越多越大（實測看幾集就 300 MB）。
 * 在回應標頭加 Cache-Control: no-store 沒用：Electron 寫入快取比 webRequest 改標頭早。
 * 封面圖片同一次執行中還是有記憶體快取，只是下次開程式要重新下載（每張幾十 KB）。
 */
function disableDiskCache() {
  app.commandLine.appendSwitch('disable-http-cache')
}

/** 刪掉舊版留下的磁碟快取（快取已經關掉，這個資料夾不會再被使用） */
function removeOldCache() {
  fs.rm(path.join(app.getPath('userData'), 'Cache'), { recursive: true, force: true }, () => {})
}

/** 影片 CDN 只對特定來源回 CORS，本機 file:// 頁面的 Origin 是 null，統一改成允許全部 */
function allowCors(ses = session.defaultSession) {
  ses.webRequest.onHeadersReceived((details, callback) => {
    const headers = { ...details.responseHeaders }
    for (const k of Object.keys(headers)) {
      if (k.toLowerCase().startsWith('access-control-allow-')) delete headers[k]
    }
    headers['Access-Control-Allow-Origin'] = ['*']
    headers['Access-Control-Allow-Headers'] = ['*']
    callback({ responseHeaders: headers })
  })
}

module.exports = { disableDiskCache, removeOldCache, allowCors }
