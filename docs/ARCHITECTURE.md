# 当前架构

本文只描述已实现的职责与约束。

## 依赖与执行边界

`web` 调用 `core`，`core` 直接使用 `component` 中的具体组件。`config/AssistantAutoConfiguration` 负责装配。ConversationService 管理每个 conversationId 的 Session，AgentRuntime 将循环交给 AssistantHarness。

```text
ChatController → ConversationService.send
  └─ Session.waiting 登记输入 → 公平 executionLock
       ├─ 已提升为 steer：返回 202 STEERED，不再单独执行
       └─ Runtime.chat(conversationId, message, steers)
            └─ Harness.aroundRun(..., this::runLoop)
                 ├─ 恢复历史 → 保存 RUNNING
                 ├─ Runtime.runLoop(AgentExecution)
                 │    ├─ aroundReasoning：提交一条 steer，更新/保存视图并取得 Prompt
                 │    ├─ aroundModelCall → ChatModel
                 │    ├─ checkpoint：先保存 assistant 文本或工具意图
                 │    ├─ aroundToolRound → ToolRoundExecutor → ToolExecutor → 保存完整工具结果
                 │    └─ 无工具调用：若仍有 steer，继续推理；否则结束
                 └─ 提交 COMPLETED → Reply
       finally：解除本轮补充入口、释放 executionLock

ChatController.history → Harness.history → FileConversationStore.load
```

ConversationService 负责会话调度与持久化会话身份，通过 FileSessionStore 保存项目绑定和活动信息，查询旧会话时可读取历史推导未绑定的会话信息。通过 Runtime.chat 进入原有的 Runtime → Harness 生命周期。conversationId 是执行身份，由 Runtime 转交 Harness 用于恢复与持久化；Runtime 不接收 Session，也不操作等待集合或执行锁。Harness 准备 AgentExecution 后调用 Runtime 的私有 runLoop 方法。会话调度不使用后台消费者。请求线程阻塞获取对应 Session 的锁，执行自己的消息并直接返回自己的结果；不同会话独立执行。

| 位置 | 负责 |
| --- | --- |
| ConversationService / Session | 会话定位、普通请求串行、等待输入登记、提升 steer |
| AgentRuntime | 模型/工具循环、响应协议检查、实际调用预算、意图和结果的检查点顺序 |
| Harness.aroundRun | 历史恢复、终态提交和本轮生命周期；Harness.history 提供历史查询 |
| Harness.aroundReasoning | 提交新增消息，调用 Builder，保存视图及处理位置后请求模型 |
| Harness.aroundModelCall / aroundToolRound | 真实模型和完整工具组的执行边界 |
| FileConversationStore | 权威聊天和检查点，按 turn ID 原子保存 |
| WindowContextBuilder | 统一组装固定输入、已有视图和新增消息，不归档原始轮次 |
| FileContextStore | 原子保存派生视图和原始消息处理位置 |

普通聊天的 Session 锁覆盖完整 Runtime.chat 调用，包括恢复、模型调用和所有提交；finally 保证异常或 Error 后释放。历史接口直接调用 Harness.history，读取存储中已成功保存的检查点，可能看到 RUNNING 轮次；它不创建 Session，也不等待本轮完成。默认文件存储按单个 turn 原子替换，不承诺跨 turn 的事务快照。不得在释放锁时直接删除 Session，因为其他请求可能仍引用它。

## 普通输入与 steer

客户端为每条普通输入生成 UUID。POST /api/chat 携带 conversationId、messageId、message、projectPath。Session.waiting 只保存尚未取得执行资格的消息；普通请求取得锁后通过 remove(messageId) 原子认领。Session 与输入登记均为进程内状态，进程重启不恢复排队请求，不自动重放外部副作用。

POST /api/steer 只携带 conversationId、messageId。它查找已有 Session；当前有执行中的任务，并且从 waiting 成功 remove 后，才将原消息加入本轮 CopyOnWriteArrayList。它不获取 executionLock，也不允许重新提交另一份正文。已经开始执行、不存在或已提升的消息返回 409。

被提升的原聊天请求仍等待 executionLock；随后发现消息已移出 waiting，返回 202 与 STEERED 回执。它不会创建独立 turn，也不会伪装成执行失败。steer 接收回执表示“已转为补充”，不表示模型已经应用。

