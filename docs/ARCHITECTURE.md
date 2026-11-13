# 当前架构

本文只描述已实现的职责与约束。

## 依赖与执行边界

`web → core → port`；component 实现 port；config/AssistantAutoConfiguration 负责装配。ConversationService 管理每个 conversationId 的 Session；AgentRuntime 直接依赖具体 AssistantHarness；Harness 不反向注入 Runtime。

```text
ChatController → ConversationService.send
  └─ Session.waiting 登记输入 → 公平 executionLock
       ├─ 已提升为 steer：返回 202 STEERED，不再单独执行
       └─ Runtime.chat(conversationId, message, steers)
            └─ Harness.aroundRun(..., this::runLoop)
                 ├─ 恢复历史 → 固定 manifest、保存 RUNNING → recallMemory
                 ├─ Runtime.runLoop(AgentExecution)
                 │    ├─ aroundReasoning：提交一条 steer，再构建上下文
                 │    ├─ aroundModelCall → ChatModel
                 │    ├─ checkpoint：先保存 assistant 文本或工具意图
                 │    ├─ aroundToolRound → ToolExecutor → 保存完整工具结果
                 │    └─ 无工具调用：若仍有 steer，继续推理；否则结束
                 └─ 提交 COMPLETED → observeMemory → Reply
       finally：解除本轮补充入口、释放 executionLock

ChatController.history → Harness.history → ConversationStore.load
```

ConversationService 只负责会话调度，通过 Runtime.chat 进入原有的 Runtime → Harness 生命周期。conversationId 是执行身份，由 Runtime 转交 Harness 用于恢复与持久化；Runtime 不接收 Session，也不操作等待集合或执行锁。Harness 准备 AgentExecution 后调用 Runtime 的私有 runLoop 方法。这里没有 ThreadLocal、全局“当前会话”、线程池或后台消费者。请求线程阻塞获取对应 Session 的锁，执行自己的消息并直接返回自己的结果；不同会话独立执行。

| 位置 | 负责 |
| --- | --- |
| ConversationService / Session | 会话定位、普通请求串行、等待输入登记、提升 steer |
| AgentRuntime | 模型/工具循环、响应协议检查、实际调用预算、意图和结果的检查点顺序 |
| Harness.aroundRun | 历史恢复、记忆、终态提交和本轮生命周期；Harness.history 提供历史查询 |
| Harness.aroundReasoning | 每次推理前应用一条补充，再构建临时上下文 |
| Harness.aroundModelCall / aroundToolRound | 真实模型和完整工具组的执行边界 |
| ConversationStore | 权威聊天和检查点，按 turn ID 原子保存 |
| MemoryModule | 长期记忆，不复制聊天记录 |
| ContextBuilder | 模型输入视图，不改写已保存历史 |

普通聊天的 Session 锁覆盖完整 Runtime.chat 调用，包括恢复、模型调用、所有提交与完成后的记忆更新；finally 保证异常或 Error 后释放。历史接口直接调用 Harness.history，读取存储中已成功保存的检查点，可能看到 RUNNING 轮次；它不创建 Session，也不等待本轮完成。默认文件存储按单个 turn 原子替换，不承诺跨 turn 的事务快照。不得在释放锁时直接删除 Session，因为其他请求可能仍引用它。

## 普通输入与 steer

前端为每条普通输入生成 UUID。POST /api/chat 携带 conversationId、messageId、message。Session.waiting 只保存尚未取得执行资格的消息；普通请求取得锁后通过 remove(messageId) 原子认领。Session 与输入登记均为进程内状态，进程重启不恢复排队请求，不自动重放外部副作用。

POST /api/steer 只携带 conversationId、messageId。它查找已有 Session；当前有执行中的任务，并且从 waiting 成功 remove 后，才将原消息加入本轮 CopyOnWriteArrayList。它不获取 executionLock，也不允许重新提交另一份正文。已经开始执行、不存在或已提升的消息返回 409。

被提升的原聊天请求仍等待 executionLock；随后发现消息已移出 waiting，返回 202 与 STEERED 回执。它不会创建独立 turn，也不会伪装成执行失败。steer 接收回执表示“已转为补充”，不表示模型已经应用。

每轮创建独立的补充列表，由 AgentExecution 持有共享引用。Harness 每次推理前只取第一条，保存为本轮 user 检查点，保存成功后再移除。工具意图与结果之间不插入用户消息。模型返回无工具调用文本时还会检查剩余补充，必要时继续推理；这些调用仍计入原有预算。

补充采用尽力处理语义，不实现原子的接收截止：最后一次检查后极晚到达的补充可能未生效。每轮列表在运行结束后解除关联，未应用内容不自动进入下一轮。普通 UUID 仅用于定位输入，不是持久幂等键；当前客户端不自动重试。

## CLI 纯文本流

POST /api/chat/stream 使用与普通聊天相同的请求体和会话调度，返回 UTF-8 纯文本。Controller 将请求自己的事件回调通过 ConversationService、Runtime 和 Harness 交给 AgentExecution。Runtime 和 Harness 同步发出事件，Controller 写出相应文本并立即 flush。

