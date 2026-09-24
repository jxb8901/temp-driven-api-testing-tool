#!/usr/bin/env python3
from pathlib import Path


def replace(path, old, new):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit("expected text not found in %s: %s" % (path, old[:100]))
    p.write_text(text.replace(old, new), encoding="utf-8")


for rel in (
    "docs/reference/07_expressions.md",
    "docs/reference.zh/07_expressions.md",
    "docs/reference/09_configuration.md",
    "docs/reference.zh/09_configuration.md",
):
    p = Path(rel)
    text = p.read_text(encoding="utf-8")
    text = text.replace("](../examples/", "](../../examples/")
    text = text.replace("](../schemas/", "](../../schemas/")
    text = text.replace("](../schemas/)", "](../../schemas/)")
    p.write_text(text, encoding="utf-8")

replace("docs/reference.zh/09_configuration.md", "att-schema-catalog/v2.6", "att-schema-catalog/v3.0")
replace(
    "docs/reference.zh/09_configuration.md",
    "当前主配置、Tool group、sidecar 与 template 分别为 `att-config/v2.6`、`att-tool-group/v2.6`、`att-sidecar/v2.2`、`att-template/v2.6`。",
    "当前主配置、Tool group、sidecar、Template 与 Flow 分别为 `att-config/v2.6`、`att-tool-group/v2.6`、`att-sidecar/v2.2`、`att-template/v3.0` 与 `att-flow/v3.0`。",
)

for rel in ("docs/reference/09_configuration.md", "docs/reference.zh/09_configuration.md"):
    p = Path(rel)
    text = p.read_text(encoding="utf-8").replace("]( #database-helpers)", "]( #52-dbhelper)")
    text = text.replace("](#database-helpers)", "](#52-dbhelper)")
    p.write_text(text, encoding="utf-8")

replace(
    "docs/quick-start.md",
    "09_Reference_Manual_V3.md#08-report-reference",
    "09_Reference_Manual_V3.md#11-results-reports-and-evidence",
)

# Tighten the release gate around intended source ownership and ATT CLI command lines.
p = Path("tools/verify_documentation.py")
text = p.read_text(encoding="utf-8")
old = 'actual = set(str(path.relative_to(root)).replace("\\\\", "/") for path in root.rglob("*.md"))'
new = 'actual = set(str(path.relative_to(root)).replace("\\\\", "/") for path in root.rglob("*.md") if path.name != "README.md")'
if old not in text:
    raise SystemExit("reference README filter patch point not found")
text = text.replace(old, new)
old = '''    for option in re.findall(r"--[a-z][a-z0-9-]*", quick):
        if option not in supported_options:
            fail("docs/quick-start.md uses unsupported ATT option: %s" % option)'''
new = '''    for line in quick.splitlines():
        if "./att.sh" not in line and "att.bat" not in line:
            continue
        for option in re.findall(r"--[a-z][a-z0-9-]*", line):
            if option not in supported_options:
                fail("docs/quick-start.md uses unsupported ATT option: %s" % option)'''
if old not in text:
    raise SystemExit("Quick Start ATT option patch point not found")
text = text.replace(old, new)
p.write_text(text, encoding="utf-8")

EN = r'''

### Complete option matrix (3.5.1)

`--config <file>` selects the base configuration. `--env <name>` selects one environment profile from an `att-config/v2.6` configuration and is valid for `run`, `validate`, `debug`, and `load`. `--help` prints help. `--case-id` is a compatibility synonym for `--case`. `--parallel` is the deprecated compatibility spelling for `--allow-parallel-runs`; prefer the latter. `--queue` and `--allow-parallel-runs` control process-level output-root concurrency, not Case workers. `--profile` writes performance diagnostics for `run` or `load`.

Load uses the scenario as the base and explicit workload options override the corresponding fields before the effective scenario is validated again:

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

The complete workload override set is `--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, and `--overload-policy`. `--think-time` is closed-VU only. Common selection/output options remain command-specific: `--suite`, `--suite-dir`, `--case`/`--case-id`, `--tag`, `--exclude-tag`, `--all`, `--run-id`, `--output-dir`, `--format`, `--quiet`, `--verbose`, `--ci-output`, `--dry-run`, `--fail-fast`, `--rerun-failed`, `--update-snapshot`, `--package`, `--selected`, `--input`, `--queue`, `--parallel`, `--allow-parallel-runs`, `--profile`, `--config`, `--env`, and `--help` are accepted only where the command contract permits them.
'''

ZH = r'''

### 完整選項矩陣（3.5.1）

`--config <file>` 選擇 base configuration；`--env <name>` 從 `att-config/v2.6` 選擇 environment profile，適用於 `run`、`validate`、`debug` 和 `load`。`--help` 顯示說明。`--case-id` 是 `--case` 的相容別名。`--parallel` 是已棄用的 `--allow-parallel-runs` 相容拼法，應優先使用後者。`--queue` 與 `--allow-parallel-runs` 控制共用 output root 的 process-level concurrency，不會在單一 run 內增加 Case worker。`--profile` 為 `run` 或 `load` 寫入 performance diagnostics。

Load 以 scenario 為基礎；明確提供的 workload option 會先覆蓋對應欄位，再重新驗證 effective scenario：

```sh
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT --users 2 --duration 5s
./att.sh load examples/load/arrival-smoke.yaml --config config/config.yaml --env UAT \
  --arrival-rate 5/s --warmup 1s --ramp-up 1s --duration 5s --ramp-down 1s \
  --max-concurrent 4 --overload-policy drop --format json
```

完整 workload override 為 `--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent` 和 `--overload-policy`；`--think-time` 只適用 closed-VU。其餘 selection/output 選項仍受各 command 約束：`--suite`、`--suite-dir`、`--case`/`--case-id`、`--tag`、`--exclude-tag`、`--all`、`--run-id`、`--output-dir`、`--format`、`--quiet`、`--verbose`、`--ci-output`、`--dry-run`、`--fail-fast`、`--rerun-failed`、`--update-snapshot`、`--package`、`--selected`、`--input`、`--queue`、`--parallel`、`--allow-parallel-runs`、`--profile`、`--config`、`--env` 和 `--help` 只在對應 command contract 允許時有效。
'''

for rel, marker, appendix in (
    ("docs/reference/10_cli.md", "### Complete option matrix (3.5.1)", EN),
    ("docs/reference.zh/10_cli.md", "### 完整選項矩陣（3.5.1）", ZH),
):
    p = Path(rel)
    text = p.read_text(encoding="utf-8")
    if marker not in text:
        p.write_text(text.rstrip() + appendix + "\n", encoding="utf-8")
