# AniPlay 專案脈絡（project.context.md）

這份文件記錄 AniPlay 每次改動的**技術細節**、**改了什麼／修了什麼**，以及踩過的坑。
給之後接手（或下一次對話）的人看，避免重複犯錯。

> 維護規則：**每次改動都要更新這份文件**——在「改動紀錄」最上面新增一筆（版本、日期、改了什麼、技術細節、修了什麼），
> 有新的坑就補到「重要技術細節與坑」。這份文件不要寫出資料來源網站的名稱或網域。

---

## 1. 專案概覽

動畫播放器，三個版本共用同一個資料來源（網站的公開 JSON API，**不需要**過 Cloudflare）。

| 版本 | 位置 | 技術 | Release 檔名 |
|---|---|---|---|
| 手機 | `android/phone` | Kotlin + Jetpack Compose (Material 3)，applicationId `com.aniplay.phone` | `AniPlay.apk` |
| Android TV | `android/tv` | Kotlin + Compose for TV (`tv-material`)，applicationId `com.aniplay.tv` | `AniPlayTV.apk` |
| Windows 桌面 | `desktop` | Electron 33 + hls.js 1.6.13，純 HTML/CSS/JS（無框架） | `AniPlay-Windows.zip` |

### Android（`android/`，多模組）
- `core`：手機、電視共用。
  - `net/Api.kt`：API 呼叫與解析（`Anime`、`AnimeDetail`、`Query`…）。API 與圖片主機網域以 **Base64** 存在 `BASE` / `IMG`。
  - `net/Store.kt`：本機資料（SharedPreferences + Compose state list）：收藏、待看清單、觀看紀錄（上限 300）、每集播放進度、搜尋紀錄。
  - `net/Danmaku.kt`：彈幕抓取與解析；`net/ChineseConvert.kt`：繁→簡（搜尋用）。
  - `player/PlayerActivity.kt`：全螢幕播放器（Media3 ExoPlayer，HLS + mp4），觸控與遙控器共用；`player/DanmakuView.kt`：彈幕層。
  - `ui/ViewModels.kt`：Home / Feed（分頁列表）/ Detail / Week / Changelog 的 ViewModel、`Tags`（篩選選項快取）。
  - `util/Updater.kt`：App 內更新、更新紀錄、`checkWhatsNew()`；`App.kt` 內有 `Prefs`。
- 版本：AGP 9.0.1、Kotlin compose plugin 2.2.10、Gradle 9.1.0、Compose BOM 2025.06.00、Media3 1.6.1、Coil 2.7.0、OkHttp 4.12.0。
- 導覽：不用 navigation library，自己維護 `stack: mutableStateListOf<Route>()` + `LocalNav`。

### 桌面（`desktop/`）
- `main.js`：建立視窗、單一實例鎖、呼叫 `net-policy`、`updater`。
- `net-policy.js`：關閉磁碟快取、刪舊快取、補 CORS 標頭。
- `updater.js` + `preload.js`：程式內更新（IPC：`update:supported` / `update:download` / `update:install`）。
- `src/app.js`：整個前端（hash 路由 `#/home`、`#/browse`、`#/play/<id>/<線路>/<集>`…），`src/style.css`、`src/index.html`。
- `src/t2s.js`：繁→簡對照表（由 OpenCC `TSCharacters.txt` 產生，Apache-2.0）；Android 版同一份在 `core/src/main/assets/t2s.txt`。
- `src/version.js`：`window.APP_VERSION`，CI 寫入 run_number；本機開發是 0（不檢查更新）。
- `pack.js`：打包腳本（**一定要用這個打包**，見坑 #1）。
- `devserver.js`：在瀏覽器預覽桌面版介面（`node desktop/devserver.js` → `http://localhost:5577/src/index.html`）。

