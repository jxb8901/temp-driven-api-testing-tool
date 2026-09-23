## 12 驗證與診斷

### 先从校验开始

在每次工作簿、侧车、模板或工具变更后执行：

```sh
./att.sh validate --package
```

然后根据诊断代码和结构化位置排查。不要针对人类可读消息做自动化判断。

| 类别 | 典型原因 | 修正措施 |
|---|---|---|
| `ATT-TC` | 缺失/过期快照、侧车/Sheet/表头错误、重复 Case ID | 检查快照/基名、sheet 映射、有效表头和完整 ID |
| `ATT-CTX` | 未知或歧义 Context 路径 | 检查请求/当前/缺失字段、最近建议或规范候选 |
| `ATT-STG` | 必需选择器为空白、选择器 YAML 无效、阶段键重复 | 检查选择器形式、`name`、别名和 required 标志 |
| `ATT-TPL` | 未知/重复模板、动作或负载无效 | 检查符号名/完整路径、描述符、动作类型和本地文件 |
| `ATT-CFG` | 未知字段、重复键、schema 类型/枚举错误 | 与第 6 章对照并移除不支持字段 |
| `ATT-TOOL` | 未知/缺失参数、进程或解析失败 | 对比调用契约，检查退出码/stdout/stderr/raw output |
| `ATT-PATH` | 非法 ID 或路径逃逸 | 移除非法字符，并保持内容在配置根目录下 |
| `ATT-RUN` | 超时、非零退出、渲染/运行时失败 | 检查 Case 日志和动作/工具证据 |

### 常见问题

#### 为什么 Excel 看起来没问题，但 Case ID 被拒绝？

ATT 导入的是显示单元格文本，然后应用严格的 ID 安全检查。检查隐藏的首尾空白、尾随 `.`、路径字符、控制字符以及 Windows 设备名。以文本形式保存标识符，以保留前导零。

#### 两张 sheet 能同时包含 `TC001` 吗？

可以。给 sheet 不同的 group ID，即可生成例如 `payment.payment.TC001` 和 `payment.batch.TC001` 这样的 ID。

#### 为什么 `N/A` 变成空了？

ATT 会在数据映射和阶段选择前，把 `N/A`、`NA`、`NULL`、`NONE`、空和仅空白值归一化为 blank。

#### 为什么 Context 变量失败？

ATT 会把缺失路径视作作者/运行时错误，而不是静默渲染成空字符串。遵循 `ATT-CTX-001` 的 `requestedPath`、`currentNode`、`missingSegment` 和最近建议，检查大小写敏感的作用域、物理表头/别名、阶段 key、动作 ID，以及可用性时间点。后缀简写必须唯一识别一个可读逻辑路径；当 validation 能识别 canonical current-scope replacement 时，会以 `CONTEXT_LEGACY_PATH` 发出迁移 warning。`ATT-CTX-002` 会列出所有冲突候选，以便你加长后缀或使用规范路径。声明的可选字段即使值为空白，仍然是有效空字符串。

#### 为什么 FAIL 变成 ERROR？

假断言是 FAIL。无效表达式语法/导航、工具失败、超时、解析失败、I/O 失败或运行时异常，都是 ERROR。应查看动作证据，而不只看最终聚合状态。

#### 为什么工具跑了不止一次？

它的动作启用了重试，并收到了符合条件的非零退出码。查看 Case 日志中的尝试列表和最终动作记录。

#### 我能在 `command` 中使用 shell 管道吗？

不能。ATT 会把 `|`、`>`、`<` 按字面值传递。把 shell 行为放到审查过的工具脚本中。

#### 为什么必需的 array 参数会拒绝 `[]`？

必需项验证发生在 argv 扩展之前。空 typed List 被视为缺失；请至少传入一个标量 item，或将参数设为 optional。

#### 我应该使用包校验还是选中校验？

本地快速反馈请用 selected 模式。发布前、CI 推进、或共享包时请用 package 模式。

#### 报告能否不依赖服务器打开？

可以。保持生成的 run 目录完整即可，相关相对链接仍可工作。

#### build 会不会再次执行测试？

不会。它只是归档一个已完成的持久化 run。

#### 为什么 `att.bat` 会要求 Maven，或者为什么 `.sh` 工具在 Windows 上失败？

在二进制发布中，`att.bat` 会找到 `lib\att-*.jar`，只需要 Java 8+。源码树中，`att.bat` 会在 Maven 在 `PATH` 上时使用 Maven；没有 Maven 时，需要已有的 `target\classes`。先用 `att.bat version` 确认启动器后再校验包。

启动器让 ATT 自身跨平台；它无法翻译外部工具可执行文件。请为 Windows 配置 `.bat`、`.cmd`、PowerShell 脚本（需要显式 `powershell`/`pwsh` argv）或原生可执行文件，而不是 POSIX-only `.sh`。PATH 校验遵循 Windows `PATHEXT`，因此如 `pwsh` 这类名称可解析为 `pwsh.exe`。维护多平台版本时，请保持参数契约和 stdout 输出格式一致。

#### 为什么 ATT 说会使用 mwiede/jsch，或者 Java SSH 协商失败？

当 `PATH` 中存在可执行 `ssh` 时，ATT 会优先使用本地 `ssh`。如果不存在，ATT 会打印 `local ssh command not found; ATT will use Java SSH library mwiede/jsch`，并改用 Java exec channel。这是自动回退，不是远程连通性测试。

回退实现非常保守：ATT 包含 `com.github.mwiede:jsch:2.28.2`，但不捆绑 Bouncy Castle。它要求一个可读、非符号链接的 `~/.ssh/known_hosts` 用作严格主机验证。它不会读取 `~/.ssh/config`，也不会自动使用 OpenSSH agent；需配置一个非交互可读的 `identityFile`。密码和交互式口令提示不支持。

算法可用性取决于 Java 运行时：

| 算法 | Java 回退限制 | 首选方案 |
|---|---|---|
| `ssh-ed25519`、`ssh-ed448` | 需要 Java 15+ 或 Bouncy Castle provider | 优先使用本地 OpenSSH 或 Java 15+；否则让管理员把批准的 `bcprov-jdk18on` 加入运行时 classpath |
| `curve25519-sha256`、`curve448-sha512` | 需要 Java 11+ 或 Bouncy Castle | 优先本地 OpenSSH 或 Java 11+；否则使用批准的 Bouncy Castle provider |
| `chacha20-poly1305@openssh.com` | 在所有 Java 版本上都需要 Bouncy Castle | 优先本地 OpenSSH，或在服务端启用 AES-GCM/CTR cipher，并添加 Bouncy Castle provider |
| RSA/SHA-1 `ssh-rsa` 签名 | 默认被 mwiede/jsch 禁用 | 更新服务端到 RSA/SHA-2 (`rsa-sha2-256`/`rsa-sha2-512`) 或其他现代 host/user-key 算法；不要在未经审查的情况下重启 SHA-1 |

协商失败时，先用本地 `ssh -v` 复现连接，定位 host-key、key-exchange、cipher 或 user-key 不匹配。优先升级 Java 或服务端算法集合，而不是弱化 JSch 默认值。

### 安全提醒

不要把密码、token、私钥或敏感客户数据放进工作簿单元格、模板描述符、命令字符串、stdout 或 stderr。优先使用工具脚本中经批准的秘密注入方式。在共享报表和归档前进行审查。
