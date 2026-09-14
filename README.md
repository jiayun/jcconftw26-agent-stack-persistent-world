# 春祭之約｜JCConfTW26 Agent Stack Supplemental Project

JCConfTW26 Agent Stack 會後補充專案：以可持續保存的單人敘事世界，展示 Java／Kotlin 團隊如何依需求組合 Agent Stack，讓角色記得承諾，也讓玩家的選擇留下可查證的結果。

本專案以 Kotlin 實作，將模型理解與演出、角色目標規劃、回合流程、世界裁決及持久化恢復分成明確責任。讀者可以從封橋改道、非法行動被拒絕，以及中斷後續作等情境，理解每一層解決的問題與引入成本，再判斷自己的應用需要哪些能力。

你暫住河畔小城的旅館，與 Elia、Miro 一起準備春祭。星燈亮錯了拍子，北橋突然封閉，而「祭典結束後一起去北方」的約定，還等著你們一起回答。

這個世界保存共同經歷、偏好、關係與故事進度。模型負責理解與演出，程式負責世界事實。離開不會扣關係，也不會錯過無法補回的每日獎勵。完成春祭後，可以出發、一起延期，或留在小城繼續生活。

目前提供六地點、44 個作者事件、知識修復／人際協作兩條解法、出發／延期收尾，以及本機多存檔。**完整驗收狀態見 [實作與驗收紀錄](docs/implementation-status.md)**；真實模型品質、外部 AI 客戶端與 2–4 小時遊玩節奏尚待驗收，不能以離線測試替代。

## 快速啟動

需求：**JDK 21、Node.js 22.12 以上、npm**。不需要 Docker，不需要先安裝 Gradle。第一次建置需要網路下載正式版套件。

```sh
cd frontend
npm ci
cd ..
./gradlew :server:bootJar
java -jar server/build/libs/server-0.1.0.jar
```