### 建置、發布
- GitHub：`DLIN131/AniPlay`（public）。`.github/workflows/release.yml`：push 到 `main` → android job（ubuntu）+ desktop job（windows-latest, Node 20）→ release `v1.0.<run_number>`，附三個檔案。`**.md` 在 `paths-ignore`，只改文件不會發版。
- 固定下載網址：`https://github.com/DLIN131/AniPlay/releases/latest/download/<檔名>`。
- 簽章：`android/keystore/`（gitignore，alias `aniplay`）+ GitHub Secrets（`KEYSTORE_BASE64`、`SIGNING_*`）。弄丟 keystore 就無法覆蓋安裝。
- commit message＝Release 說明＝App 內「更新紀錄」內容 → 用**繁體中文、給使用者看的寫法**，最後一行 Co-Authored-By 會被 App 濾掉。
- 本機桌面版：打包到 `desktop/dist/AniPlay-win32-x64`（使用者直接從這裡執行）。版本號要和 GitHub 最新版一致（`APP_VERSION=1.0.N` + `src/version.js` 設成 N，打包完 `git checkout src/version.js`），不然一開就會提示更新。

### 資料來源 API（`/api.php/` 底下）
- `Vod/get_list`：`offset`、`limit`、`orderby`（只能 `hits|up|pubdate|hits_week|hits_month|hits_day|score`）、`type_id`（2 日番、21 劇場版、3 美番、20 真人、24 BD 副音軌、26 演唱會）、`vod_name`、`vod_class`、`vod_year`、`vod_area`（＝季度月份：一月／四月／七月／十月）、`vod_version`…
- `Vod/get_detail?vod_id=`：`vod_play_from`＝`cht$$$chs`，`vod_play_url`＝`01$網址#02$網址$$$…`；網址可能是 m3u8 **或 mp4**。
- `App2/weekList`（week 1＝週一）、`App2/bannerList`、`Vod/getAllTagOption`。
- 彈幕：`Scrolling/getScrolling`（POST multipart `play_url`，站內彈幕）、`Scrolling/getVodOutScrolling`（回傳外部彙整彈幕 XML 網址，B 站 `<d p="時間,模式,字號,顏色,…">` 格式；沒給時由 `…/playlist.m3u8` 推成 `….xml`）。
- 回應 `code != 1` 就是錯誤；偶爾會回 HTML 的 System Error（暫時性，重試即可）。
- 列表的 `vod_time` 是數字時間戳，時間表裡的 `vod_time` 才是「更新至03集」字串。標題等文字可能含 `&amp;` 之類的 HTML 跳脫。

---

## 2. 重要技術細節與坑

1. **桌面版打包不能在命令列傳 `--ignore=^/dist`**：Windows 上 `npx` 經過 `cmd.exe`，`^` 是跳脫字元會被吃掉，變成 `/dist`，連 `node_modules/hls.js/dist` 都被排除 → m3u8 全部播不了（v1.0.1～v1.0.4 都中招）。現在用 `pack.js`（排除規則寫在程式裡，打包完會檢查 `hls.min.js` 有沒有在 asar 裡）。Git Bash 另外還會把 `/dist` 轉成 Windows 路徑（`MSYS_NO_PATHCONV=1` 可關）。
2. **Electron 磁碟快取**：Chromium 會把看過的影片片段都存進 `userData/Cache`（看幾集就 300 MB）。`disk-cache-size` 開關、在 `webRequest.onHeadersReceived` 加 `Cache-Control: no-store` **都沒用**（快取在改標頭之前就寫了）。有效的是 `app.commandLine.appendSwitch('disable-http-cache')`（要在 ready 前）。
3. **桌面版程式內更新**（`updater.js`）：
   - 必須用 `require('original-fs')`：Electron 的 `fs` 會把解壓出來的 `app.asar` 當資料夾，刪不掉（ENOTEMPTY）還鎖住（EBUSY）。
   - 覆蓋檔案的腳本要在程式結束後跑：detached 的 `cmd /c start "" /min powershell -File update.ps1`（`windowsVerbatimArguments`）。非 detached 子程序會被 libuv 的 job object 跟著父程序關掉；detached 又沒有主控台，PowerShell 直接結束、`tasklist | find` 會卡死，所以要靠 `start` 開新主控台。
   - 背景執行的 cmd 用系統字碼頁（Big5）讀批次檔 → 路徑有中文會亂碼。腳本只放英數字，路徑全部用環境變數（`AP_SRC`、`AP_DST`、`AP_EXE`、`AP_PID`、`AP_LOG`）傳。
   - 每次更新用 `%TEMP%/AniPlay-update/<時間戳>` 新資料夾；清理時跳過正在用的和 10 分鐘內的。
   - PowerShell 錯誤訊息要設 `[Console]::OutputEncoding=UTF8`，不然是 Big5 亂碼。
