# 当前架构

本文只描述已实现的职责与约束。

## 依赖与执行边界

`web → core → port`；core 复用 versioning 的不可变快照和指纹函数；component 实现 port；config 负责装配。

core 不依赖 component、HTTP、文件路径、模型厂商类或业务工具名称。Runtime 和 Harness 通过每次 run 传入的 RuntimeHooks 连接，没有构造器循环依赖、中间件注册器或优先级系统。

```text
ChatController → AssistantHarness.chat → ConversationGate（finally 释放锁）
  └─ aroundRun：恢复、保存 RUNNING、召回记忆
       ├─ AgentRuntime.run：有界模型/工具循环
       │    ├─ aroundReasoning
       │    │    ├─ ContextBuilder.prepare：每步重建临时模型视图
       │    │    └─ aroundModelCall → ChatModel
       │    ├─ checkpoint：先提交 assistant 意图
       │    └─ aroundToolRound → ToolExecutor → checkpoint：提交工具结果
       └─ 提交 COMPLETED → MemoryModule.observe → Reply
```

| 位置 | 负责 | 调整示例 |
| --- | --- | --- |
| AgentRuntime | 循环推进、模型协议检查、实际请求预算、意图与结果的检查点顺序 | 停止条件、模型响应格式 |
| Harness.aroundRun | 恢复、终态提交、记忆失败降级与本轮生命周期 | 中断处理、记忆补偿时机 |
| Harness.aroundReasoning | 把当前执行快照交给上下文策略，再启动推理 | 上下文构造时机 |
| ContextBuilder.prepare | 当前模型输入和选择证据，不修改聊天事实 | 历史选择、记忆裁剪、摘要策略 |
| Harness.aroundModelCall | 包装真实模型请求，当前记录阶段与耗时 | 请求策略、错误分类；网络超时仍由适配器实现 |
| Harness.aroundToolRound | 包装一组真实工具执行 | 整组执行权限、停止策略 |
| ToolExecutor | 同一允许列表控制暴露与执行，工具顺序及普通错误结果 | 单个工具失败、输出截断 |
| ConversationStore | 单个 turn 的原子替换 | 保存与读取故障 |
| MemoryModule | 长期记忆的召回与更新 | 检索质量、记忆范围与格式 |
| config / manifest | 启动选择、版本匹配和参数冻结 | 发布组合、组件版本 |

RuntimeHooks 是明确的调用边界，不是通用插件框架。默认包装只调用一次真实操作，没有自动重试。模型包装接收 Prompt 和真实调用函数；即使以后包装内重复调用或调整请求，预算仍在每次真正请求之前检查。

## 状态所有权与提交

每轮创建一个 AgentExecution，不能挂在单例 Bean 的可变字段中。

| 数据 | 权威来源 | 写入者 |
| --- | --- | --- |
| 已提交消息与状态 | ConversationStore；AgentExecution.turn 是最近成功提交的游标 | Harness.commit |
| 恢复历史、召回结果 | 本轮固定的只读列表 | Harness 在恢复、召回阶段设置 |
| 实际模型调用次数 | AgentExecution.modelCalls | Runtime，在真实请求前递增 |
| 长期记忆 | MemoryModule 的存储 | MemoryModule.observe |
| 本次模型输入 | ContextBuilder.ModelContext | ContextBuilder，每次推理重新构造 |
| 版本与参数 | 本轮 ConversationTurn.manifest | 启动装配后固定 |

Runtime 不再维护另一份可变消息列表，也不返回重复的调用次数；Harness 从执行状态和已提交轮次生成回答。checkpoint 每次追加一个消息，保存成功后才推进游标。提交失败立即退出，失败记录保留最后成功提交的消息；不把未保存的工具结果当成已提交事实。

对话轮次状态是 RUNNING → COMPLETED / FAILED。恢复时将残留 RUNNING 标记 FAILED，并更新本轮历史视图。中断时外部工具结果可能未知，不自动重放。回答成功提交后才更新记忆，记忆故障不回滚回答或重新调用模型。

## 上下文与预算

ContextBuilder.prepare 接收恢复历史、召回列表和当前已提交轮次的全部消息。默认 window@1.0.0 仍选择最近完整成功轮次和有界记忆，保持相同选择算法；prepare 增加选择证据，并接上完整的本轮消息。