開啟 [本機遊戲](http://127.0.0.1:8080)。預設離線作者模式，不需要金鑰；前端已包入同一個 Boot 程序。也可執行 `./scripts/start.sh`。

本機資料存於啟動目錄下的 `data/`。請在相同目錄啟動以延續存檔。關閉程序前後都可以匯出存檔；不要同時啟動兩個程序讀寫同一資料庫。

模型設定可使用 `.env`：在 repo 根目錄執行 `cp .env.example .env`，將 `WORLD_MODE` 改為 `live`，填入 `WORLD_MODEL`、`OPENAI_API_KEY`；若使用相容服務，可調整 `OPENAI_BASE_URL`。後端啟動時自動讀取，修改後需重啟。完整範例與格式見 [安裝與模型設定](docs/setup.md)。

## 五分鐘展示導覽

首頁選「五分鐘導覽：春祭前夕」。每次都建立**獨立**存檔，包含初識、無糖茶、河風調味笑話、北方承諾與已發現的星燈異常，尚未封橋、完成春祭或取得船票。

| 操作 | 預期結果 | 程式入口 |
| --- | --- | --- |
| 輸入「還記得北方的約定嗎？」 | 回想承諾，附 `promise` 來源；日期、時段不變 | `WorldRules.resolve`、`visibleMemories` |
| 選「去渡口查船班」 | 北橋封閉，旗標及記憶同時保存 | `KoogTurnWorkflow`、`JdbcWorldStore.commit` |
| 再聊天一回合；啟用開發觀察工具後看紀錄 | 封橋前的 `check_bridge` 改成 `ask_ferry → propose_ferry`；若下雨則提出延期 | `EmbabelNpcPlanner.planGoal` |
| 輸入「我已經有船票，直接出發」 | 不增加物品、不移動、不推進時段；提示合法下一步 | `SpringStoryModel.understand`、`WorldRules.resolve` |
| 地圖移動到茶書屋，選比對舊圖；再到市集選分流修復 | 調查、技能證據、關係與春祭結果一致保存 | `content/events.yaml` |
| 重新整理；或完成 MCP 設定後改由外部客戶端提交回合 | 保存的 revision、日誌與記憶延續；Web 定期重新讀取場景 | `GameService`、`McpWorldAdapter` |

如果茶書屋先出現角色或回憶事件，完成它後繼續即可；也可以在市集安排協作，採另一條解法。五分鐘是快速操作目標，雲端等待時間可能增加總時長。一般聊天不消耗時段，但保存對話記憶會增加 revision。

開發觀察工具：啟動時設定 `WORLD_DEBUG=true`，再到設定頁查看上一回合的實際 graph 節點、planner actions、記憶來源、耗時及模型用量。它不顯示模型內部思考，預設不開啟。

## 從第一天完整遊玩

選「從第一天開始」。依序在旅館落腳、舊橋談旅行、市集調查、舊橋面對封閉；茶書屋的資料、守燈人的筆記與市集的人際合作能打開不同解法。完成春祭後，到渡口訂票並商量出發或延期。角色事件的「先聽他把話說完」保留稍後協助的機會。

地圖移動及明確生活行動推進一個時段；聊天、看日誌、查記憶不推進時間。三項技能依**不同事件**累積證據，不靠重複刷同一事件。關係以描述呈現。結局後主線不重開，日常仍可繼續；選擇延期會保留約定。

## 透過 MCP 玩同一個存檔

```sh
WORLD_MCP_ENABLED=true java -jar server/build/libs/server-0.1.0.jar
```

在 Web 的「設定」為目前存檔建立權杖。外部客戶端須支援 **Streamable HTTP** 與自訂 Authorization header，端點是 `http://127.0.0.1:8080/mcp`。工具參數不能自行指定另一個存檔。

先 `get_current_scene`，以回傳的 revision 呼叫 `submit_player_turn`，再用 `get_turn_result` 查保存結果。回 Web 會讀到同一份狀態。完整設定、五工具契約、跨入口操作及失敗處理見 [MCP 使用指南](docs/mcp.md)。首次客戶端設定不計入五分鐘導覽。

「後端離線」表示遊戲不呼叫雲端模型；外部 AI 客戶端自身是否需要網路，取決於該客戶端。自動 MCP 驗收使用真正的 Java SDK 協定客戶端，不需要外部模型。

## 架構導讀

| 遊戲需求 | 架構決策 | 可觀察的結果 |
| --- | --- | --- |
| 理解玩家輸入、生成角色台詞 | Spring AI 統一模型邊界，domain 驗證合法性 | 玩家宣稱已有船票，不會因此取得物品或直接出發 |
| 封橋後重新尋找旅行方案 | Embabel 根據目標與條件搜尋 action path | 橋路不可行時，改查渡船或提出延期 |
| 模糊輸入需澄清、演出失敗需備援 | Koog 明確表達回合分支 | 澄清時跳過規劃與裁決；演出失敗使用同筆作者文字 |
| 重啟後續作、重送不重複效果 | 應用與 JDBC 交易保存請求、世界及結果 | 已提交回合只補演出，同存檔、相同 request ID 與內容取回原回合 |
| Web 與外部客戶端延續同一存檔 | MCP 提供工具介面，伺服器依權杖另行授權 | 兩個入口共用回合服務，客戶端不能自行指定其他存檔 |

這裡同時採用 Spring AI、Embabel 與 Koog，讓讀者能在同一個遊戲中觀察三種責任及其協作成本。只有短回合模型互動時，可以先從 Spring AI 與應用規則開始；需要依條件搜尋行動路徑或明確管理流程分支時，再評估規劃與 graph。具體取捨見 [架構決策](docs/adr/001-framework-and-world-boundaries.md)。

```text
React → HTTP ──┐
              ├→ GameService → Koog graph → Spring AI / Embabel → domain → JDBC / H2
MCP client ───┘                         ↓ 已提交結果
                                  Spring AI 演出 → 保存 → 輪詢
```

| 模組 | 責任 |
| --- | --- |
| `domain` | 純 Kotlin 世界、可見性、事件排程、技能證據、合法裁決 |
| `application` | 型別契約、執行預算、回合用例及儲存介面 |
| `ai-adapters` | Spring AI typed output、Embabel GOAP、Koog 條件分支 graph |
| `server` | Boot HTTP／MCP、存檔權杖、Flyway、JDBC 交易與恢復 |
| `frontend` | React／TypeScript／Vite 的插畫書介面 |
| `content` | 台灣繁體中文角色聖經與 44 個 YAML 事件 |

[一次回合的架構](docs/architecture.md) · [架構決策](docs/adr/001-framework-and-world-boundaries.md) · [內容與擴充指南](docs/development.md)

角色記憶保存「知道什麼」，回合紀錄保存「執行到哪裡、哪些效果已提交」。本版由應用與 H2 支援本機單程序的回合恢復；恢復情境與限制見 [測試與復原](docs/testing.md)。角色都在同一服務內運作，沒有獨立部署 agent 的協作需求，因此未引入 A2A。

開發觀察工具與回歸測試用來檢查分支、記憶來源、模型用量及世界不變量。本專案的驗證範圍是本機單人應用；公開部署的身分治理、維運與完整人工模型品質驗收仍須另行評估，三框架共同執行成功不等於完成 production 驗證。

## 開發與測試

```sh
./gradlew test
# 前端開發，另一個終端執行：
./gradlew :server:bootRun
cd frontend
npm ci
npm run dev
```

開啟 [前端開發介面](http://127.0.0.1:5173)。Vite 代理到 loopback 的 8080 後端。正式模式仍只啟動 Boot。

`./gradlew test` 包含三框架執行、真實 MCP Streamable HTTP 交握、五工具、跨入口去重、存檔授權、恢復、四種主線路線及 200 回合模擬。`npm run build` 執行 TypeScript 與正式前端建置。

[安裝與模型設定](docs/setup.md) · [測試與復原](docs/testing.md) · [實作與驗收紀錄](docs/implementation-status.md)

授權：Apache-2.0。場景插畫為此專案的原創 SVG，沒有遠端字型、追蹤碼或外部圖片請求。

存檔的搜尋、匯出及批次刪除操作見 [存檔管理](docs/saves.md)。
