<!-- att-doc-status: current release=4.2.4 -->

# ATT Server API

Public Server API 以 /api/v1 為版本根目錄。以下按任務導覽，並將部署政策放在 API contract 以外。

| 任務 | Contract |
|---|---|
| 查看核心 route、Job、auth 及 endpoint 慣例 | [REST API v1](v1.zh.md) |
| 瀏覽已註冊 Package resource 及 shared configuration | [Package inspection](packages.zh.md) |
| 驗證 RUN、Debug、LOAD input 並提交工作 | [Execution API](execution.zh.md) |
| 讀取 event、SSE、result 及 cancellation 行為 | [Jobs and Events](jobs-and-events.zh.md) |
| 建立、審閱或操作 Schedule | [Schedules API](schedules.zh.md) |
| 查看擬議的同步 validation contract | [規劃中的 Validation API（#287）](validation.zh.md) |

Tomcat deployment、Server configuration 及 authentication operation 請參閱 [Operations](../../operations/server-deployment.zh.md)。
