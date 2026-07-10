# ATT V2 Test Case Development Guide

**Author: Jeffrey + ChatGPT**

This guide is for people who add or change test cases without changing framework code.

## Start with the sample

`testcase/payment_regression.xlsx` is the canonical example. It contains 22 passing cases across `支付測試案例集` and `批量測試案例集`. Copy it with its sidecar YAML, then rename both files together.

```sh
cp testcase/payment_regression.xlsx testcase/my_feature.xlsx
cp testcase/payment_regression.yaml testcase/my_feature.yaml
./att.sh validate --suite testcase/my_feature.xlsx
```

## Add a row safely

1. Keep headers exactly as declared in the sidecar.
2. Make the row Case ID unique within its sheet; ATT prefixes it with the group ID.
3. Put YAML in columns whose sidecar declaration ends in `(yaml)`; the physical Excel header has no `(yaml)` suffix.
4. Set every required stage template cell to a YAML map with `name`.
5. Run `validate` before a test run.

| Column | Example value |
|---|---|
| 案例編號 | `TC012` |
| 案例名稱 | `Refund with decimal amount` |
| 標籤 | `refund,edge,decimal` |
| 預期結果 | `status: SUCCESS\nrejectCode: '0000'` |
| 執行模板 | `name: PAYMENT_INVOKE` |
| 驗證模板 | `name: PAYMENT_VERIFY` |

`N/A`, `NA`, `NULL`, and `NONE` become blank. Optional stage cells may be blank; required stages may not.

## Use stages and templates deliberately

`runWhen: onSuccess` runs only while prior stop-on-failure stages pass; `always` is suitable for cleanup; `onFailure` is for diagnostics or compensation. Use `onFailure: continue` only for evidence gathering that must not block a later useful assertion.

Place `template.yaml` and its payload files together under `templates/<name>/`. Use stable action IDs: current-stage output is `${ACTIONS.<action-id>.output}` and persisted cross-stage output is `${CASE.STAGES.<stage>.TEMPLATE.ACTIONS.<action-id>}`.

Use a `render` action for large XML/JSON/text payloads rather than duplicating them in Excel.

## Write reliable assertions

Quote literal strings; leave numeric and boolean literals unquoted when type matters.

```yaml
expression: "${ACTIONS.callApi.output.Response.Status} == 'SUCCESS'"
expression: "${ACTIONS.selectTxn.output.effectRows} >= 1 and true"
expression: "${ACTIONS.getLogs.output} like '%PAYMENT%POSTED%'"
```

For simple transformations, use built-ins instead of a new shell script:

```text
#{upper(value=${CASE.currency})}
#{coalesce(${CASE.optionalReference}, 'NO-REFERENCE')}
#{boolean(yes)}
```

Use configured tools only for external systems or commands. Keep inputs named, validate them in `config/config.yaml`, and prefer deterministic outputs in samples.

## Review checklist

- Workbook and sidecar basenames match.
- IDs, tags, and template names are meaningful and unique.
- YAML cells parse and do not duplicate a stage data key in their template map.
- Assertions cover the intended normal or edge behavior.
- Sensitive values are absent from samples and log messages.
- `./att.sh validate --suite ...` and the selected run pass.

## Reference: capability recipes

Use a full template path when symbolic names are inappropriate:

```yaml
name: 付款/本地付款
```

All other keys in that YAML cell become stage-private data. Do not repeat a key supplied by `stages[].dataColumns`.

Use lifecycle stages in sidecar order:

```yaml
- {key: invoke, template: 執行模板, required: true, runWhen: normal, onFailure: stop}
- {key: rollback, template: 回滾模板, required: false, runWhen: onFailure, onFailure: continue}
- {key: cleanup, template: 清理模板, required: false, runWhen: always, onFailure: continue}
```

Tool-call literals are typed and quoted commas are safe:

```text
#{someTool(message='Hello, payment', retry=3, enabled=true)}
```

Only configured external tools accept named arguments validated by `config.yaml`. The optional `delimit` metadata may appear only on the final argument and converts one cell value such as `PAYMENT,POSTED` to ordered process arguments.

Generate both documentation layouts while reviewing a package:

```sh
./att.sh docs
./att.sh docs --single-page
```

The first is navigable JavaDoc-style multi-page documentation; the second is a searchable portable HTML file.
