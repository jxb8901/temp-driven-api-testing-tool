<!-- att-doc-status: current release=4.2.6 -->

# Schedule API

Schedule 是由 Server 擁有的定義，可於稍後提交 Job，但它不是 Local Run ID；只有 occurrence 被接納後才會建立 Job。

目前 REST route table、request shape、authorization 及 occurrence-history path 定義於 [REST API v1](v1.zh.md#rest-api)。Browser Schedule 畫面請參閱 [Studio Schedules](../studio/schedules.zh.md)。

Package revision policy、保留的 history、overlap limit、shutdown 及 recovery 行為請參閱 [Jobs and Schedules operations](../../operations/server-jobs-schedules.zh.md)。

Legacy path 名稱 /load-schedules 仍受目前 4.2.6 REST API 支援。本 interface 不會宣告 #294 未來 CLI management command 已提供。
