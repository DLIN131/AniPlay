# AniPlay

動畫播放器，三個版本共用同一個資料來源：

| 版本 | 資料夾 | 技術 | 產出 |
|---|---|---|---|
| 手機 App | `android/phone` | Kotlin + Jetpack Compose (Material 3) | `release/AniPlay.apk` |
| Android TV | `android/tv` | Kotlin + Compose for TV | `release/AniPlayTV.apk` |
| Windows 桌面 | `desktop` | Electron + hls.js | `release/AniPlay-Windows.zip` |

`android/core` 是手機版和電視版共用的 API、收藏／紀錄、播放器（Media3 ExoPlayer，HLS）。

## 功能

- 首頁：輪播、繼續觀看、本週熱門、最新、劇場版、高分推薦…
- 分類：類別 × 排序 × 類型 × 版本 × 年份篩選
- 新番時間表（週一到週日）
- 搜尋（含搜尋紀錄）
- 收藏、觀看紀錄、每集播放進度（存在本機）
- 播放器：繁／簡線路、選集、上一集／下一集、看完自動下一集、倍速、記住進度

### 操作

- **手機**：點畫面顯示控制列，雙擊左右側快退／快進 10 秒
- **電視遙控器**：左右快退／快進（長按加速）、OK 暫停、長按 OK 2 倍速、上鍵選集、下鍵進度列
- **桌面鍵盤**：空白鍵暫停、← → 快退／快進、↑ ↓ 音量、F 全螢幕、N／P 下一集／上一集、F11 視窗全螢幕

## 建置

```bash
cd android
./gradlew :phone:assembleRelease :tv:assembleRelease
```

```bash
cd desktop
npm install
npm run pack
```

桌面版介面開發時可用 `node desktop/devserver.js` 在瀏覽器開 `http://localhost:5577/src/index.html` 預覽。

沒有 `android/keystore/keystore.properties` 時，release APK 用 debug 簽章。
