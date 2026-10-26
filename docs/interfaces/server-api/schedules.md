<!-- att-doc-status: current release=4.2.4 -->

# Schedule API

Schedules are Server-owned definitions. They can submit Jobs later, but they are not Local Run IDs and do not create a Job until an occurrence is accepted.

The current REST route table, request shape, authorization and occurrence-history paths are defined in [REST API v1](v1.md#rest-api). The browser Schedule screens are described in [Studio Schedules](../studio/schedules.md).

Use the Server operational guide for package revision policy, retained history, overlap limits, shutdown and recovery behavior: [Jobs and Schedules operations](../../operations/server-jobs-schedules.md).

The legacy path name /load-schedules remains supported for the current 4.2.4 REST API. The interface does not expose future CLI management commands from #294.
