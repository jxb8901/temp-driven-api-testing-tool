<!-- att-doc-status: current release=4.2.6 -->

# ATT Server API

The public Server API is versioned under /api/v1. These pages route readers by task and keep deployment policy outside the API contracts.

| Task | Contract |
|---|---|
| Check core routes, Jobs, auth and endpoint conventions | [REST API v1](v1.md) |
| Browse registered Package resources and shared configuration | [Package inspection](packages.md) |
| Validate RUN, Debug and LOAD inputs and submit work | [Execution APIs](execution.md) |
| Read events, SSE, results and cancellation behavior | [Jobs and Events](jobs-and-events.md) |
| Create, review or operate Schedules | [Schedules API](schedules.md) |
| Read the proposed synchronous validation contract | [Validation API (planned, #287)](validation.md) |

Tomcat deployment, Server configuration and authentication operations are documented under [Operations](../../operations/server-deployment.md).
