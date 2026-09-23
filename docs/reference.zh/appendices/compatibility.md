### 14.2 相容性與已棄用 Alias

Compatibility 的目的，是讓既有 package 可讀，而不是維持第二套 current model。新 authoring 使用 canonical `EXEC`、`META`、Action-local `output`、current schema、`--env` 與目前 Tool/DB/MQ contract。

Deterministic legacy alias 在可一對一映射時可以保留並產生 migration warning；若舊語義與 scope isolation 或 common result/evidence contract 衝突，就不建立 alias。Deprecated CLI/authoring form 只有在使用者仍需要 migration path 時才保留在其 owner chapter 或 CHANGELOG。
