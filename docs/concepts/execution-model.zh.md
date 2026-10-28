# Execution model

ATT 將 functional execution、Load generation 及 read-only validation 分開。

## Functional RUN

執行模型支援 Case、Template、Flow 或 Tool target，亦支援選取一批 Case。ATT Studio 及 Server 透過統一的 RUN draft 與 Job 提供這些功能。對支援的 Server target，Debug 是 RUN 的選用診斷模式。

ATT 4.2.6 保留各 interface 的相容行為：Local CLI 的 run command 執行 workbook Case，standalone debug command 執行單一 Template、Flow 或 Tool。舊版 ATT Remote client 保留相應 Server command。這些 command 名稱不會定義不同的 Engine semantics。

## LOAD

LOAD 透過 Load scheduler 執行 workload definition，支援 VU 或 arrival-rate pacing、timing controls、threshold 及 evidence policy。這是獨立的效能執行模型。六種 authoring shape 概述於 [Load model](load-model.zh.md)；[Load Reference](../reference.zh/execution-modes/load.md) 定義其 schema 及 execution contract。

## VALIDATE

VALIDATE 檢查 configuration、Package resource、schema 及所選 dependencies。就 business execution 而言，它是 read-only：不會執行 Tool、發送 Load traffic 或進行 DB/MQ business operation。Snapshot generation 仍是明確的 authoring command。

## Contract 所有權

[Unified RUN](../reference.zh/execution-modes/unified-run.md) 定義 RUN semantics。[Debug](../reference.zh/execution-modes/debug.md) 定義診斷 input 與 output。[LOAD](../reference.zh/execution-modes/load.md) 定義 workload 行為。[Validation and Diagnostics](../reference.zh/validation-diagnostics.md) 定義 validation result 與診斷。
