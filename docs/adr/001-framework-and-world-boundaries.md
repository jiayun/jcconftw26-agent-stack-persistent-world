# ADR 001：三框架與世界權限邊界

狀態：採用。日期：2026-09-13。

## 決策

以模組化單體實作。Domain 不依賴 AI；application 定義回合與儲存介面；Spring AI 負責模型、Embabel 負責目標導向規劃、Koog 負責外層回合
graph。HTTP 與 MCP 是相同 application service 的兩個入口。

這個選擇讓讀者能觀察三種不同責任：型別化模型輸出、條件改變後的規劃路徑、包含澄清與失敗的控制流程。規劃與敘事不能修改世界，交易必須通過
domain。

## 需求與可簡化的選擇

- 輸入理解與角色演出需要一致的模型介面及型別契約，因此集中在 Spring AI。若應用只有短回合互動與有限工具查詢，可先保留這一層和
  domain 規則。
- 旅行、調查與春祭目標會因橋路、時間及已知資訊改變可行路徑，因此使用 Embabel 搜尋 actions。若所有路徑固定，可由應用明確編排，不必引入
  GOAP。
- 回合包含澄清、正常提交、演出備援與提交後恢復入口，因此使用 Koog 表達可測的分支。若流程簡單，也可以用一般應用程式控制流程；本專案採
  graph 是為了讓節點與分支可直接觀察。

## 重疊與代價

三者都有 agent、tools 或流程概念，功能確實重疊。採用全部三者會增加依賴體積、版本協調、API 遷移、測試矩陣與排錯成本。正式建置已證明此組合可啟動，不代表所有
agent 都應使用三框架。單純聊天應用可能只需 Spring AI，只有固定步驟的應用也未必需要 GOAP。

## 持久化與失敗

H2 file、Spring JDBC、Flyway 不需 Docker。本專案的 durable execution 由應用與資料庫保存請求／世界／結果來實作，未採用框架的
checkpoint 或持久化機制。request ID 去重，revision 比較與列鎖避免覆寫。提交後生成失敗使用作者文字，不能把同一效果再執行一次。

恢復範圍限定於本機單程序與同一資料庫內的回合，詳見 [失敗矩陣](../testing.md#失敗矩陣)。目前沒有跨服務寫入、長時間人工批准或
timer 的需求，因此未加入外部 durable workflow engine。若需求擴大，需重新評估保存粒度、等待與取消、遠端結果查核及補償，不能直接沿用本版的恢復保證。

五層記憶先做結構化與文字檢索，保留可替換介面。重要承諾不隨一般對話淘汰。修正記憶是一個新的 revision，不是時間倒流。

## MCP

採 Spring AI 正式 Streamable HTTP server。外部客戶端可以提交玩家行動，但不能 patch、改好感、匯出原始存檔或指定 NPC
私密視角。未來「世界資料館」MCP Client 是不同的內容來源擴充，不在這個 server 的權限內。

採用 MCP 是為了讓不同客戶端重用相同遊戲能力。存檔權杖、角色可見性、輸入驗證與 domain 裁決由伺服器另外實作。所有 NPC
都在同一服務內，沒有獨立部署 agent 的任務協作需求，因此不採用 A2A。

## 版本證據

開工重新讀取 Maven Central metadata
與正式發布。三框架正式基線為 [Spring AI 2.0.1](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.1)、[Embabel 1.5.1](https://github.com/embabel/embabel-agent/releases/tag/v1.5.1)、[Koog 1.2.0](https://github.com/JetBrains/koog/releases/tag/1.2.0)
。配套 Boot 4.1.1、Kotlin compiler 2.3.10、Gradle 9.0.0、JDK 21。

實際解析的 MCP Java SDK 2.0.0 及 `mcp-spring-webmvc` 2.0.1 為正式版。Boot BOM 將 Kotlin runtime 解析到 2.3.21，編譯器仍為指定
2.3.10；共同編譯、執行與序列化測試通過。沒有改用預覽版或降版；全部可解析相依版本見各模組 `gradle.lockfile`。

Spring AI 2.0.1 已採 OpenAI SDK 式 options builder，與舊版 `OpenAiApi` 設定不同。本專案使用已編譯驗證的新 API。

Flyway 啟動時提示 H2 2.4.240 新於其宣告已驗證的 2.3.232。本專案依 Boot BOM 保留正式版，並通過實際遷移、交易與 H2 file
關閉重開測試；此結果不擴大 Flyway 官方的相容性宣告。
