# CI、打包與運維

ATT 支援 source-tree development 以及 offline release package。

## Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` 會執行 release gate、重新生成 modular Reference Manual、建立 application jar 和 release archive，並 smoke-test 解壓後的 local package。Reference generation 另外需要 Python 3 與 Pandoc。它會輸出供 CLI 使用的 `att-4.1.0-local.tar.gz`、包含 WAR 和部署指南的 `att-4.1.0-server.tar.gz`，以及 source archive `att-4.1.0-src.tar.gz`。每個 binary archive 均須小於 25 MB。Local package 不再包含 WAR 或其中重複的 runtime library。

## Runtime dependencies

CLI、Engine 及 Worker 仍相容 Java 8。ATT Server WAR 需要 Java 17+ 及外部 Tomcat 10.1+。ATT 不內置 CLI integration 所需的 JDBC driver；請將 driver/dependency jar 放入 `lib/`。IBM MQ 是 optional integration：default CLI build 在沒有 MQ client class 時仍可使用；MQ deployment 需 package 支援的 IBM client jar/profile。

## Documentation operations

`./att.sh docs` 從已驗證 ATT package model 生成 `build/docs/index.html`。Normative product Reference 則獨立由 `docs/reference*` 經 `tools/build_reference_manual.py` 生成。`./att.sh clean` 刪除文件規定的 generated runtime/build output，但保留 source input。

## CI 與 environment promotion

CI 應先 validate package，再執行 external integration test，並按需要保存 Run/Debug/Load evidence。Environment 透過明確 `--config`/`--env` policy 選擇。穩定 DB/MQ logical ID 讓相同 Template 在 SIT/UAT/PREPROD 間移動而不用改 Action。

Parallel job 應使用唯一 Run ID；若需要獨立 retention/latest-run state，應使用不同 output root。對同一 shared output root 的 clean/report/archive 等 destructive operation 必須序列化。

Maintainer implementation sequencing、scheduler internals、resource-owner detail 位於 `docs/system-design/`，不屬於本 end-user Reference。
