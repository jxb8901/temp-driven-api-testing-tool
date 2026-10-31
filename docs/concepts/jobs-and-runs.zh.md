# Local Run 與 Server Job

Local Run ID 與 Server Job ID 代表不同的 lifecycle。

## Local Run ID

Local Engine 會為本機執行分配或接受 Run ID，並將執行輸出寫入指定的本機 output root。此 ID 用於分組本機結果；它不是持久化 Server record，不支援 Server event replay，也不能透過 Server Job endpoint 管理。

## Server Job

ATT Server 接受已授權的執行 request，並建立帶有 Server Job ID 的持久化 Job record。Job 擁有自己的 status、event、result 及保留的 artifact。ATT Remote 透過 SSE 追蹤已接納的 Job，並取得最終結果。本機執行不會建立假的 Server Job。

## Schedule

Schedule 是由 Server 擁有、可於稍後提交 Job 的定義。其定義、recurrence 及 occurrence history 均與單一 Job 分開。Schedule 會保留 Server contract 所需的 validated input 及 revision policy；source 變更時可能須重新 validation。

4.2.8 使用現有舊版 ATT Remote command 或 Studio 管理 Server Job。CLI vNext 及其未來 interface option 仍屬[規劃中](../interfaces/cli/vnext-proposed.zh.md)。未來 #294 management command 不屬於該 proposal。

請參閱 [Server Job API](../interfaces/server-api/jobs-and-events.zh.md)、[Schedules API](../interfaces/server-api/schedules.zh.md) 及 [舊版 Remote 指南](../remote-client.zh.md)。