每轮创建独立的补充列表，由 AgentExecution 持有共享引用。Harness 每次推理前只取第一条，保存为本轮 user 检查点，保存成功后再移除。工具意图与结果之间不插入用户消息。模型返回无工具调用文本时还会检查剩余补充，必要时继续推理；这些调用仍计入原有预算。

补充采用尽力处理语义，不实现原子的接收截止：最后一次检查后极晚到达的补充可能未生效。每轮列表在运行结束后解除关联，未应用内容不自动进入下一轮。普通 UUID 仅用于定位输入，不是持久幂等键；当前客户端不自动重试。

## 工具轮

内置工具与用户工具统一通过 `ToolBinding` Bean 接入。`AssistantAutoConfiguration.toolRegistry` 收集全部 `ToolBinding` Bean，交给 `ToolRegistry` 按名称登记并检查重名。注册的工具全部对模型可见，用户工具与内置工具同名时启动失败。`ToolRegistry` 同时供推理准备和实际执行查找。`AgentRuntime` 先提交模型的调用意图，再进入 `AssistantHarness.aroundToolRound()`。Harness 创建本轮 ToolContext，发出 ToolsStarted，调用 `ToolRoundExecutor`，将结果转换为 ToolResponseMessage 提交，最后发出 ToolsFinished。

`ToolRoundExecutor` 把连续的 READ 调用提交给共享线程池。遇到 WRITE、SHELL 或未知工具时，先收齐前一批结果，再单独处理当前调用。工具结果按原调用顺序返回。这个顺序约束在同一批调用内生效，会话之间仍可独立执行。只有明确声明 READ 且支持并发的工具进入线程池。

`ToolExecutor` 执行单个回调，保留调用 ID、完整结果和取消传播。普通错误和只读队列拒绝提交分别形成失败结果。批次取消时尝试中断尚未结束的只读任务，后续批次停止执行。工具执行类型用于调度，文件权限和 Shell 隔离由工具后端负责。

### 编码工具

工具名称、参数和描述沿用 AgentScope Java 的 `agentscope-harness` 中的 `FilesystemTool` 和 `ShellExecuteTool`。`AssistantAutoConfiguration` 导入 `BuiltinToolConfiguration`，其中每个工具 Bean 明确声明回调和执行类型。默认注册表获取全部绑定时会创建共享的 `WorkspaceFiles` 和 `SandboxShell`，镜像名在此时检查，工作区在工具调用时从 ToolContext 取得。内置配置保留延迟初始化，用户提供完整 `ToolRegistry` 时由该注册表决定模型可见和可执行的范围。模型定义与执行回调来自同一个注册表。Harness 统一组织工具批次、进度事件和结果提交，具体工具后端执行路径、隔离和超时检查。[AgentScope Java 许可](licenses/Apache-2.0.txt)

| 类型 | 工具 | 执行位置 |
| --- | --- | --- |
| READ | `list_files`、`glob_files`、`grep_files`、`read_file` | `WorkspaceFiles` 中的工作区目录 |
| WRITE | `write_file`、`edit_file` | `WorkspaceFiles` 中的工作区目录 |
| SHELL | `execute` | `SandboxShell` 按项目路径创建并复用的 Linux 容器 |

工作区由每次请求的 projectPath 提供，和助手数据目录分开。直接文件操作接受相对路径，逐段拒绝 `..` 和符号链接，搜索跳过符号链接及特殊文件。read_file 流式分页读取 UTF-8 文本（可超过 1 MiB），超长单行使用 char_offset 续读；写入和编辑仍限制 1 MiB。写入创建新文件，编辑要求文本唯一匹配或显式指定全部替换。编辑原子替换目标目录项。

`execute` 根据规范化项目路径生成稳定容器名，首次调用使用 YAML 配置的镜像（默认 `ubuntu:24.04`）创建并启动容器，本地缺少时自动拉取，拉取与创建共限时 300 秒；已有容器会校验项目标签、镜像及项目挂载，已停止时重新启动。容器仅将项目目录可写绑定到 `/workspace`，使用独立的默认桥接网络，不挂载 Docker socket 或额外宿主目录。容器根文件系统可写，以 root 用户支持安装项目依赖，保留 CPU、内存和进程数限制，完整采集命令输出。LLM 只提供普通命令，程序用 ProcessBuilder 参数列表调用 `docker exec ... /bin/sh -c command`，不经宿主 Shell 解析。项目修改同步到宿主机，容器依赖保留。超时或取消时停止整个容器，下一次调用重新启动。Docker 错误直接形成工具错误，不回退到宿主机执行。

