# Testdata Registry 與 Input Mapping

本頁說明 issue #63 的 runtime boundary。Public descriptor 契約為 `att-testdata/v1.0`；Environment selection 屬於 `att-config/v2.11`，Load policy 屬於 `att-load/v1.5`。

## Registry Layers 與啟用時機

所選 environment profile 提供 package-relative testdata descriptor paths。每個 descriptor 有 logical ID，並宣告非空 literal `records` list 或 generated record。Load scenario 可在頂層 `testdata` 加入 package-relative paths，形成 scenario-local overlay。同名 Load-local ID 會在該 scenario 完整取代 environment descriptor；不合併 records 或 selection 欄位。同一 layer 內重複 ID 或重複 canonical path 都無效。

Registry 會按需建立 descriptor ID 索引。Run 與 Debug 只載入 input mapping 引用的 descriptors。Load 在 scheduler 啟動前驗證 workload inputs 和明確 policy 引用的 IDs。明確執行 package validation 時，會載入並 schema-validate 所選 environment 的全部 descriptors，包括未使用項。Import 必須解析為 canonical package root 內的 regular YAML file，且不能是 symlink。

## Literal 與 Generated Records

Literal record 保留 YAML value 型別，因此完整 input reference 可回傳 scalar、map 或 list。Descriptor 驗證後會凍結其資料，避免共享 record 被呼叫端修改。

Generated record 宣告一個 inclusive integer range（`from` 到 `to`）與一個 `record` template。Range 必須遞增，最多 1,000,000 筆。Integer format 僅支援 `%d` 或 `%0Nd`。`record` 可含巢狀 maps/lists；ATT 遞迴以格式化整數取代 `%{seq}`。Generated record 不允許其他 variable 或 expression 語法。只按需求物化所選 index，不會先建立整個 sequence list。

## Mapping 與 Selection

Input mapping 會在解析值發布至 `EXEC.INPUT` 前執行：

- `@{id}` 回傳所選 record，並保留原生型別。
- `@{id.path}` 及數字 list index 可存取巢狀值。
- `@{id}` 可內嵌於文字，但所選值必須是非 null scalar；結果為字串。
- `${...}` 只可讀取該 mapping 階段開始前已初始化的 Context。Run Case/Stage mapping 可使用 `EXEC.ID`、`EXEC.RUN_ID`、`EXEC.STARTED_AT`、`EXEC.RUN_STARTED_AT`、`EXEC.OUTPUT_DIR` 及 `META.PROJECT/SOURCE/TARGET`。Debug `inputs` 另可使用 `META.TEMPLATE`。Load workload `inputs` 可使用 `EXEC.RUN_ID`、兩個 timestamp、當前可用的 `EXEC.LOAD` identity fields 及 `META.PROJECT/SOURCE/TARGET/TEMPLATE`；`EXEC.ID` 與 `EXEC.OUTPUT_DIR` 只會在 input 解析後初始化。`EXEC.LOAD.USER_ID` 只在 closed workload 提供；若要支援 arrival-rate，可用 optional path `${EXEC.LOAD.USER_ID?}`。
- 所有 mode 都拒絕在建立時引用 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、Action `output` 及 invocation-scoped helper metadata。Mapping 會在 execution 前驗證；Load 會在 scheduler 啟動前驗證。V1 mapping grammar 會評估 literal、selected-record `@{...}` reference 及 `${...}` Context reference；不會評估 built-in call。
- Mapping 不會評估 `#{...}`、`&{...}` 或 `%{...}`。

同一 mapping 對同一 ID 的所有引用共用同一筆 record。Run/Debug 跨 Case 與 Stage mapping 也會保留該 execution 的 ID choice。多筆 records 的 descriptor 必須宣告 `selection.strategy`：`sequential`、`roundRobin` 或 `random`。`exhaustion` 預設為 `error`；`recycle` 會由開頭重新選取，`stop` 會向 scheduler 發出停止目前 Load workload 的訊號。設定 `seed` 可讓 random selection 重複。

Load 中每個被引用 ID 可設定 `workload`、`user` 或 `iteration` scope；預設為 `iteration`。同一 scope lifetime 會沿用所選 record。`user` 需要 closed-VU workload，因為 arrival-rate 沒有穩定 user identity。Workload `selection` policy 會完整取代 descriptor policy；這是選取覆蓋，不是 import 宣告。單筆 descriptor 直接選取，不必設定 selection。

Resolver 的 run-level selection map 只保留 workload/user scope。Iteration 選擇只存在目前 input mapping 的 local memo table，因此同一 mapping 重複引用 ID 仍會得到相同 record，而已完成的 iteration 不會留下 cache entry。Resolver telemetry 會按 scope 報告 mapping evaluations、selection requests/evaluations/cache hits 及 cache sizes。

## Direct Reference Boundary 與 Evidence

Testdata marker 只允許出現在 input mapping。Template、Flow、Tool definition 及 Tool invocation arguments 不可直接使用 `@{...}` 或 `%{...}`，也不可讀取 `TESTDATA.*`、`EXEC.TESTDATA`、`EXEC.DATA` 或 `EXEC.FIXTURE`；它們透過 `EXEC.INPUT` 接收已解析值。Selection policy 因此留在 execution boundary，可重用元件只需消費一般型別的 input。

Selection evidence 包含 logical ID、來源 layer、從零開始的 record index、可用時的 generated sequence、scope、strategy 與有效 random seed；不複製 raw record contents。Load 將有界 metadata 保存在保留的 iteration result 中。Resolver 會把 selection choice 限定於目前 execution/workload，generated values 則按 index 建立，避免展開整個範圍。

Descriptor loader 會拒絕 `password`、`token`、`authorization`、`api_key` 等 credential-like record 欄位名稱；這項檢查只看欄位名稱，不掃描任意值。Selection metadata 不含 input values。Records 請使用合成或最少化的個人資料，並以現有 Load evidence policy 控制保留哪些 execution evidence。
