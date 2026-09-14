# 透過 MCP 玩同一個存檔

## 連線

先建立 Web 存檔，再以 `WORLD_MCP_ENABLED=true` 啟動遊戲。設定頁「建立連線權杖」只授權目前存檔，權杖只顯示一次。可以為不同存檔建立不同權杖，也可以分別撤銷。

客戶端必須支援 Streamable HTTP 與自訂 header；下面是 **連線參數參考**，不是所有客戶端共用的設定檔 schema：

```json
{
  "transport": "streamable-http",
  "url": "http://127.0.0.1:8080/mcp",
  "headers": { "Authorization": "Bearer REPLACE_WITH_SAVE_TOKEN" }
}
```

不要將權杖放入模型提示、工具參數、issue 或紀錄。此版沒有帳號系統；存檔管理由 Web 處理。MCP 權杖不授權任意匯出、刪除、重設、好感修改或資料
patch。

## 一次跨入口操作

1. 在 Web 建立「春祭前夕」範例，聊聊約定。
2. 建立目前存檔的權杖，依客戶端自己的格式設定連線。
3. 連線完成後呼叫 `get_current_scene`；記住 `revision` 與 `suggestions`。
4. 選一個合法 suggestion ID，提交唯一 request ID。例如以下 `expectedRevision` 必須換成剛讀取的實際值：

```json
{
  "requestId": "example-turn-001",
  "expectedRevision": 1,
  "suggestionId": "event:closure:ferry"
}
```

5. `submit_player_turn` 立即回傳 turn ID 與狀態，接著使用 `get_turn_result` 輪詢。
6. 回 Web，場景會定期讀取已提交 revision；重新整理也會恢復上一回合的保存演出。

如果要自由輸入，改用 `text`，不可同時提供 suggestion ID。離線後端接受固定文字範圍；外部 AI 自行產生的補充敘述不會成為遊戲事實。

## 五個工具

| 工具                    | 參數                                                         | 契約                                                       |
|-------------------------|--------------------------------------------------------------|------------------------------------------------------------|
| `get_current_scene`     | 無                                                           | 授權存檔場景、revision、在場角色、2–4 個合法建議與公開事實 |
| `get_story_journal`     | 可選整數 `cursor`                                            | 每頁 20 筆已知事件、公開承諾與 nextCursor                  |
| `search_known_memories` | `query`                                                      | 玩家已知記憶，最多六筆，包含 sourceId                      |
| `submit_player_turn`    | `requestId`、`expectedRevision`、`text`／`suggestionId` 擇一 | 持久化請求後立即回傳 turnId；共用 Web 用例                 |
| `get_turn_result`       | `turnId`                                                     | 相同授權存檔的進度及完整保存結果                           |

工具參數不接受 save ID、角色 ID、視角或任意 patch。唯讀工具不推進時段或 revision。不回傳作者未公開事件、NPC 秘密、開發 trace
或原始世界快照。

## 重送、中斷與錯誤

- 相同存檔、相同 request ID、相同內容：取得原回合。Web 與 MCP 間也成立。
- 相同 ID 改內容：`CONFLICT`。不能用同一 ID 提交第二個行動。
- revision 過期：`CONFLICT`。重新讀場景再選擇，不可自動把舊行動改成新 revision。
- 外部客戶端斷線：遊戲不取消已接受回合。重連後查原 turn ID，或原封不動重送原 request ID。
- 程序重啟：重新執行已接受但未提交的請求；已提交回合只補敘事，不重做效果。
- 無效或撤銷權杖：HTTP 401。跨存檔 turn ID 回 `NOT_FOUND`；不揭露另一存檔是否存在。
- 非法工具欄位：`INVALID_INPUT`。世界不會被改寫。

測試中的客戶端是 MCP Java SDK 2.0.0 的 `HttpClientStreamableHttpTransport`，真實完成 initialize、tools/list 與 tools/call。
**實際外部 AI 客戶端的產品／版本／操作紀錄尚待人工驗收**，不能將 SDK 測試冒充該項完成。

## 與未來世界資料館的差別

本版是「遊戲世界 MCP Server」，外部客戶端透過它遊玩現有存檔。未來可以增加獨立的地方文獻 MCP Server，由遊戲的 Spring AI MCP
Client 查資料；那是內容來源擴充，不在本版範圍。