## CLI 纯文本流

POST /api/chat/stream 使用与普通聊天相同的请求体和会话调度，返回 UTF-8 纯文本。Controller 将请求自己的事件回调通过 ConversationService、Runtime 和 Harness 交给 AgentExecution。Runtime 和 Harness 同步发出事件，Controller 写出相应文本并立即 flush。

RUNNING 提交后发 TurnStarted。Runtime 在预算检查通过、实际调用计数增加后发 ModelStarted，CLI 使用该计数显示调用序号。每批工具在执行前发 ToolsStarted，复用模型给出的名称和参数。工具结果提交后发 ToolsFinished，携带有序工具结果和执行耗时。Controller 根据每条结果的成功状态统计失败数，并显示结果摘要。COMPLETED 提交后发 TurnCompleted，使用同一份 Reply 输出回答。

事件只在当前请求内传递，不写入对话存储。被提升为 steer 的请求返回空的 HTTP 202。当前轮保存补充消息后发出含 turnId 和 messageId 的内部 TurnSteer，CLI 跳过该事件。Harness 退出时若有异常，发 RequestFailed 并沿用失败记录的原因。首段文本发出前由现有 HTTP 错误处理返回状态，发出后则把失败写在流尾。

## 会话信息与恢复入口

`ConversationInfo` 保存 conversationId、projectPath、title、createdAt 和 updatedAt；`FileSessionStore` 在 `data/sessions/<编码id>.json` 原子保存，轮次格式不变。ConversationService 在会话锁内检查或创建项目绑定，绑定提交成功后才进入 Runtime；不同项目的后续请求返回 409。完成或失败后尽力更新活动时间，不改变已提交结果。

Controller 提供 `GET /api/conversations`（全部会话，按活动时间倒序）和 `GET /api/conversations/{id}/session`（指定会话信息，缺失为 404）。现有历史接口仍返回完整轮次。查询不会创建 Session 或修复 RUNNING；恢复客户端选择原 ID、切换原项目目录后，下一条消息进入原有执行链路。从 Runtime 开始无需区分新建和恢复。

缺少元数据的历史会话在查询时返回空 projectPath，标题和时间从旧记录推导；首次携带明确项目目录继续聊天时持久化绑定，不迁移或重写旧 checkpoint。内存中的锁、排队消息、线程不会恢复。

## 状态所有权、检查点与恢复

| 数据 | 权威来源与写入者 |
| --- | --- |
| 已提交消息和状态 | FileConversationStore；Harness.commit 保存成功后才推进 AgentExecution.turn |
| 派生上下文视图 | FileContextStore 保存；Harness 在视图保存成功后推进内存状态 |
| 实际模型调用次数 | AgentExecution.modelCalls，由 Runtime 在真实请求前增加 |
| 尚未应用的 steer | 本轮临时列表；不替代聊天记录 |
| 临时模型输入 | WindowContextBuilder.ModelContext |

原始轮次仅有一个已提交 turn 游标。派生视图记录 sourceTurnId/sourceMessageCount，恢复时仅补齐该位置之后的原始消息；它不替代原始聊天，也不保存系统提示词和工具定义。checkpoint 保存失败立即停止，工具意图提交成功后才执行外部工具。每次最终请求检查字符预算，失败请求也计入 modelCalls；到达调用预算后不执行没有后续推理机会的新工具。

状态为 RUNNING → COMPLETED / FAILED。恢复残留 RUNNING 时保存失败记录，不自动重放可能已发生的工具副作用。

## 错误与观测

普通工具业务错误由执行器转换成工具结果；serial@1.1.0 继续传播取消异常，旧 serial@1.0.0 不变。模型异常、预算耗尽、工具协议错误、聊天或 steer 检查点保存失败停止本轮。失败终态再次保存失败时附加 suppressed 异常。

日志在拥有对应操作的方法中直接记录 turnId、stage、模型次数、耗时、releaseId/configurationId，以及已应用补充的 messageId。上下文日志记录新增消息数和视图消息数，不替代原始记录或视图检查点。日志不另存完整模型输入或工具结果。

