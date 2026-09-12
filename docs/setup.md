# 安裝、設定與資料保存

使用 JDK 21；以 `java -version` 確認。Gradle Wrapper 固定 9.0.0，Kotlin 編譯器固定 2.3.10；建置會明確要求 Java toolchain 21，不會把本機其他版本默默當成相容版本。

前端使用 Node.js 22.12 以上。先 `cd frontend && npm ci`，再回根目錄執行 `./gradlew :server:bootJar`。產物為 `server/build/libs/server-0.1.0.jar`，用 `java -jar` 啟動。Windows 可使用 `gradlew.bat`。

## 環境設定

| 名稱 | 預設 | 說明 |
| --- | --- | --- |
| `WORLD_MODE` | `offline` | `offline` 或 `live` |
| `WORLD_MODEL` | 空白 | live 必填的 OpenAI 模型 ID |
| `OPENAI_API_KEY` | 空白 | live 必填；不寫入 repo、存檔、MCP 工具參數 |
| `WORLD_DATABASE_URL` | `jdbc:h2:file:./data/spring-festival;DB_CLOSE_ON_EXIT=FALSE` | H2 file JDBC URL |
| `WORLD_MCP_ENABLED` | `false` | 啟用同程序 `/mcp` Streamable HTTP |
| `WORLD_DEBUG` | `false` | 顯示開發觀察工具 |
| `PORT` | `8080` | HTTP 與 MCP 共用連接埠 |

`.env.example` 是可複製的設定參考；程式不自動執行 `.env`，請透過終端、IDE 或程序管理工具注入環境變數。

```sh
export WORLD_MODE=live
export WORLD_MODEL=REPLACE_WITH_MODEL_ID
export OPENAI_API_KEY=REPLACE_WITH_API_KEY
java -jar server/build/libs/server-0.1.0.jar
```

live 缺少任一設定會拒絕啟動並說明原因。離線模式涵蓋全部作者事件的合法分支、移動、休息、回想約定與「我喜歡……」的明確偏好；不宣稱能理解任意自由文字。兩個模式都真正執行 Embabel planner、Koog graph、domain 與 H2。

## 資料與失敗

H2、Flyway 與回合紀錄都保存在本機。live 會將輸入、場景與角色已過濾的記憶傳給 OpenAI；離線不傳送。工具不開放任意世界 patch。自然語言仍可能不一致，請以畫面中的結構化事實與日誌為準。

備份優先使用設定頁匯出。若要備份整個 H2 檔案，先停止程序，再複製 `data/`。匯入建立新 UUID 存檔，原存檔不變，也不帶入既有 MCP 權杖或執行中的回合。記憶更正不逆轉已發生的故事。

收到 revision 衝突時，重新讀取場景再選新行動；不要自動替舊行動改 revision。若網路中斷，畫面的「重新查詢原回合」會使用保存的 request ID 或 turn ID。

## 疑難排解

- 8080 被占用：設定 `PORT`；前端開發的 Vite proxy 也需改成同一連接埠。
- 找不到 JDK 21：設定 `JAVA_HOME` 指向正式安裝的 JDK 21，再檢查 `./gradlew --version`。
- H2 被鎖住：停止另一個使用相同資料庫的程序，不要刪鎖檔強行啟動。
- localhost 沒畫面：正式模式先 `bootJar`；`bootRun` 主要供前後端分開開發，請使用 5173。
- 模型逾時：已提交世界仍在；會用同一事件的作者文字備援，不重複效果。
- 在其他電腦連不到：此版預設且設計為 loopback 單人遊戲，非公開部署服務。
- 存檔版本不相容：保留原始匯出檔，使用對應版本開啟；目前只接受 schema 1／content `spring-festival-1`。
