## Appendix D — Limits、Security Guarantees 與 Advanced Diagnostics

### Limits 與預設值

Normative field default 以其 owner schema/configuration chapter 為準。重要 architecture limit 包括：

- Load 必須二選一 workload model；
- arrival-rate overload policy 為 `drop`；
- 每次 Flow invocation 有新的 Action scope，返回後恢復 caller scope；
- `DIAG` 是 framework-owned evidence，不屬於 expression tree；
- resource lifecycle state 不是 public Context tree；
- 除文件明確允許的 extension location（例如支援位置的 root `x-*`）外，未知 schema field 會被拒絕。

Timeout range、evidence sample bound、result limit、pool size 等 operational numeric limit 仍由對應 schema/descriptor 定義，避免本附錄成為第二個 source of truth。

### Collector projection 與 redaction guarantees

所有失敗 collector（包括 returned operation error 和 thrown Tool exception）都會先经過同一 public projection 再發布或寫 log。Projection 省略 raw input、payload、argv、output、resolved command text 和失敗 record 的 `result`，且不保證保留 `parserDiagnostic`。Native error/diagnostic 只保留安全 field；每個保留的 text field 限制為 1024 字元。`inputOmitted` 和 truncation flag 表示省略或截斷的 evidence。 Free-form message、stderr、per-instance error 和 cleanup warning 會在固定 budget 內 redact string、DocumentValue text 和 array input；最多检查 256 個 input node、8192 個 token 字元，每個 token 最多 1024 字元。Byte array 最多處理 128 byte（UTF-8、Base64、hex 和 Java decimal rendering），其他 array 最多 64 個 element；char array 最多 1024 字元。超出任一 budget、private token 少于 4 字元或遇到未知 input type 時，會用安全 marker 省略所有 free-form failure detail（包括 upstream-truncated secret prefix/head-tail echo），並设置 `inputRedactionLimited` 和 `failureDetailsOmitted`。超過 1024 字元的 free-form field 也會被省略並標记 truncated；structured metadata 继續保留。Structured status、category 和 resource identity 只做长度限制。SSH fan-out 會保留最多 64 個 instance 的 bounded metadata、error 和 stderr，優先保留失敗 instance；`instanceCount` 和 `instancesTruncated` 表示总數和省略的 instance。 若有 private token，且 operation 或 instance record 標记了 capture/detail truncation（如 `stderrTruncated` 或 `stderrArtifactTruncated`），該 record 的 free-form failure detail 也會被省略，以避免短 secret 被切斷後泄漏 prefix/suffix。没有 private token 時可保留 bounded preview。Primitive array 的完整 list rendering 和單獨 element 都會在相同 node/token budget 內 redact。 DB returned failure 會從 native `result.error` 提取安全 summary（`type`、bounded/redacted `message`、`sqlState`、`vendorCode` 和安全 cancellation metadata），保留于 DB evidence 的 `error` 並用于 collector 的 `error`；不會發布 rows、parameters、SQL text 或 raw result。失敗 command 的 `stdout` 可作為獨立 diagnostic evidence，按與 `stderr` 相同的 bounded/redacted/omission policy 處理；不會作為 `error.message` 或恢復失敗 `result`。 MQ evidence 的 root 和 error summary 會保留 `completionCode`、`reasonCode` 和 bounded symbolic `reason`。安全 location metadata 包括 HTTP `method` 和僅含 scheme/host/port 的 `url` origin，以及 MQ `queueManager`、`physicalInstance`、`host`、`port`、`channel` 和 `transport`。HTTP evidence 没有 resolved request input，因此失敗 collector 的 URL 一律省略 path、query、fragment 和 user info，並设置 `urlPathOmitted`；不添加 raw input。無法安全解析或超過 budget 的 URL 會以安全 marker 省略。

### Advanced diagnostics

#### 哪些意外異常會附带 stack trace？