## 存储与启动配置

OpenAI 兼容适配器支持通过 application.yml 中的 assistant.model.reasoning-effort 设置请求的 reasoning_effort，配置值随模型快照记录。DeepSeek V4.1 Flash 可用 none 关闭思考模式，通过现有的工具执行循环。

一个进程拥有一个本地数据目录。每个 turn 一个 JSON 文件，以同目录临时文件原子替换；不承诺 fsync 或断电级持久化。会话目录使用 Base64 URL 编码。历史裁剪不删除原始文件；工具请求与结果的 ID 和配对保持。

新轮次使用 `schemaVersion=3`。旧格式 1 和 2 可以直接读取，读取时忽略旧 manifest。只读取历史时保留磁盘原文件。中断轮次恢复为 FAILED 时，按新格式写回原 turn 文件。

`application.yml` 提供系统提示词路径、模型选项、执行预算、工作区与 Shell 镜像配置。用户提供同类型的 Bean 时优先使用该 Bean。默认 ToolRegistry 接入全部工具 Bean，向模型暴露工具定义，并给执行器提供同一份回调。

## 验证范围

只运行本次变更相关的本地小检查：普通 HTTP 聊天和历史、输入 ID 校验、steer 回执与失败响应、多条补充逐步应用、最终回答后的补推理、工具配对、检查点失败停止、预算与旧记录兼容。既有并发测试保留为按需入口，不默认运行；不访问外部模型、启动浏览器或运行完整测试集。

## 本地排队演示

默认 demo 模型使用 demo@1.1.0，每次模型调用在请求线程阻塞 3 秒后执行原演示逻辑；旧 demo@1.0.0 实现保留。延迟只在演示模型中生效，真实模型与会话调度不增加等待。发生工具调用或应用 steer 时会继续调用模型，每次仍等待 3 秒。客户端可通过纯文本流观察每轮处理进度，通过历史接口读取已保存的对话。

## 独立人格定义

`Personality` 保存不可变的核心设定、表达约定和对话示例，`text()` 按固定顺序生成文本。`Personality.lively()` 提供内置人设。人格仍是独立内容，接入 `WindowContextBuilder` 有待实现。

## 已移除：独立备注记忆

不再提供记忆组件、备注召回/写入、memory 配置或记忆字符预算。WindowContextBuilder.prepare(systemPrompt, toolDefinitions, view, appendedMessages, projectPath) 使用持久化视图和新增消息，完整轮次由原有存储链路独立保存。旧 data/memory 文件不删除。原来仅用于报告记忆故障的 Reply.warnings 字段一并移除。

## 增量上下文视图

首次、新轮次、恢复均调用同一 prepare：系统提示词和工具定义由外部每次提供，Builder 合并已有视图和新增消息，统一构造 Prompt。旧的 activeTurn 全量输入、原始 history 快照、成功轮次筛选及 recent-turns/history-chars 配置已移除。ConversationService 的运行中补充输入对象现名 SteerBuffer，与上下文视图无关。

原始记录提交成功后，AgentExecution 只收集新增消息；每次推理前，Harness 先保存 data/contexts/<编码会话ID>/view.json，再更新内存视图并清空新增集合。视图检查点包含 view、sourceTurnId、sourceMessageCount。两类文件分别原子提交，不提供跨文件事务；原始记录领先时，恢复只补齐未纳入视图的尾部。没有视图的旧会话使用空视图及已有原始消息初始化，不改写旧消息内容。

恢复残留 RUNNING 仍转为 FAILED，不重放工具。所有状态的消息都可进入视图；没有结果的工具意图仅在派生视图中补齐“结果未知”响应，保留原始调用和原始记录。ContextView 保存六字段 JSON 摘要对象和带 turnId 的保留消息。Builder 只对 appendedMessages 处理大结果，由 ToolResultStore 发布内容寻址的项目文件；compact 显式更新旧摘要并保留近期轮次。compact 已通过现有 ChatModel 请求并严格校验 JSON 摘要；prepare 已复用现有字符上限自动触发 compact；大结果阈值和摘要统一计数暂未接入。旧版列表或文本摘要视图从完整原始记录重建，原始游标与视图长度保持独立。Runtime 的最终 64000 字符保护仍在。
