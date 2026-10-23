// 打包 Windows 版：node pack.js [輸出資料夾]
// 排除規則寫在這裡而不是命令列：在 Windows 上 npx 會經過 cmd.exe，`^` 是跳脫字元會被吃掉，
// "^/dist" 變成 "/dist"，連 node_modules/hls.js/dist 都被排除，結果影片播不了。
const { packager } = require('@electron/packager')
const path = require('path')
const fs = require('fs')

const out = process.argv[2] || 'dist'
const version = process.env.APP_VERSION || require('./package.json').version

packager({
  dir: __dirname,
  name: 'AniPlay',
  platform: 'win32',
  arch: 'x64',
  out,
  overwrite: true,
  asar: true,
  icon: path.join(__dirname, 'src', 'icon.ico'),
  appVersion: version,
  // 只排除專案根目錄的東西（路徑以 / 開頭、相對於專案資料夾）
  ignore: [/^\/dist($|\/)/, /^\/devserver\.js$/, /^\/pack\.js$/, /^\/\.gitignore$/],
}).then(([appPath]) => {
  // 確認播放 m3u8 需要的 hls.js 有打包進去
  const asar = require('@electron/asar')
  const files = asar.listPackage(path.join(appPath, 'resources', 'app.asar')).map((f) => f.replace(/\\/g, '/'))
  if (!files.includes('/node_modules/hls.js/dist/hls.min.js')) {
    console.error('打包結果缺少 hls.js/dist/hls.min.js')
    process.exit(1)
  }
  if (files.some((f) => f.startsWith('/dist/'))) {
    console.error('打包結果不應該包含 dist/')
    process.exit(1)
  }
  console.log(`打包完成：${appPath}（v${version}，${files.length} 個檔案）`)
  fs.writeFileSync(path.join(appPath, 'version.txt'), version)
}).catch((err) => {
  console.error(err)
  process.exit(1)
})
