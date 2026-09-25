### 14.4 限制與預設值

Normative field default 以其 owner schema/configuration chapter 為準。重要 architecture limit 包括：

- load V1 必須二選一 workload model；
- arrival-rate overload policy 為 `drop`；
- 每次 Flow invocation 有新的 Action scope，返回後恢復 caller scope；
- `DIAG` 是 framework-owned evidence，不屬於 expression tree；
- resource lifecycle state 不是 public Context tree；
- 除文件明確允許的 extension location（例如支援位置的 root `x-*`）外，未知 schema field 會被拒絕。

Timeout range、evidence sample bound、result limit、pool size 等 operational numeric limit 仍由對應 schema/descriptor 定義，避免本附錄成為第二個 source of truth。