原 build(history, memories, userMessage) 契约保留。只实现 build 的自定义组件由默认 prepare 适配：每步调用 build，再追加本轮 assistant/tool 交换；选择证据标记为 unreported，不推测选择结果。需要基于工具结果动态调整上下文时覆盖 prepare。

临时模型视图不写回对话，不允许通过上下文裁剪删除原始记录。默认策略不裁剪本轮工具历史，保留工具请求与结果的 ID 和配对。当前持久协议只支持文本和工具消息；供应商专属推理元数据、多模态消息需要先扩展数据协议。

最终发送请求前检查字符预算；预算不含供应商封装和工具定义开销，尚未精确计算 token。模型实际请求（包括失败请求）计入 modelCalls。达到最后请求预算但仍要求工具时，保存意图后停止，不执行没有后续推理预算的新工具。外层循环同时保留固定上限。

## 错误、观测与清理

| 故障 | 当前行为 |
| --- | --- |
| 模型网络异常、非法响应、预算不足 | 停止，尽力保存 FAILED，原异常继续传播 |
| 普通工具参数或业务异常 | 执行器返回失败内容，模型可以继续纠正 |
| serial@1.1.0 收到取消异常（包括工具反射包装中的取消） | 向外传播，停止本轮；serial@1.0.0 保留旧行为 |
| 对话保存失败 | 停止，不执行后续工具或模型；保存失败终态再次出错时附为 suppressed |
| 记忆召回失败 | warning，使用对话历史继续；取消异常继续传播 |
| 完成后的记忆更新失败 | warning，保留已提交的成功回答 |
| 普通诊断日志 RuntimeException | 忽略日志故障，不影响权威提交 |

阶段日志关联 turnId、stage、modelCalls 和耗时；每轮记录一次 releaseId/configurationId。召回阶段记录记忆内容摘要的 SHA-256 列表，上下文阶段记录选中/排除的 turn ID 和记忆索引，索引对应本轮召回摘要列表。工具阶段记录调用 ID 与名称，结果复用已有检查点。

日志默认输出终端，不作为恢复依据，不保存第二份工具结果或完整模型输入。配置指纹及召回摘要用于定位差异，不能保证复现外部返回或还原已经改变的记忆内容。

锁由 ConversationGate 的 finally 释放，覆盖 RuntimeException、取消和 Error。当前没有额外的每轮外部资源；未来新增资源必须在拥有它的 around 边界用 finally/try-with-resources 清理。当前没有 HTTP 取消入口，也不承诺中断一个不响应取消的同步工具。

## 文件与并发

每个 turn 一个 JSON 文件，同目录临时文件加原子替换；按 startedAt、turn ID 排序恢复。会话目录名采用 Base64 URL 编码，工具不提供文件路径。没有 fsync，不承诺断电级持久化。

单进程的 64 条带锁覆盖恢复、模型、提交与记忆更新。历史读取也经过同一锁。条带碰撞可能使无关会话暂时串行；两个进程不能共用数据目录。

## 组件与版本

默认 Bean 用 ConditionalOnMissingBean 退出，用户组件优先。模型复用 Spring AI ChatModel，工具复用版本化 ToolCallback。工具名称/版本精确匹配清单，未知、重复版本拒绝启动，没有 latest 回退。热加载、后台工作流和动态插件安装未实现。

新默认发布组合是 releases/harness-v2.yml（assistant-harness-1.1.0），选择 serial@1.1.0。baseline-v1.yml、compact-v1.yml、提示词资源和旧执行器行为保留。window 的选择算法未变，继续使用 1.0.0，由实现指纹标识观测与适配代码修订。

新轮次仍使用 schemaVersion=2 固定 manifest；schemaVersion=1 保持可读且不补造版本。manifest 包含提示词原文/校验和、组件和工具版本、脱敏参数、类指纹及整体配置指纹。新骨架增加 AgentExecution、RuntimeHooks、ContextBuilder 契约指纹；组件指纹覆盖其继承实现和直接成员类，仍不代表所有传递依赖或外部服务版本。

回退骨架行为需要切回对应 Git 修订和清单；单独选择旧清单只切换组件组合。详见 VERSIONING.md。

## 轻量验证

本次只运行 15 项本地小检查：Runtime 的结束/协议/预算/工具意图与包装调用预算；Harness 的逐步上下文、工具结果保存失败、记忆更新降级、恢复不重放；上下文选择证据、执行器取消版本差异、版本快照保留、自定义 Bean 替换。

没有运行完整测试集、并发、浏览器、外部网络或真实模型检查。