RUNNING 提交后发 TurnStarted。Runtime 在预算检查通过、实际调用计数增加后发 ModelStarted，CLI 使用该计数显示调用序号。每批工具在执行前发 ToolsStarted，复用模型给出的名称和参数。工具结果提交后发 ToolsFinished，复用原有 ToolResponse，并携带本批工具的执行耗时。Controller 根据默认执行器的两种错误前缀统计失败数，并显示结果摘要。COMPLETED 提交、记忆更新结束后发 TurnCompleted，使用同一份 Reply 输出回答。

事件只在当前请求内传递，不写入对话存储。被提升为 steer 的请求返回空的 HTTP 202。当前轮保存补充消息后发出含 turnId 和 messageId 的内部 TurnSteer，CLI 跳过该事件。Harness 退出时若有异常，发 RequestFailed 并沿用失败记录的原因。首段文本发出前由现有 HTTP 错误处理返回状态，发出后则把失败写在流尾。

## 状态所有权、检查点与恢复

| 数据 | 权威来源与写入者 |
| --- | --- |
| 已提交消息、状态和 manifest | ConversationStore；Harness.commit 保存成功后才推进 AgentExecution.turn |
| 历史与记忆视图 | 本轮只读快照 |
| 实际模型调用次数 | AgentExecution.modelCalls，由 Runtime 在真实请求前增加 |
| 尚未应用的 steer | 本轮临时列表；不替代聊天记录 |
| 临时模型输入 | ContextBuilder.ModelContext |
| 长期记忆 | MemoryModule 的独立存储 |

每轮仅有一个已提交 turn 游标，没有第二份可写 transcript 或回答缓存。checkpoint 保存失败立即停止，工具意图提交成功后才执行外部工具。每次最终请求检查字符预算，失败请求也计入 modelCalls；到达调用预算后不执行没有后续推理机会的新工具。

状态仍为 RUNNING → COMPLETED / FAILED，schemaVersion 仍为 2，manifest 从 turn 创建起保持不变。恢复残留 RUNNING 时保存失败记录，不自动重放可能已发生的工具副作用。回答成功提交后再 observeMemory；记忆更新故障不回滚回答。

## 错误与观测

普通工具业务错误由执行器转换成工具结果；serial@1.1.0 继续传播取消异常，旧 serial@1.0.0 不变。模型异常、预算耗尽、工具协议错误、聊天或 steer 检查点保存失败停止本轮。失败终态再次保存失败时附加 suppressed 异常。记忆召回失败降级，完成后的记忆更新失败保留已保存答案。

日志在拥有对应操作的方法中直接记录 turnId、stage、模型次数、耗时、releaseId/configurationId，以及已应用补充的 messageId。上下文选择证据、记忆摘要仅为诊断，不替代权威检查点。日志不另存完整模型输入或工具结果。

## 存储与版本

OpenAI 兼容适配器支持通过 AI_REASONING_EFFORT 设置请求的 reasoning_effort，配置值随模型快照记录。DeepSeek V4.1 Flash 可用 none 关闭思考模式，通过现有的工具执行循环。

一个进程拥有一个本地数据目录。每个 turn 一个 JSON 文件，以同目录临时文件原子替换；不承诺 fsync 或断电级持久化。会话目录使用 Base64 URL 编码。历史裁剪不删除原始文件；工具请求与结果的 ID 和配对保持。

默认发布为 releases/session-v1.yml（assistant-session-1.0.0），组件版本、工具定义和提示词未改变。harness-v2.yml、baseline-v1.yml、compact-v1.yml 与旧提示词保留。新 manifest 增加 ConversationService 的实现指纹；原记录快照不被重写。旧 schemaVersion=1 可读且不补造版本信息。

默认 Bean 继续让位于用户组件；工具名称及版本精确匹配发布清单，没有 latest 回退。Runtime/Harness 骨架行为由新发布组合、Git 修订及实现指纹归因。没有动态插件、分布式 Worker、HTTP 取消、SSE 或后台任务系统。

## 验证范围

只运行本次变更相关的本地小检查：普通 HTTP 聊天和历史、输入 ID 校验、steer 回执与失败响应、多条补充逐步应用、最终回答后的补推理、工具配对、检查点失败停止、预算与版本快照。既有并发测试保留为按需入口，不默认运行；不访问外部模型、启动浏览器或运行完整测试集。

## 本地排队演示

默认 demo 模型使用 demo@1.1.0，每次模型调用在请求线程阻塞 3 秒后执行原演示逻辑；旧 demo@1.0.0 实现保留。延迟只在演示模型中生效，真实模型与会话调度不增加等待。发生工具调用或应用 steer 时会继续调用模型，每次仍等待 3 秒。前端在每条请求结束后刷新历史，不必等所有排队请求结束。

## 独立人格定义

component/personality/Personality 是尚未接入聊天链路的不可变数据类，实现版本为 personality@1.0.0。它通过 Ref 接收独立的人格内容版本，保存核心设定、表达约定和对话示例，并提供固定顺序的 text() 与内容 SHA-256。示例明确标记为风格参考，不进入真实聊天存储。

该类没有注册为 Bean，不加载资源或调用模型，也未改变 ContextBuilder、发布清单或执行 manifest。livelyV1() 显式返回内置 lively-companion@1.0.0，含完整三段内容和八组示例，内容在类中固定；外部资源加载、装配校验与上下文接入为后续工作，详见 [人格开发文档](../companion-agent/personality/README.md)。
