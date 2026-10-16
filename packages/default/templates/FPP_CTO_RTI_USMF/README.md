# FPP_CTO_RTI_USMF data contract

This template implements the photographed USMF CTO success plus RTI success flow:

1. send CT001 Precheck and verify its transaction-log keyword;
2. send CT001 Confirm and verify its transaction-log keyword;
3. poll CTO ACSC/ACCC state, then verify Pacs002 ACSC and ACCC logs;
4. simulate Pacs004 under the environment's configured FPS Console condition;
5. poll RTI success and amount;
6. poll the specified CTO/OID until refunded.

## CASE data

API inputs:

- `precheckRequestId`, `precheckRequestType`, `precheckRequestFile`
- `confirmRequestId`, `confirmRequestType`, `confirmRequestFile`
- `pacs004RequestId`, `pacs004RequestType`, `pacs004RequestFile`
- `apiLogPath`, `apiSuccessCode`

Log inputs:

- `transactionLogFile`, `precheckLogKeyword`, `confirmLogKeyword`
- `pacs002AcscLogFile`, `pacs002AcscKeyword`
- `pacs002AcccLogFile`, `pacs002AcccKeyword`

Database and evidence inputs:

- `artifactDirectory`, `amount`
- `ctoAcscAcccSqlFile`, `ctoAcscAcccExpectedCount`
- `rtiTxnSqlFile`, `rtiTxnExpectedCount`
- `ctoRefundSqlFile`, `ctoRefundExpectedCount`

Each SQL file prints the same one-row `RECORD_COUNT | MATCH_COUNT | RESULT_SUMMARY` contract described by the D3I template. The RTI SQL predicate includes both successful state and `${CASE.amount}`; the refund SQL identifies the required OID and counts only refunded matches. SQL exits non-zero while the condition is not ready, allowing three 10-second ATT attempts.

The shipped `fpp.invokeApi` is intentionally an integration skeleton and returns `NOT_IMPLEMENTED`. Replace its marked block with the approved FPP client before executing this template against USMF. Keep API credentials outside CASE data and Case logs.
