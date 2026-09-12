# 擴充與內容創作

## 新增事件

編輯 `content/events.yaml`，每個事件須有全域唯一 ID、category、title、place、text、participants 與 2–4 個 branches。category 為 main／character／life／memory／atmosphere；地點與 Place enum 一致。

```yaml
- id: example_cup
  category: life
  title: 杯沿的一圈茶漬
  place: TEAHOUSE
  text: 桌上的茶杯留下淺淺一圈痕跡，店主遞來一塊布。
  participants: []
  priority: 15
  cooldown: 3
  once: true
  lockedFacts: []
  generationScope: 只可補充表情與動作，不可增加物品或世界效果。
  hooks: []
  branches:
    - id: help
      label: 幫忙整理茶桌
      text: 你擦乾桌面，替下一位客人留下一個舒服的位置。
      skill: observation
      memory: 你在茶書屋幫忙整理茶桌。
    - id: listen
      label: 先聽店主說茶杯的故事
      text: 店主說，這個杯子已經陪過許多春天。
```

`chapter` 精確限定章節，`minChapter` 設定下限，`require` 全部成立才觸發，`exclude` 任一成立就互斥。`once` 與 `cooldown` 控制重複；同分以 seed 決定，未見過事件與等待時間影響優先度。`hooks` 僅引用存在的作者事件，不直接跳過世界條件。

branch 的 flags、skill、character、trust、closeness、memory、layer、pinned、ending 是明確效果。技能只記錄事件 ID，不能靠同一事件反覆升級。`listen` 保留稍後協助的機會，不標記角色事件完成。主線完成以 `advanceChapter` 控制；已完成主線不再符合章節。

新增內容時同步調整 content version、內容數量測試與可達性測試。不要只為讓測試通過刪除驗收斷言；測試須涵蓋新增前置條件造成的卡關。

## 角色與技能

角色公開語氣及私密設定在 `content/characters.yaml`。私密段落是作者資料，不能整份送給不知情角色。角色模型上下文只使用明確聲線與自身 knownBy 記憶。新增角色需同時調整 World 初始關係、日程、事件參與者與 UI 的人物卡；加上「其他角色不知道其秘密」測試。

新增技能需調整 World、WorldRules 的允許清單、匯入驗證與 UI 名稱。每項技能的不同 evidence 數量決定初識／熟悉／拿手。後期分流修復會反映累積的生活練習，結局回收早期笑話或熱湯。

## 新增 Embabel action

在 `EmbabelNpcPlanner.planGoal` 對應目標的 actions 集合加入 `ConditionAction`，定義 preconditions、effects 與 cost。不要先用 if/else 排好完整路徑，再把清單叫作 planner 結果。

每個 goal 的測試須至少改變一項真實條件，驗證 planner 選出的 action IDs 確實改變。若 action 會提交 NPC 活動，另在 `WorldRules.advance` 註冊允許動作與目前條件。NPC 活動不能替玩家買票、出發或答應邀約。

## 台灣繁體中文規範

使用「專案、程式、檔案、資料、設定、預設、非同步、資訊、存檔、權杖」。識別字、schema 欄位與工具名稱維持英文。語氣溫暖、帶生活感，避免把延期寫成失敗或用關係扣分催促回訪。

世界真相固定為舊星燈設施與今年祭典配置不相容；模型不可改成詛咒、陰謀或臨時出現的敵人。自然語言不得把提案說成已完成。每個重要承諾要有可查來源，不只藏在聊天文字。

## 驗證入口

`./gradlew test` 會載入 YAML、驗證 Kotlin schema、ID／hook 引用、類別數量與主線可達性。修改 API 後執行 MCP 交握與跨入口測試。修改前端後執行 `npm run build`，並人工檢查桌面及手機版、鍵盤焦點、錯誤與忙碌狀態。