4. **全螢幕**：只把 `<video>` 全螢幕，彈幕 canvas 會被蓋掉。桌面版控制列、彈幕、面板都放在 `.video-wrap` 裡，全螢幕的是整個 `.video-wrap`；原生控制列不用。
5. **彈幕對齊**：彈幕位置一律由「影片目前時間 − 彈幕時間」算（Android 用 `player.currentPosition`，桌面用 `video.currentTime`），不用自己的計時器；往回拖或跳超過 1 秒就重排，並補上「現在應該在畫面上」的彈幕。
6. **hls.js 記憶體**：預設 `backBufferLength` 是無限，長片越看越吃記憶體 → 設 90 秒。
7. **桌面版 mp4**：有些作品是 mp4，不能丟給 hls.js；網址不是 `.m3u8` 就直接 `video.src`。
8. **Android**：`DanmakuView` 不能有叫 `enabled` 的屬性（和 `View.setEnabled` 撞 JVM 簽章）→ 叫 `showing`。TV 的 Compose `LazyColumn` 裡要有可聚焦元件方向鍵才捲得動（更新紀錄每筆用 `Card(onClick = {})`）。
9. **測試桌面打包版**：用 `--remote-debugging-port=9339 --remote-allow-origins=*` 啟動，再用 CDP（WebSocket `Runtime.evaluate`，需要時 `userGesture: true`）操作。視窗被蓋住時 `document.hidden` 為 true，影片不載入、全螢幕卡住。**不要**用 `SetForegroundWindow` 把視窗拉到前面打擾使用者；能用 `show: false` 的小測試就用。
10. **Android 測試**：使用者不用模擬器，只用 Gradle 編譯；手機／電視上的實際行為都還沒在實機驗證過。
11. 改檔時用 node 腳本做字串替換要小心：heredoc 裡的 `\\n` 寫進檔案可能變成真的換行；同名欄位（例如 `Anime.year` 和 `Query.year`）會替換到第一個。

---

## 3. 改動紀錄（新的在上面）

### v1.0.11（2026-10-24）新增更新紀錄
- **改了什麼**：三版都有「更新紀錄」頁（手機：我的 → 更新紀錄；電視：側邊選單；電腦：側邊欄最下面）；更新後第一次開啟跳出「這次更新了什麼」。
- **技術細節**：
  - 內容直接讀 GitHub Releases API（`/releases?per_page=50`），`cleanNotes` 去掉 `---` 以下的下載資訊和 Co-Authored-By。
  - Android：`Updater.Note`、`fetchChangelog()`、`checkWhatsNew()`（比對 `Prefs.lastSeenVersion`）、`ChangelogViewModel`；phone `Changelog.kt`（`Route.Changelog`、`WhatsNewDialog`），tv `Changelog.kt`（`Tab.Changelog`）。
  - 桌面：`fetchChangelog()`、`pageChangelog()`（`#/changelog`）、`checkWhatsNew()`（`localStorage.last_seen_version`），`.modal` 樣式；側邊欄 active 判斷改成 `.sidebar a[data-tab]`。
  - 第一次裝（沒有上次版本）不跳；從沒有這功能的舊版升上來也不會跳（會被當成第一次）。

### v1.0.10（2026-10-24）分類新增月份篩選
- **改了什麼**：分類頁新增「月份」（一月／四月／七月／十月），可搭配年份；修正標題出現 `&amp;`。
- **技術細節**：`Query.month` → `vod_area`；`Api.months`；桌面 `browseState.month`、`MONTHS`。Kotlin `JSONObject.str()` 與桌面 `str()` 加上 HTML 實體還原（`&amp;` 最後換）。
- 坑：第一次插入 `month` 欄位插到了 `Anime`（第一個 `val year`），已移到 `Query`。

### v1.0.9（2026-10-24）修正電腦版更新解壓縮時找不到更新檔（另一個工作階段）
- **修了什麼**：啟動後 3 秒的暫存清理會刪掉剛開始下載的 `update.zip`；同時開兩份程式會互相清掉暫存。
- **技術細節**：`cleanOld()` 跳過正在用的資料夾和 10 分鐘內建立的；`app.requestSingleInstanceLock()`，第二份會叫出原視窗；下載完檢查檔案大小＝`content-length`；`Expand-Archive` 錯誤訊息改 UTF-8。

