# Local Runs and Server Jobs

A Local Run ID and a Server Job ID describe different lifecycles.

## Local Run IDs

The Local Engine assigns or accepts a Run ID for local execution output. Run and Load evidence is written under the configured local output root. The ID groups local results; it is not a persistent Server record, does not support Server event replay and cannot be managed through Server Job endpoints.

## Server Jobs

ATT Server accepts an authorized execution request and creates a durable Job record with a Server Job ID. The Job owns its status, events, result and retained artifacts. ATT Remote follows the accepted Job by SSE and retrieves the final result. A Local execution does not create a fake Server Job.

## Schedules

A Schedule is a Server-owned definition that can submit later Jobs. Its definition, recurrence and occurrence history are separate from any one Job. Schedules retain the validated input and revision policy required by the Server contract; changing the source may require revalidation.

In 4.2.6, use the current legacy ATT Remote commands or Studio to manage Server Jobs. CLI vNext and its future interface-specific options remain [Planned](../interfaces/cli/vnext-proposed.md). Future #294 management commands are not part of that proposal.

See the [Server Job API](../interfaces/server-api/jobs-and-events.md), [Schedules API](../interfaces/server-api/schedules.md) and [legacy Remote guide](../remote-client.md).
