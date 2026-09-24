# Direct DB Action timeout and query retry

Issue #39 extends direct `type: db` Actions with Action-level timeout and query-only retry while preserving the existing DBHelper transaction and evidence model.

## Timeout

Both direct `query` and `update` Actions may declare `timeoutMs` from 1 to 3,600,000 milliseconds. The DB executor applies the shorter effective limit between the Action timeout and the selected DBHelper `statement.timeoutSeconds`. Each retry attempt owns a fresh Action timeout; `retry.intervalMs` is a delay between attempts and is not counted inside that timeout.

```yaml
actions:
  findOrder:
    type: db
    db: orders
    timeoutMs: 1500
    query:
      sql: select status from orders where id = :id
      parameters:
        id: "${EXEC.INPUT.orderId}"
```

Timeout evidence remains DB evidence. No new public DB Context namespace is introduced; the final result remains `${output.result}` while the Action is executing and `${EXEC.ACTIONS.<actionId>.output.result}` after publication.

## Query retry

A direct DB `query` may use the same retry shape as a Tool Action. Supported categories are `ASSERTION` and `TIMEOUT` only.

```yaml
actions:
  waitForOrder:
    type: db
    db: orders
    timeoutMs: 1500
    query:
      sql: select status from orders where id = :id
      parameters:
        id: "${EXEC.INPUT.orderId}"
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

`maxAttempts` must be 2–10, `intervalMs` must be 0–3,600,000, and `retryOn` must be a non-empty unique list. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal and are not retried.

For a retry-enabled DB Action, each attempt is recorded under `output.attempts[n]`, including that attempt's result and DB evidence. Intermediate attempts may carry `retryReason: ASSERTION` or `retryReason: TIMEOUT`. `winningAttempt` identifies a successful attempt; `finalAttempt` identifies terminal failure/exhaustion. The top-level `output.result` and `output.evidence` always represent the final/winning attempt, matching the existing Tool Action lifecycle.

## Asynchronous API submission followed by DB polling

A common SIT/UAT flow is to submit an asynchronous API request and then poll the transaction table until the back-end process reaches its terminal state. The API remains a Tool Action; the polling operation can now stay a direct DB Action instead of being wrapped in another Tool solely to obtain retry behavior.

```yaml
actions:
  submitPayment:
    type: tool
    call: >-
      #{payments.submit(requestFile=${EXEC.INPUT.requestFile})}
    assert: "#{${output.result.accepted} == true}"

  waitForCompletion:
    type: db
    db: payments
    timeoutMs: 3000
    query:
      sql: >-
        select status, reject_code
        from payment_txn
        where ref_no = :refNo
      parameters:
        refNo: "${EXEC.INPUT.refNo}"
    assert: >-
      #{${output.result.rowCount} == 1
        and ${output.result.rows[0].STATUS} == 'COMPLETED'}
    retry:
      maxAttempts: 8
      intervalMs: 1000
      retryOn: [ASSERTION, TIMEOUT]
```

Here an empty row set or a non-`COMPLETED` status is an assertion failure and may be polled again. A timed-out query may also retry. A syntax/permission/connection SQL error is terminal instead of being hidden by generic retry.

## Update safety

Direct DB `update` supports `timeoutMs` but deliberately rejects `retry`. After a timeout or transport/database failure, ATT cannot in general prove whether a mutating statement committed, rolled back, or reached the server. Automatic replay could therefore duplicate a business mutation. Authors who need application-specific idempotent retry must model that explicitly rather than enabling generic DB Action retry.
