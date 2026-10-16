# FPP_D3I_BATCH_SIT data contract

This template implements the photographed SIT D3I batch flow as one ordered ATT stage template. It covers:

1. `FPSPD3I` input, Job2, PCFM/P file and transaction checks, done-file check;
2. Job3 `-104`, AFT files, OTWD and P/C checks;
3. `AFTD3RLT012_<AcDate>` conversion, Job2/Job3 `-105`, CFM/C checks and nine-line DVO output;
4. ten-line `FPSPDRR` input, Job2/Job3 `-106`, CAPT/CRJT + PCFM, capture-sent, file journal and AFT checks;
5. ten-line `FPSPDSR` input, Job2/Job3 `-107`, ACPT/UCPT + SUCC/RJCT, file journal and AFT checks.

## CASE data

Paths and executable inputs:

- `d3InputDirectory`, `d3WorkerDirectory`, `d3DoneDirectory`
- `aftOutboundDirectory`, `iclOutboundDirectory`, `artifactDirectory`
- `job2HandlerScript`, `job3HandlerScript`, `xml2IffScript`
- `acDate`, `d3rltXmlFile`, `d3rltTextFile`

Prepared file counts:

- `dvoExpectedFileCount`, `drrExpectedFileCount`, `dsrExpectedFileCount`

SQL file and expected-count pairs:

- `d3iTxnSqlFile` / `d3iTxnExpectedCount`
- `d3iFileJnlSqlFile` / `d3iFileExpectedCount`
- `d3iOutboundTxnSqlFile` / `d3iOutboundTxnExpectedCount`
- `d3iOutboundFileJnlSqlFile` / `d3iOutboundFileExpectedCount`
- `rltTxnSqlFile` / `rltTxnExpectedCount`
- `rltFileJnlSqlFile` / `rltFileExpectedCount`
- `dvoTxnSqlFile` / `dvoTxnExpectedCount`
- `dvoFileJnlSqlFile` / `dvoFileExpectedCount`
- `drrTxnSqlFile` / `drrTxnExpectedCount`
- `drrFileJnlSqlFile` / `drrFileExpectedCount`
- `drrCapSentSqlFile` / `drrCapExpectedCount`
- `drrCapFileJnlSqlFile` / `drrCapFileExpectedCount`
- `dsrTxnSqlFile` / `dsrTxnExpectedCount`
- `dsrFileJnlSqlFile` / `dsrFileExpectedCount`
- `dsrFinalTxnSqlFile` / `dsrFinalTxnExpectedCount`
- `dsrFinalFileJnlSqlFile` / `dsrFinalFileExpectedCount`

## SQLPlus summary contract

Each SQL file owns the business predicate named by its template assertion and prints exactly one pipe-delimited summary row:

```text
RECORD_COUNT | MATCH_COUNT | RESULT_SUMMARY
2 | 2 | SR_STUS=PCFM,PCFM
```

- `RECORD_COUNT` is the actual selected record count.
- `MATCH_COUNT` is the number satisfying the named status/flag condition.
- `RESULT_SUMMARY` is human-readable evidence for reports and Case logs.
- Exit `0` only when the expected condition is ready; exit non-zero while polling should retry.

`fpp.querySqlplus` uses three ATT attempts with a 10-second timeout per attempt. Configure `FPP_SQLPLUS_CONNECT` through an Oracle wallet or external-authentication alias; do not put credentials in CASE data.

The prepared DRR and DSR source files must each contain 10 newline-terminated lines. DVO output files must contain 9 newline-terminated lines because `inspectFiles` uses `wc -l` semantics.
