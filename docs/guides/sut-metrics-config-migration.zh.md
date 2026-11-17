# SUT Metrics Runtime Config 遷移

ATT 4.3.1 從所選 Server Runtime Config 讀取 Data Source、Metric Source、Group 和 Profile。Server data directory 內的 `sut-metrics-catalog.json` 不再是有效來源。遷移完成前請保留該檔；切換至 config-backed mode 後 Server 會忽略它。

## 以 YAML 定義

使用 `att-config/v2.14` 及有界的 `sutMetrics` lists。範例見 [`examples/config/sut-metrics-v2.14.yaml`](../../examples/config/sut-metrics-v2.14.yaml)。不要加入 `createdAt`、Data Source revision、Metric Source connection revision 或 flat Profile revision hash；ATT 會由正規化定義及 active Runtime Config 推導 revisions。只儲存 `ATT_SECRET_PROMETHEUS_TOKEN` 這類 secret reference，絕不可儲存 secret 值或絕對路徑。

啟用候選設定時，Server 必須能以 `ATT_SECRET_<secretRef>` 解析每個 Data Source credential 和選用的 CA secret。HTTPS host 也必須列在精確 host 的 `ATT_SUT_METRICS_ALLOWED_HOSTS` 清單中；只有 loopback HTTP 可免此限制。secret 遺失或 host 不在 allowlist 時，候選設定會被拒絕，並保留 last-known-good catalog。

Admin catalog read 和 preview 仍可使用。中央 Runtime Config writer 尚未支援前，admin API mutation 會回傳 HTTP 409 `ATT-SUT-METRICS-CONFIG-READ-ONLY`。請按核准的 Runtime Config 變更流程發佈。無效 YAML 候選設定會被拒絕，並保留 last-known-good catalog。

DBHelper Metric Source 會解析到各 Runtime Config Environment 既有的 DBHelper descriptor。Flat Profile 的 `allowedBindings.environments` 必須列出已宣告的環境，而且 Profile 允許的每個環境都必須有其引用的 DBHelper。Package Tool connection 仍屬於 Package，會在 LOAD admission 時依所選 Package 和 Environment 驗證。

## 遷移舊 JSON catalog

先建立並檢視獨立、已驗證的候選檔：

```sh
scripts/migrate-sut-metrics-catalog.sh \
  --config /absolute/path/config/config.yaml \
  --catalog /absolute/path/data/sut-metrics-catalog.json \
  --output /absolute/path/config/config.sut-metrics-candidate.yaml
```

比較候選檔和原始 config，確認 secret reference 及 endpoint，並在預期部署環境驗證。遷移會拒絕重複 ID，以及已存在但不同的 `sutMetrics` 定義；不會合併或覆寫衝突 catalog。

檢視完成後明確套用：

```sh
scripts/migrate-sut-metrics-catalog.sh \
  --config /absolute/path/config/config.yaml \
  --catalog /absolute/path/data/sut-metrics-catalog.json \
  --apply
```

Apply 會再次檢查兩個輸入檔，為 Runtime Config 和舊 catalog 建立以 digest 命名的唯讀備份，並以 atomic replace 發佈已驗證的 `att-config/v2.14` 候選檔。舊 catalog 會保留供 rollback；Server 不會讀取它。重複執行相同遷移不會改檔。若 Runtime Config 已有完全相同的定義，命令會回報已完成；若定義不同則停止，不會合併。

沒有 `sutMetrics` 的舊版 ATT config 設定仍可讀取。遷移會將 config 版本升至 v2.14，使新的 strict block 通過驗證。已提交的 Job 仍保留原本固定的 SUT Metrics snapshots 和 revisions。