意外內部故障（例如 `NullPointerException`、`ClassCastException`、反射查找／存取失敗、其他非預期 runtime exception，或非 domain `IllegalStateException`，包括包在 wrapper cause 內的情況）會在 `case.log` 寫入有界的 `[ATT INTERNAL ERROR]` 區塊、執行 phase 及原始 cause chain。Validation `IllegalArgumentException`、已識別的 domain／transport failure、timeout／cancellation、assertion failure 與一般 MQ no-message outcome 仍保持精簡。同一 Throwable 即使同時被 resource executor 和 Action boundary 看見，每個 Case log 也只會寫一次。Resource-specific redaction（包括由 environment 提供的 SSH identity-file path）會註冊到該 Case log，並套用至後續 log write，避免外層 Action diagnostic 洩漏未出現在 sanitized stack 的內容。Stack 最多 180 行／16 KB；configured secrets 與敏感 key/value assignment 也會遮蔽。Public Action evidence 只保留簡短錯誤類型／phase，不加入 stack。Run、Debug 及 reusable Tool/HTTP/MQ/DB 共用這條 logging path。

#### 為什么 `att.bat` 會要求 Maven，或者為什么 `.sh` Tool 在 Windows 上失敗？

在二進制發布中，`att.bat` 會找到 `lib\att-*.jar`，只需要 Java 8+。源碼樹中，`att.bat` 會在 Maven 在 `PATH` 上時使用 Maven；没有 Maven 時，需要已有的 `target\classes`。先用 `att.bat version` 确認啟動器後再校驗包。

啟動器讓 ATT 自身跨平台；它無法翻译外部 Tool 可執行文件。請為 Windows 配置 `.bat`、`.cmd`、PowerShell 腳本（需要顯式 `powershell`/`pwsh` argv）或原生可執行文件，而不是 POSIX-only `.sh`。PATH 校驗遵循 Windows `PATHEXT`，因此如 `pwsh` 這類名称可解析為 `pwsh.exe`。维護多平台版本時，請保持參數契約和 stdout 輸出格式一致。

#### 為什么 ATT 說會使用 mwiede/jsch，或者 Java SSH 協商失敗？

當 `PATH` 中存在可執行 `ssh` 時，ATT 會優先使用本地 `ssh`。如果不存在，ATT 會打印 `local ssh command not found; ATT will use Java SSH library mwiede/jsch`，並改用 Java exec channel。這是自動回退，不是远程連通性测试。

回退實现非常保守：ATT 包含 `com.github.mwiede:jsch:2.28.2`，但不捆绑 Bouncy Castle。它要求一個可讀、非符號链接的 `~/.ssh/known_hosts` 用作严格主机驗證。它不會讀取 `~/.ssh/config`，也不會自動使用 OpenSSH agent；需配置一個非交互可讀的 `identityFile`。密碼和交互式口令提示不支持。

算法可用性取决于 Java 运行時：

| 算法 | Java 回退限制 | 首選方案 |
|---|---|---|
| `ssh-ed25519`、`ssh-ed448` | 需要 Java 15+ 或 Bouncy Castle provider | 優先使用本地 OpenSSH 或 Java 15+；否則讓管理员把批准的 `bcprov-jdk18on` 加入运行時 classpath |
| `curve25519-sha256`、`curve448-sha512` | 需要 Java 11+ 或 Bouncy Castle | 優先本地 OpenSSH 或 Java 11+；否則使用批准的 Bouncy Castle provider |
| `chacha20-poly1305@openssh.com` | 在所有 Java 版本上都需要 Bouncy Castle | 優先本地 OpenSSH，或在服務端啟用 AES-GCM/CTR cipher，並添加 Bouncy Castle provider |
| RSA/SHA-1 `ssh-rsa` 簽名 | 默認被 mwiede/jsch 禁用 | 更新服務端到 RSA/SHA-2 (`rsa-sha2-256`/`rsa-sha2-512`) 或其他现代 host/user-key 算法；不要在未经审查的情况下重啟 SHA-1 |

協商失敗時，先用本地 `ssh -v` 復现連接，定位 host-key、key-exchange、cipher 或 user-key 不匹配。優先升級 Java 或服務端算法集合，而不是弱化 JSch 默認值。

