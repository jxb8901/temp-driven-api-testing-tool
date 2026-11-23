# Example and Default Package migration matrix

This is the per-file decision record for [#426](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/426). The machine-readable copy is [example-inventory.json](../example-inventory.json). `packages/legacy` is not created: the blocked Package files stay in `packages/default` because they are the verified compatibility route and [#398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398) has no golden evidence for a Flow translation. A second copy without a cutover would not complete the migration.

| Path | Status | Kind | Reason |
|---|---|---|---|
| `packages/default/templates/FPP_CTO_RTI_USMF/CT001_Precheck.xml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/FPP_CTO_RTI_USMF/README.md` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/FPP_CTO_RTI_USMF/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/FPP_D3I_BATCH_SIT/README.md` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/FPP_D3I_BATCH_SIT/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/PAYMENT_CLEANUP/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/PAYMENT_INVOKE/debug.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/PAYMENT_INVOKE/payload.xml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/PAYMENT_INVOKE/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/PAYMENT_PREPARE/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/PAYMENT_ROLLBACK/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/PAYMENT_VERIFY/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/QUICK_START/template.yaml` | current | legacy-compatibility | Supported Workbook/Stage/Template route. Not deprecated. |
| `packages/default/templates/V3_FLOW_EXAMPLE/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/flows/common/compose/flow.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/flows/common/decorate/flow.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/flows/common/identity/flow.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/flows/payments/offline-authorize/flow.yaml` | current | native-suite | Offline multi-Flow Native Suite. Issue #425. Not a conversion of payment2. |
| `packages/default/templates/flows/payments/offline-settle/flow.yaml` | current | native-suite | Offline multi-Flow Native Suite. Issue #425. Not a conversion of payment2. |
| `packages/default/templates/flows/quick-start/flow.yaml` | current | native-quick-start | Existing Native Quick Start. att-testcase/v1. |
| `packages/default/templates/付款/本地付款/request.tmp.xml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/templates/付款/本地付款/template.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/testcase/payment2.xlsx` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/testcase/payment2.xml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/testcase/payment2.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/testcase/payment_regression.xlsx` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/testcase/payment_regression.xml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/testcase/payment_regression.yaml` | blocked | tracked-exception | Blocked by #398 golden evidence and, for LOAD/V3 targets, #418. Stays in packages/default. packages/legacy is not created. |
| `packages/default/testcase/payments/offline-authorize/testcase.yaml` | current | native-suite | Offline multi-Flow Native Suite. Issue #425. Not a conversion of payment2. |
| `packages/default/testcase/payments/offline-settle/testcase.yaml` | current | native-suite | Offline multi-Flow Native Suite. Issue #425. Not a conversion of payment2. |
| `packages/default/testcase/quick-start/README.md` | current | native-quick-start | Existing Native Quick Start. att-testcase/v1. |
| `packages/default/testcase/quick-start/excel/testcase.yaml` | current | native-quick-start | Existing Native Quick Start. att-testcase/v1. |
| `packages/default/testcase/quick-start/inline-multi/testcase.yaml` | current | native-quick-start | Existing Native Quick Start. att-testcase/v1. |
| `packages/default/testcase/quick-start/single/testcase.yaml` | current | native-quick-start | Existing Native Quick Start. att-testcase/v1. |
| `packages/default/testcase/quick_start.xlsx` | current | legacy-compatibility | Supported Workbook/Stage/Template route. Not deprecated. |
| `packages/default/testcase/quick_start.xml` | current | legacy-compatibility | Supported Workbook/Stage/Template route. Not deprecated. |
| `packages/default/testcase/quick_start.yaml` | current | legacy-compatibility | Supported Workbook/Stage/Template route. Not deprecated. |
| `packages/default/testdata/accounts.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/testdata/payments-offline-settle.xlsx` | current | native-suite | Offline multi-Flow Native Suite. Issue #425. Not a conversion of payment2. |
| `packages/default/testdata/quick-start-native.xlsx` | current | native-quick-start | Existing Native Quick Start. att-testcase/v1. |
| `packages/default/tools/cat_api_response.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/exehelper.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/exehelper.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/inspectFiles.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/invokeApi.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/loghelper.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/moveFiles.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/querySqlplus.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/searchText.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/sqlplusToXml.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp/tool-group.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp_inspect_files.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp_invoke_api.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp_move_files.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp_query_sqlplus.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp_search_text.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/fpp_sqlplus_to_xml.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/genEndToEndId.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/gen_e2e_id.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/getAcDate.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/getAppLogs.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/getSeq.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/get_ac_date.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/get_app_logs.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/get_seq.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/grepFromAppLogs.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/grep_from_app_logs.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/invokePaymentApi.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/invoke_inward_service.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/invoke_outward_service.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/invoke_payment_api.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/loghelper.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/orders/count.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/orders/find.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/orders/findByDate.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/orders/tool-group.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/orders/updateStatus.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/sample/getAcDate.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/sample/getSeq.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/sample/tool-group.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/selectCtxn.yaml` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/select_ct_txn.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `packages/default/tools/tool_group_dispatch.sh` | current | package-owned-tool-or-data | Tool and testdata ownership stays with the Package. Not a Native Case. |
| `examples/environments/README.md` | current | example-note | Current example note. |
| `examples/history/config/sut-metrics-v2.14.yaml` | legacy | historical-config | Historical inline config kept as a migration input. See the file name for its schema generation. |
| `examples/legacy/README.md` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/README.md` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/README.zh.md` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/profile-app.json` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/profile-jvm-prod.json` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/profile-jvm.json` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/profile-platform.json` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/scenario.yaml` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/legacy/sut-metrics/source.json` | legacy | relocated-sut-catalog | Former Settings catalog. Current path is examples/package-local-sut-metrics. Issue #428. |
| `examples/load/README.md` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/arrival-rate.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/arrival-smoke.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/closed-minimal.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/closed-random-think.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/closed-smoke.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/closed.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/independent-evidence.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/mixed-closed.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/multi-arrival.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/multi-closed.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/quick-profile.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/sut-metrics/v2/group-runtime.json` | current | sut-v2-contract | Active att-sut-metrics v2 samples. Not moved. |
| `examples/load/sut-metrics/v2/metric-source-db-wide-row.json` | current | sut-v2-contract | Active att-sut-metrics v2 samples. Not moved. |
| `examples/load/sut-metrics/v2/metric-source-prometheus-multi-series.json` | current | sut-v2-contract | Active att-sut-metrics v2 samples. Not moved. |
| `examples/load/sut-metrics/v2/observation-sampled-series.json` | current | sut-v2-contract | Active att-sut-metrics v2 samples. Not moved. |
| `examples/load/sut-metrics/v2/profile-flat.json` | current | sut-v2-contract | Active att-sut-metrics v2 samples. Not moved. |
| `examples/load/testdata-generated.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/load/tool.yaml` | current | load-scenario | Direct LOAD target. Not converted to a Native Case. |
| `examples/package-local-sut-metrics/README.md` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/README.zh.md` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/config/config.yaml` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/config/sut-metrics/prometheus-sit.yaml` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/config/sut-metrics/prometheus-uat.yaml` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/packages/payments/load/sut-metrics/groups/app-servers.yaml` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/packages/payments/load/sut-metrics/profiles/basic.yaml` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/packages/payments/load/sut-metrics/sources/app-runtime.yaml` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/package-local-sut-metrics/packages/payments/package.yaml` | current | sut-package-local | Canonical Global Data Source plus Package inventory. Issue #428. |
| `examples/testdata/generated-account.yaml` | current | example-note | Current example note. |
