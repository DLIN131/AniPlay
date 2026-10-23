// 開發用：在瀏覽器預覽桌面版介面（node devserver.js → http://localhost:5577/src/index.html）
const http = require('http')
const fs = require('fs')
const path = require('path')
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.ico': 'image/x-icon' }
http.createServer((req, res) => {
  const p = path.join(__dirname, decodeURIComponent(req.url.split('?')[0]))
  if (!p.startsWith(__dirname)) return res.writeHead(403).end()
  fs.readFile(p, (err, data) => {
    if (err) return res.writeHead(404).end()
    res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'application/octet-stream' }).end(data)
  })
}).listen(5577)
