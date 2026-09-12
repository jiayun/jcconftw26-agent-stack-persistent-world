# 實作與驗收紀錄

日期：2026-09-13。依據：`Persistent-Story-World-Implementation-Plan.md`，規劃基準日 2026-09-12。

本 repo 已從空白建立可啟動、可通關、可持續保存的遊戲與 MCP Server。**尚未宣稱整份 M0–M5 驗收全部通過。** 以下區分已實作、已測試與仍需外部條件的驗收。

## 已實作並驗證

- Kotlin／Gradle 四模組、React／TypeScript／Vite、包含前端的 Boot JAR。
- Spring AI 2.0.1、Embabel 1.5.1、Koog 1.2.0 同程序實際執行。Embabel 用 GOAP 搜尋路徑，Koog 用真實 graph，Spring AI 使用 typed output 與唯讀 tools。
- MCP Java SDK 2.0.0、Spring AI WebMVC transport 2.0.1 為正式版；真實 Streamable HTTP initialize、tools/list、五工具呼叫通過。
- 六地點、44 YAML 事件（6／10／16／6／6）、三技能、關係描述、角色日程、兩個解法、出發／延期、結局後日常。
- 五層記憶、承諾固定保留、已知資訊過濾、偏好候選驗證、更正／刪除、來源追蹤。
- H2 file／Flyway／JDBC，request ID 去重、revision 衝突、並行提交、交易快照、已接受／已提交恢復與匯入 round trip。
- Web 與 MCP 共用存檔、授權範圍、撤銷權杖、禁止跨存檔查詢與隱藏資料外洩。
- 實際模型工具續回合也計入最多六次請求；三次唯讀工具，多角色共用 45 秒預算。
- 固定 seed 的 200 回合模擬與四條真實 HTTP 作者路線通過。
- 中文 README、安裝、五分鐘展示、完整遊玩、架構、ADR、MCP、擴充及測試指南；桌面與手機版人工檢查。

## 仍待驗收

| 項目 | 目前狀態／需要的條件 |
| --- | --- |
| 至少三次完整真實模型遊玩 | 本次環境沒有 OPENAI_API_KEY 與 WORLD_MODEL。live adapter 已編譯，使用受控測試模型驗證工具與失敗路徑；尚無真實模型遊玩紀錄。 |
| 外部 MCP AI 客戶端 | Java SDK 協定驗收通過；尚未記錄一個實際外部 AI 客戶端的產品版本與人工操作。 |
| 2–4 小時核心故事、5–10 次遊玩 | 已有全部事件與合法路線；文字深度、節奏及實際時長尚須真人試玩，不能以 44 事件數量推定達標。 |
| 前 15 分鐘／45 分鐘體驗指標 | 流程上可很早回收記憶及形成承諾；尚未取得新玩家的計時紀錄。 |
| 長程自然語言品質 | 世界不變量與有界模擬通過；角色一致性、跨回合語意矛盾與趣味性仍需 live 評估。 |
| 新讀者與內容作者驗收 | 啟動、通關與擴充步驟已提供，尚未由獨立新讀者完成上手與新增事件測試。 |

## 實作界線

目前事件與角色內容採固定作者模板，離線可完成所有合法作者分支；任意自由輸入使用 live 模型。工作記憶保存最近對話，語意偏好候選採保守的明確原文驗證。角色私人作者設定不會整份送進模型。

事件只做本機回合推進，不離線持續呼叫模型。不包含多人、公開部署、戰鬥、A2A 或世界資料館 MCP Client。傳遞依賴中出現框架的其他 API，並不表示遊戲啟用了該功能。

## 驗證證據

執行 `./gradlew test :server:bootJar`、`cd frontend && npm run build`、`python3 scripts/smoke.py` 可重現。詳細場景見 [測試指南](testing.md)，離線作者路線摘要見 [驗證摘要](validation/summary.json)。

版本調整沒有降版或混入預覽版。前端依官方 npm registry 升級到已修補版本並固定 lockfile；JVM runtime 的 BOM 實際解析差異與框架 API 變化已記錄於 ADR。
