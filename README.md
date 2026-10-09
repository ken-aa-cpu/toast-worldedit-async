# Toast_WorldEditAsync

Arclight（Forge/NeoForge + Bukkit 混合伺服器）上的 WorldEdit 非同步方塊引擎 PoC。

WorldEdit 原生的大範圍操作（`//paste`、`//set` 等）會在呼叫執行緒上一次同步跑完整個
區域，操作大到一定程度就會撞上伺服器看門狗，把整台伺服器砍掉。這個外掛接管寫入/復原/
`//paste`/光照重算這幾條路徑，改成跨很多 tick、每 tick 限時分批完成，讓大範圍操作
「慢一點但絕對不會讓伺服器死掉」。

實測：5676 萬格的城堡（`finishedcastle.schem`）在 Arclight 上完整貼上、復原光照，
全程沒有觸發看門狗，MSPT 落地後回到個位數毫秒。

## 需求

- Arclight 1.21.1（NeoForge）
- WorldEdit for Bukkit 7.3.7
- Rust（僅編譯期需要，`cargo build` 會自動觸發，執行期不需要裝 Rust）

## 建置

```bash
./gradlew.bat build
```

輸出的 jar 在 `build/libs/Toast_WorldEditAsync-0.2.0.jar`，放進 `plugins/` 資料夾即可
（跟 WorldEdit 一起裝，`plugin.yml` 宣告了 `depend: [WorldEdit]`）。

## 開始使用

引擎預設是開的（可在 `config.yml` 的 `engine.enabled-by-default` 改成 `false`），開服後
不用再手動下指令。要臨時關掉比對/退回原生行為，隨時可以：

```
/wepoc engine off
```

`engine=off` 時，所有 WorldEdit 指令都走原生路徑，跟沒裝這個外掛完全一樣。`engine=on`
（預設狀態）之後：

- `//set`、`//replace` 等大範圍編輯指令的**寫入**跟**光照重算**會被接管，跨 tick 分批
- `//paste` 整個指令被接管，改成我們自己跨 tick 分批驅動（見下方「為什麼 `//paste` 要
  整個接管」）
- `//undo`、`//redo` 一律導到這個外掛自建的 undo/redo 堆疊，完全不碰 WorldEdit 原生的
  history 系統

### 檔位（速度）

任何支援本引擎的指令，可以在最後加一個檔位關鍵字，控制「每 tick 願意花多少時間」：

```
//set stone fast
//paste slow
/undo auto
```

- `slow` / `mid`（預設）/ `fast`：固定每 tick 時間預算，檔位越快，完成越快但也越可能
  跟其他伺服器工作搶資源。
- `auto`：動態量測真實 tick 耗時，自動調整預算——伺服器忙的時候自動讓步，閒的時候盡快
  做完。**用時間換安全**，一般情況下建議用這個。

### 危險確認閘

任何預估會動到超過 2000 萬格的操作，會先被擋下來：

```
[WEPoC] 這個操作預估會動到約 X 格，超過 20,000,000 格的危險門檻...
```

確定要繼續，緊接著（30 秒內）輸入：

```
/wepoc danger confirm
```

再送出原本那個指令一次即可放行（只放行一次，不會一直記著）。

## 設定檔（config.yml）

`plugins/Toast_WorldEditAsync/config.yml`（第一次啟動會自動產生預設值）：

```yaml
engine:
  enabled-by-default: true   # 開服時引擎是否自動開啟

danger-gate:
  block-threshold: 20000000  # 危險確認閘的門檻（格數）
  confirm-ttl-seconds: 30    # /wepoc danger confirm 後的有效時間

speed-tiers:
  slow-budget-ms: 2
  mid-budget-ms: 6
  fast-budget-ms: 15

undo:
  max-stack-per-player: 15   # 每位玩家 undo/redo 堆疊上限（執行期仍可用 /wepoc maxundo 覆寫）
```

改完要重啟伺服器才會生效，目前沒有 `/wepoc reload` 這種執行期重載指令。

## 常用指令一覽

