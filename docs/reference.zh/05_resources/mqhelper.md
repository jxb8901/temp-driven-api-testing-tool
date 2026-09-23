### 5.3 MQHelper

MQHelper 是一級 IBM MQ resource。每個 descriptor 使用 `schemaVersion: att-mqhelper/v1.0`、穩定 logical `id`、connection topology 與可選 credential。Global `mqhelpers` 引用 descriptor file；environment profile 可讓相同 logical ID 在不同環境選擇不同 descriptor。

主要 call：

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payload 採 file-based contract，因此 request bytes 不需要複製到 Context/evidence。`request` 結合 send 與 correlated receive。Correlation identifier、queue/operation metadata、timing、diagnostic 屬 evidence；credential 與 payload bytes 不複製到 evidence。

Timeout 是 operation-specific failure，與 assertion failure 分開。MQ connection/pool lifecycle 是 framework-owned resource state，特別是在 Load mode，不會公開成 `EXEC.MQ` tree。

ATT default build 不要求 IBM MQ client class；真正執行 MQ 需要 package/release 文件所述 IBM MQ client jar/profile。MQ operation 與 Tool、DB 一樣進入同一 Action result/evidence envelope。
