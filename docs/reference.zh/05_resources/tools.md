### 5.1 Tool

Tool 是具名的 external 或 framework-native capability。每個 Tool 必須二選一：**command-backed** 或 **call-backed**。

#### Command-backed Tool

Command-backed Tool 依 configured argv contract 在本機或已配置 SSH transport 執行。Argv list 會保留每個 item 的 argument boundary；scalar command 只會被 tokenize 成相同 internal argv model。一般 process-backed Tool 不會隱式啟動 shell，也不會自動 wildcard expansion。Stdout/stderr、exit code、timeout 和 process diagnostic 屬 evidence；非零 process exit 本身不等於 assertion FAIL，除非 Action contract 明確這樣判定。

Script、CLI、SSH、third-party executable 適合 command-backed Tool。

#### Call-backed Tool

Call-backed Tool 執行 typed framework-native call，例如支援的 DB read/update facade 或 pure built-in，不需要把 typed value 轉成 process string。若 capability 本身就是 typed ATT call contract，優先使用 call-backed。

兩種 backend 都發布相同 public Action envelope。Active Action 使用 `${output.result}`，完成後使用 `${EXEC.ACTIONS.<id>.output.result}`。Final operation evidence 位於 `output.evidence`；retry 的 per-attempt evidence 保留在 `output.attempts[n].evidence`。

Tool Action 在支援位置可以使用 object `saveAs` 和 post-operation evidence collector。Collector 在 primary operation 後、該 attempt assertion 前執行；collector failure policy 不會取代 primary `result`。