| 指令 | 說明 |
|---|---|
| `/wepoc engine <on\|off>` | 開關整套接管邏輯 |
| `/wepoc danger confirm` | 放行上一個被危險閘擋下的操作 |
| `/wepoc stats` | 看目前 undo/redo 深度、auto 動態預算、最近一次操作統計 |
| `/wepoc mspt` | 看伺服器目前的真實 MSPT（平均/最近一筆） |
| `/wepoc maxundo <n>` | 設定每位玩家 undo/redo 堆疊上限（預設 15） |
| `/wepoc hookstatus` | 檢查 EditSessionEvent 訂閱、BukkitImplAdapter 是否正常註冊 |
| `/wepoc debug <on\|off>` | 切換詳細日誌（`[WEPoC-DEBUG]` 前綴），排查「某個外掛的呼叫有沒有被接管到」這類問題時開 |

## 已知限制

老實列出目前還沒做完、或刻意沒做的部分：

1. **只有 `//paste` 被整個接管**，`//set`/`//replace` 等其餘大範圍指令的「決定要寫哪些
   格子」這一步（WorldEdit 原生 `RegionVisitor` 迴圈）仍然是同步、不可分段的。實測
   `ChangeBuffer` 容量修正之後，這些指令在合理範圍內（測到 2700 萬格為止）不會崩潰，
   但沒有像 `//paste` 一樣被主動接管、也沒有測出修正後真正的新安全上限。
2. **危險門檻（2000 萬格）沒有針對 Arclight 重新精確校準**——目前的數字留有安全邊際，
   但不是用 Arclight 的真實吞吐量算出來的精確值。
3. **`//paste` 的 entity/biome 複製（`-e`/`-b`）仍是同步一次跑完**，沒有跨 tick 分批
   保護。一般情況下這兩者的數量遠小於方塊數，但沒有實測驗證極端案例。
4. **沒有測過多人同時進行大範圍操作的併發情境。**
5. **WorldEdit 的官方剪貼簿（`//copy`/`//schem load` 用的）本身沒有優化**——大型結構
   複製到剪貼簿仍然吃掉可觀記憶體（Java 物件陣列），這個外掛只優化了「怎麼把剪貼簿內容
   寫進世界」，沒有動剪貼簿儲存本身。
6. **仍是 PoC 等級**：程式碼裡留著不少開發過程用的診斷指令（`rustlighttest`、
   `lightwritetest`、`relightverify`、`noactortest`、`blockdiff`、`lightdiff`、
   `propdump` 等），正式營運前應該考慮加權限保護或移除。
7. **只驗證過 Arclight 1.21.1+ WorldEdit 7.3.7 這個確切版本組合**——內部用到不少該版本
   的反射/內部類別細節，版本升級後這些反射點大概率會失效（啟動時 `hookstatus` 會明確
   報告失敗，不會靜默壞掉）。
8. **`config.yml` 的值只在啟動時讀一次**，沒有執行期重載指令，改完設定檔要重啟伺服器。
9. **undo/redo 堆疊、`/wepoc stats` 統計數字都只存在記憶體裡**，伺服器重啟就會歸零，
   沒有落地成檔案或資料庫，也沒有操作稽核紀錄——`logs/latest.log` 有印出關鍵事件，
   但那是 vanilla 自己的日誌機制，不是本引擎專門管理的紀錄。

## 給想深入的人：完整工程紀錄

`驗證報告.md` 是整個開發過程的逐項驗證記錄（每一輪測試、每一個踩過的坑、每一次
反編譯確認的根因），包含：

- 五項風險逐項驗證
- `ChangeBuffer` 兩次效能坑（雜湊碰撞退化、預設容量擴容風暴）的完整診斷過程
- Rust 光罩計算池的演算法驗證與 Paper/Arclight 寫入機制差異的根因
- `//paste` 接管的完整設計理由（含反編譯 WorldEdit 原始碼的關鍵發現）
- 「打印機」執行緒池平行化的完整 A/B 對照實驗（含最終判定「沒有實測到加速，拆掉」）

這份文件很長，但如果你想知道「為什麼是這樣設計，不是那樣」，答案幾乎都在裡面。

## License

[GPL-3.0](LICENSE)。這個外掛依賴 WorldEdit（本身是 GPL-3.0）、`//paste` 接管的管線設計
是反編譯 WorldEdit 原始碼（`ClipboardCommands.paste`/`PasteBuilder`/`ForwardExtentCopy` 等）
確認邏輯後用它自己公開的類別重組出來的，選用同一份授權條款最不容易有問題。
