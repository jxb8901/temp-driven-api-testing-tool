<!-- att-doc-status: current release-target=4.6.0 status=unpublished legacy-code-line=4.3.1 -->

# CLI interfaces

舊版 Local CLI 和 ATT Remote 相容介面對應 4.3.1 code line。尚未發佈的 4.6.0 release-target stack 保留這些 interface，並加入明確的 Native Case 和 Native Suite RUN command。4.6.0 release 和 tag 尚未發佈，部署驗收仍待完成。

| Interface | 狀態 | 指南 |
|---|---|---|
| Local command：run、validate、snapshot、debug、load、docs、report、build、clean | 4.3.1 code line 的 legacy CLI surface | [CLI Reference](../../reference.zh/cli.md) |
| att remote profile、Job、event 及 artifact | 4.3.1 code line 的 legacy 相容介面 | [ATT Remote 指南](../../remote-client.zh.md) |
| Native Case `run native-case` 及 implicit Suite `run native-suite` | 已納入 4.6.0 目標 stack；release 與部署驗收仍待完成 | [CLI Reference](../../reference.zh/cli.md) |
| 更廣泛的共用 run、load、validate grammar 及 Local/Server routing | 規劃中，尚未發行 | [Proposed CLI vNext](vnext-proposed.zh.md) |

更廣泛的 vNext proposal 不會移除現有 Server、Studio 或 REST capability。Native Server RUN 使用已驗證的 `att-execution/v2` draft flow；舊版 `att remote` interface 仍可使用。Job management、detach 及其他 #294 P3 proposal 不屬於該 grammar。