### v1.0.8（2026-10-24）修正電腦版更新第二次以後會失敗（另一個工作階段）
- **修了什麼**：更新過一次後，下次更新出現「directory not empty」。
- **技術細節**：`updater.js` 改用 `original-fs`；每次更新用新的時間戳資料夾。

### v1.0.7（2026-10-23）電腦版不再越用越占空間
- **改了什麼**：影片不存硬碟快取；開啟時刪掉舊快取；更新暫存清理；記憶體回收；播放進度清理。
- **技術細節**：新增 `net-policy.js`（`disable-http-cache`、刪 `userData/Cache`、CORS）；hls `backBufferLength: 90`；`store.prunePositions()` 刪掉不在觀看紀錄裡的 `pos_*`。實測：16.4 MB 影片下載後快取增加 0；使用者電腦上舊快取 141 MB → 0。
- 坑：見「坑 #2」，兩種常見作法實測無效。

### v1.0.6（2026-10-23）新增待看清單
- **改了什麼**：作品頁「待看」按鈕、首頁最上面一列、手機「我的 → 待看」、電視側邊選單、電腦側邊欄。
- **技術細節**：Android `Store.watchLater`（key `watch_later`）、`toggleWatchLater`；TV `LocalListScreen` 改成 `LocalList` enum；桌面 `store.later()` / `toggleLater()`、`#/later`、`pageLocal(kind)`。

### v1.0.5（2026-10-23）播放器控制列、全螢幕彈幕設定，修正電腦版無法播放
- **修了什麼**：**電腦版 m3u8 全部播不出來**（打包漏掉 `hls.js/dist`，見坑 #1）；全螢幕只能雙擊進入、全螢幕看不到彈幕設定。
- **改了什麼**：桌面版自訂控制列（播放、進度條、音量、上下集、彈幕開關、彈幕設定、倍速、選集、全螢幕），全部疊在 `.video-wrap` 裡，3 秒自動隱藏；手機／電視彈幕設定改成單一面板（`danmakuSettings()` 用 AlertDialog + 自建可聚焦 chip）。
- **技術細節**：新增 `desktop/pack.js`，CI 改用 `node pack.js dist`；`package.json` 的 `pack` script 改成 `node pack.js`。

### v1.0.4（2026-10-23）全螢幕彈幕、繁體搜尋
- **修了什麼**：電腦版用原生全螢幕按鈕時彈幕被蓋掉。
- **改了什麼**：`controlslist="nofullscreen"` + 自己的全螢幕按鈕（全螢幕 `.video-wrap`）；三版搜尋自動繁→簡（`ChineseConvert` / `toSimplified`，在 `Api.list` / `list()` 統一轉換，搜尋框保留原字）。

### v1.0.3（2026-10-23）電腦版程式內更新
- **改了什麼**：電腦版按「立即更新」直接下載、安裝、重開。
- **技術細節**：`updater.js` + `preload.js`；下載用 `net.fetch`、解壓用 PowerShell `Expand-Archive`；安裝腳本細節見坑 #3（實測時依序修掉：中文路徑亂碼、`find` 卡住、子程序被一起關掉）。
- 注意：v1.0.2 以前只會開瀏覽器下載，要手動更新一次。

### v1.0.2（2026-10-23）新增彈幕
- **改了什麼**：站內彈幕＋外部彙整彈幕合併；時間微調（每部作品記住）、字體大小、不透明度、顯示範圍。
- **技術細節**：Android `DanmakuApi`（XmlPullParser）、`DanmakuView`（Canvas，`postInvalidateOnAnimation`，捲動 8 秒、置頂／置底 4 秒，軌道不夠就丟掉）；桌面 `loadDanmaku()`（DOMParser）、`danmakuLayer()`（canvas + rAF）。
- **修了什麼**：桌面版 mp4 作品無法播放（原本全部丟給 hls.js）。

### v1.0.1（2026-10-23）第一版
- 手機、Android TV、Windows 三版；首頁（輪播、繼續觀看、各分類列）、分類篩選、新番時間表、搜尋、收藏、觀看紀錄、每集播放進度；播放器（繁／簡線路、選集、看完自動下一集、倍速）；Android App 內更新；GitHub CI 自動發版、正式簽章。
