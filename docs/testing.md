# 測試、復原與品質驗收

## 可重現的自動驗證

```sh
cd frontend
npm ci
cd ..
./gradlew test :server:bootJar
java -jar server/build/libs/server-0.1.0.jar
# 另一個終端：
python3 scripts/smoke.py
```

Gradle 測試不需要金鑰或 Docker。測試資料使用 H2 memory 或 JUnit 暫存目錄；HTTP smoke 會在目前遊戲建立清楚標示的獨立驗收存檔，不重設其他存檔。要隔離 smoke，啟動時指定新的 `WORLD_DATABASE_URL`。

| 測試 | 檢查重點 |
| --- | --- |
| `PlannerTest` | 三種目標的真正 action path、私人資訊過濾、Koog 澄清與作者備援、提交後只補敘事 |
| `ModelBoundaryTest` | Spring AI 真實 tool loop、typed output、角色可見性、六次模型／三次工具上限 |
| `WorldIntegrationTest` | HTTP、MCP SDK 的 Streamable HTTP 交握與五工具、跨入口去重、撤銷權杖、跨存檔查詢、非法輸入、型別拒絕、並行提交、記憶更正、匯入、四條路線與 200 回合 |
| `RestartPersistenceTest` | 關閉完整 Spring context 與 H2 file，再開一個 context：已接受請求續作、已提交請求不重複效果 |
| `scripts/smoke.py` | 對打包後程序以真實 HTTP 從第一章走完知識／協作 × 出發／延期，再匯入與更正記憶 |

測試報告在各模組 `build/reports/tests/test/index.html`。CI 同時建置前端並執行測試與打包。相依版本鎖在 version catalog、BOM、Gradle lockfiles 與 npm lockfile；Wrapper ZIP 另驗證 SHA-256。

## 失敗矩陣

- 接受前：未保存請求，可以使用原命令重試。
- 已接受、尚未提交：ACCEPTED 保存在 H2。程序重啟後由相同 expected revision 重新裁決；若版本已變更就終止衝突。
- 已提交、尚未完成敘事：COMMITTED 保存 `ValidatedOutcome`，只補演出。
- 已完成但回應遺失：查原 turn ID 或同內容重送原 request ID，取回相同結果。
- 模型逾時、格式錯誤：每階段至多修復一次；演出失敗採同筆作者文字。
- 同存檔第二個請求同時到達：列鎖與 pending 檢查只接受一個新的回合。
- MCP 斷線：已接受回合繼續；重連讀原 turn ID。

程序 restart 測試會重建框架物件，不靠 Koog 或 Embabel 的記憶體恢復。它不模擬停電、磁碟硬體毀損或跨版本資料庫降版。

## 200 回合模擬

固定 seed 26，結局後輪流拜訪五個本城地點，執行合法事件或休息。逐回合檢查章節不回退、重要承諾／記憶容量、關係範圍、技能證據不重複，並要求至少 20 個不同事件實際觸發，捕捉明顯事件飢餓。這是有界回歸測試，不代表任意政策下永遠沒有飢餓。

## 瀏覽器檢查

已人工操作桌面與 390 × 844 手機版，確認建檔、約定回收、非法船票輸入、場景、地圖、人物與共同記憶。日誌顯示已發生結果；更正畫面要說明不逆轉世界；忙碌時避免重複送出，網路失敗保留 request ID。

HTTP 與 MCP 已共用同一個世界；UI 每三秒重新讀取世界與最新已保存演出，使外部入口完成的回合能同步回 Web。不要把尚未完成的模型文字當成已發生事件。

## 真實模型評估：尚待執行

至少需三份**完整 live 遊玩紀錄**，不可把離線紀錄或測試模型當成雲端模型實測。建議涵蓋知識修復出發、人際協作出發與共同延期，每次都包含自由文字、早期偏好回收、模糊意圖與非法事實宣稱。

啟動 live 模式與 `WORLD_DEBUG=true` 後，可用 `python3 scripts/smoke.py --record live-records.json` 記錄作者分支的模型演出與 trace；**此腳本不取代自由輸入與完整人工遊玩**。

每份紀錄請記下模型 ID、日期、輸入／保存結果、角色語氣與記憶來源、世界一致性、總延遲、模型請求數、token usage、作者備援與任何錯誤。依角色一致性、記憶正確性、故事連貫、選擇可理解性逐項給 1–5 分，記錄失敗例，不只填平均分數。

另外需至少一個實際外部 MCP AI 客戶端的產品與版本紀錄，走完查場景、提交回合、查結果及回 Web 續玩。SDK 協定測試不代替這一項。

2–4 小時的核心體驗與 5–10 次遊玩節奏仍須真人實測；目前事件數量與可達性通過，不能據此推定閱讀時間與故事品質達標。
