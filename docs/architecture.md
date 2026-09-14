# 一次輸入如何成為世界的一部分

玩家的輸入會依序經過模型理解、必要的角色規劃、世界裁決、交易提交與演出。各層的輸出都有下一層要檢查的契約：模型提供意圖，planner 提供候選路徑，domain 決定合法效果，儲存層記錄已發生的結果。需求與選型的對照見 [架構導讀](../README.md#架構導讀)。

## 先保存請求，再執行回合

HTTP 與 MCP 都轉成 `TurnCommand`。`GameService.submit` 先驗證 text／suggestionId 擇一，再由 `JdbcWorldStore.accept` 鎖定存檔列：同 request ID、同內容回傳原回合；同 ID 不同內容、過期 revision、同存檔另一個未完成回合回傳衝突。請求交易完成後才排入虛擬執行緒，adapter 立即回傳 turn ID。

程序重啟時，`recover` 掃描 ACCEPTED 與 COMMITTED。ACCEPTED 由已保存命令重新執行；COMMITTED 只補演出。執行緒內集合只用於避免本程序重複排程，不是世界的真相來源。

這是本機單程序、同一資料庫內的回合恢復契約，由 application 與儲存層承擔。Koog graph 表達執行路徑，角色記憶提供上下文；恢復時則依已保存的回合狀態決定續作位置。長時間等待、跨服務副作用、計時與補償不在本版恢復範圍；若加入這些需求，需另行設計持久化執行契約。各中斷點的行為見 [失敗矩陣](testing.md#失敗矩陣)。

## Koog 控制分支

`KoogTurnWorkflow` 使用 Koog 1.2.0 的 `strategy`、自訂 `node` 及條件 edge，實際以 `GraphAIAgent.run` 執行。

1. 讀取已提交世界與玩家可見記憶。
2. Spring AI 理解輸入；模糊意圖走 clarification edge，跳過 planner 與世界裁決。
3. Embabel 根據 revision 與角色可見條件規劃三類目標。
4. `WorldRules.resolve` 只接受目前事件的合法 branch、移動、休息與受限聊天記憶。
5. 交易提交世界文件、事件／日誌、技能證據、驗證過的記憶、計畫及狀態差異。
6. 根據已提交結果演出。多角色各取自己的記憶，分別呼叫 Spring AI，共用預算。
7. 失敗走 author-fallback edge。保存 `TurnResult` 後，HTTP／MCP 輪詢才回傳完整結果。

Koog 不直接建立模型客戶端。它的 executor 在有人繞過 Spring AI 呼叫模型時立即失敗。沒有採用 beta starter、Koog planner 或外部 durable workflow。

## Embabel 真正選路

`EmbabelNpcPlanner` 透過 `DefaultPlannerFactory` 建立正式 GOAP planner，交給它 action 集合、preconditions、effects、cost 與目標。

- 旅行：橋路檢查，或詢問渡船再提案；不可行時提出延期。
- 調查：開館時查舊圖並比對；夜間訪問守燈人再檢查設備。
- 春祭：已知真相時準備工具與修復提案；沒有知識時尋求助手並規劃協作。

程式的分支只選「目標的 action 集合」，完整路徑由 planner 搜尋。測試比對真實 actions。`NpcPlan` 保存來源 revision、條件、action IDs 與下一步。Domain 再檢查 revision 與目前合法性，每個 NPC 每次推進最多保存一項自己的活動；提案不會替玩家取得物品或接受旅行。

## Spring AI 理解與演出

Spring AI 2.0.1 的 ChatClient、ChatModel 與 BeanOutputConverter 統一模型邊界。live 使用 OpenAI provider；離線 ChatModel 確實經過 typed output，再以作者文字呈現。模型只回傳 `PlayerIntent`、偏好候選與 `NarrativeDraft`，不接受 patch。

偏好候選必須是玩家本回合完整原文、以「我喜歡」開頭且長度受限，domain 才會保存；不能由模型推論新的承諾或物品。模型另可動態呼叫 `read_public_scene`、`read_known_memories` 與 `read_known_events`。這些 Spring AI tools 在註冊之前就完成角色過濾，沒有任何寫入操作或視角覆寫參數；每次執行都通過 `TurnBudget.tool()`。理解階段只提供公開場景，演出階段才加入各角色可見記憶。

總預算 45 秒、最多六次模型請求與三次唯讀工具，每階段最多兩次嘗試；SDK 關閉隱式重試。每次呼叫受剩餘總時間限制。BudgetAdvisor 位於工具迴圈內的模型前方，工具續呼叫也計入六次限制。Schema 只保證結構；自然語言一致性仍須真實模型評估。重要世界事實另由 UI 顯示。

## 角色記憶：知道什麼、依據是什麼

五層記憶共用具來源 ID、knownBy、importance、pinned 與 correction 的資料型別。WORKING 最近八筆；其他非固定記憶合計最多一百筆，重要承諾獨立保留。查詢先過濾角色權限，再依問題相關性與有效版本選出最多六筆；具體檢索與更正規則見下方「記憶與演出修正」。向量搜尋未納入本版。

記憶用來回答角色知道哪些共同經歷、某項偏好從何而來。回合是否已提交，則由持久化的請求與結果判定；不能從一段角色對話推定某個世界效果已執行。

## 世界與回合狀態：哪些效果已提交

`World` 是同一存檔交易的一致性邊界，存為 H2 CLOB JSON snapshot；世界內的角色、關係、技能、事件、故事線及五層記憶一起更新。關聯表另保存 durable turns、前後狀態差異與權杖。這避免在 MVP 為每個子系統建立大量 JOIN，但大型存檔會增加序列化成本。

`world_changes` 保存每回合前後 revision 與完整狀態。已提交回合有結果快照，之後查舊回合不會冒充最新世界。記憶編輯會增加 revision，且禁止與未完成回合交錯。

## 兩個入站 adapter

Web 可管理本機存檔與 MCP 連線。MCP 只暴露五工具。Filter 在每個 HTTP 請求驗證 Bearer hash，再將伺服器決定的 save ID 放進 MCP transport context；不是依賴 ThreadLocal，也不信任工具參數的存檔身分。每個工具依 context 限定查詢；回傳結果移除 developer trace 與原始 outcome。

loopback Host 與同源 Origin 檢查阻擋網頁跨站寫入。權杖只顯示一次、資料庫只留 SHA-256、可以撤銷。設定與工具記錄不包含權杖。

MCP 負責工具探索與呼叫介面；存檔授權、輸入驗證、去重及世界裁決由伺服器執行。MCP 入口先驗證存檔權杖，再與 Web 共用回合輸入驗證、去重及世界裁決；外部客戶端提交的文字仍須通過同一套裁決。

## 觀察與評估的範圍

`WORLD_DEBUG=true` 時，Web 可查看回合的 graph 節點、planner actions、記憶來源、耗時、模型與工具請求數、token usage 及作者備援。這些是本機回合的開發紀錄，用來解釋實際執行與排查問題；完整部署環境的跨服務追蹤、告警及維運指標須另行設計。

自動化測試檢查合法效果、角色可見性、去重與恢復等契約；live 回歸記錄模型在具體輸入下的演出。角色一致性、跨回合語意及遊玩品質仍需人工評估。各類證據與未完成項目見 [實作與驗收紀錄](implementation-status.md)，不以結構化輸出或離線測試推定自然語言品質。

## 記憶與演出修正（2026-09-13）

`MemoryRecall` 統一供 domain、角色唯讀工具及 MCP 使用。先依 `knownBy` 過濾，再以飲料、食物、旅行、祭典等主題別名與文字重疊評分，依更新 revision 和原始插入順序判斷新舊。同一飲食偏好主題取最近有效的一筆；`pinned` 只影響保存，不強迫排在問題相關性之前。這是可追查的詞彙檢索，不宣稱是一般語意理解；無關問題可以得到空結果。

WORKING 對話保留於存檔，但不作為已成立的記憶事實輸入模型。`updatedRevision` 為向後相容的選填欄位，舊存檔預設 0。更正／刪除飲食偏好會移除玩家可見的同主題舊副本，防止刪掉最新記憶後舊版本又成為答案。新的明確偏好仍可重新保存。更正後送給模型的文字只含有效版本；管理 API 仍可顯示原文與更正資訊。

`NarrationContext` 將玩家原文、選擇的行動、作者限制分開傳遞。事件演出優先提供當前位置、結局及本次提交結果；相關記憶附類型、來源和更新資訊。恢復已提交回合時仍使用原始 `TurnCommand`，不重新裁決。事件結果先顯示作者文字，再補角色台詞，避免模型省略關鍵情節。

角色聲線與公開目標來自 `characters.yaml`，私人作者設定不送出。聲線包含善意底色、玩笑界線及僅供學習口吻的例句。基本演出檢查涵蓋已觀察到的威脅語氣、捏造備好的飲品、已抵達卻回到行前規劃、飲食偏好答非所問及無記憶時未澄清；失敗沿用原有兩次嘗試與同回合預算，仍失敗就使用同筆作者文字。這些檢查不能保證所有自然語言語意正確，仍需人工抽查。

## 存檔刪除

`POST /api/saves/delete` 接受 `{ "saves": [{ "id": "存檔 ID", "expectedRevision": 0 }] }`，成功回傳 `deletedIds`。批次限制為 1–1000 個不重複 ID；空清單、重複 ID 或無效版本為 400，任一存檔不存在為 404，版本改變或有 ACCEPTED／COMMITTED 回合為 409。

JDBC 在同一交易按 ID 固定順序鎖定全部選取存檔，與回合接受／提交及記憶更正共用列鎖。先完整驗證，再依外鍵順序刪除 world_changes、turns、connection_tokens、saves；任一失敗整批回滾。現有資料表即可支援，不需資料庫遷移。刪除後舊連線權杖失效，既有 MCP 請求也無法取得已刪除世界。

Web 確認畫面固定保留選取時的名稱與 revision。刪除目前存檔時，清除該存檔的本機待處理請求與畫面快取，切換其餘存檔或回到開始畫面；啟動時也會處理 localStorage 指向已不存在存檔的情況。
