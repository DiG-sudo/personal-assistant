# 当前架构

本文描述已经实现的骨架与约束，不将尚未实现的扩展写成现有能力。

## 依赖方向

`web → core → port；core → versioning 的不可变快照类型`；`component → port`；`config` 装配全部实现。

`core` 不依赖 `component`、HTTP、磁盘路径或模型厂商类。Spring AI 的消息和工具类型作为现成协议复用。Runtime 与 Harness 不注册业务工具，也不根据工具名判断业务是否完成。

## 一轮聊天

1. HTTP 入口检查会话 ID 和输入长度。
2. Harness 获取会话锁，加载历史。
3. 上次残留 RUNNING 轮次标记 FAILED，保留意图和已有结果。
4. 调用记忆组件 recall。失败时产生 warning，继续使用聊天记录。
5. 创建并保存 RUNNING 轮次，将启动时已核对的 ExecutionManifest 固定到本轮。
6. 上下文组件构造模型视图，最后放入当前 UserMessage。
7. Runtime 调用模型，明确禁用 Spring AI 内部自动工具执行。
8. 模型返回工具请求时，保存 AssistantMessage 意图后执行注册工具；保存 ToolResponseMessage 后继续推理。
9. 普通最终文本完成最后一次检查点后，Runtime 只返回模型调用次数。Harness 从已提交检查点标记 COMPLETED 并生成回答，不重复传递消息。
10. 调用记忆组件 observe。更新失败只产生 warning，不重新执行模型。
11. 释放会话锁，返回回答和 warnings。

模型响应、持久化和工具输入是实际边界。运行中不反复查询或校验 Harness 已经建立的内部状态。

## 状态所有权

| 数据 | 权威来源 | 写入者 |
| --- | --- | --- |
| 已保存会话轮次 | ConversationStore | Harness |
| 本轮运行消息 | Runtime 的工作列表 | Runtime |
| 当前已保存检查点游标 | Harness | Harness |
| 长期记忆 | 当前 MemoryModule 实现 | MemoryModule.observe |
| 本次模型上下文 | ContextBuilder 的临时结果 | ContextBuilder；Runtime 追加本轮消息 |

Runtime 的工作状态和已提交检查点有不同生命周期。检查点保存成功后才推进 Harness 游标；提交失败即退出，不使用未提交状态继续推理。持久化格式包含 schemaVersion，读取未知版本时拒绝静默解释。

对话记录保留历史事实；长期记忆可以另行提取、迁移和替换；上下文是有界视图，不是第三份可独立编辑的记忆。

## 工具边界

ToolExecutor.callbacks 与 execute 必须使用同一允许列表。默认实现从版本化 ToolCallback Bean 收集候选，按发布清单精确选择。重复名称或缺失版本导致启动失败。禁用工具不会被暴露给模型，也无法通过猜测名称执行。

默认串行执行所有工具，并保持模型请求顺序。普通工具参数或执行错误转换为结果，交给模型修正；Error 与持久化故障传播到生命周期边界。

工具返回值在默认执行器内截断；保存的也是这个有界结果。若需要完整原始产物，应由具体工具保存到独立产物存储并返回引用。

## 上下文与预算

上下文组件只选择 COMPLETED 的完整轮次，保留工具请求与结果配对。固定 system prompt、选中的记忆、历史轮次和当前输入构成初始请求。Runtime 每次调用前检查总消息字符上限。

默认上下文策略不裁剪本轮已执行工具历史。超过上限时停止并保存 FAILED，防止反复提交相同超限请求。精确 token 预算尚未实现。

最后一个可用模型调用若继续请求工具，先保留意图，再停止；不会在没有后续模型预算时执行新的外部操作。

## 文件与并发

每个 turn 一个 JSON 文件，每次检查点原子替换同一个路径。按 startedAt、turn ID 排序恢复。会话目录名采用 Base64 URL 编码，工具不能提供文件路径。

会话锁是单进程的 64 条带锁，覆盖恢复、模型调用、持久化和记忆更新。读历史接口也经过同一锁，避免暴露运行中不同检查点混合视图。条带碰撞可能使无关会话暂时串行。

原子重命名保证进程中断时不会读到半个 JSON 文件；未实现 fsync，不能承诺断电级持久化。

## 扩展方式

默认组件放在自动配置中，用户配置优先。扩展包新增 Bean，默认组件用 ConditionalOnMissingBean 退出；改变内置选择可调整配置。核心类保持普通 Java 类，由装配层显式构造。

插拔在进程启动时确定。新工具用 VersionedToolCallback 包装原生 ToolCallback；记忆用 MemoryModule；模型复用 ChatModel；入口直接调用 Harness。热加载、插件安装、自动修改代码不属于当前骨架。

## 测试对应

- AgentRuntimeTest：普通结束、工具配对、预算、模型边界、先保存意图。
- AssistantHarnessTest：恢复、会话串行、失败保存、记忆故障隔离。
- ContextAndStorageTest：上下文完整轮选择、JSON 恢复、会话隔离、数据版本。
- RegisteredToolExecutorTest：允许列表、重复名、顺序、截断、参数失败。
- ComponentWiringTest：自定义 Bean 替换、追加工具、配置校验。
- ChatApiTest：真实 Spring 应用装配、HTTP 校验、备注与工具链路。

- OpenAiModelAdapterTest：本地 HTTP 替身验证真实适配器的端点、手动工具循环与无自动重试。

## 版本与运行快照

- application.yml 使用 Spring 配置导入选择 releases/baseline-v1.yml 等冻结清单。
- PromptSnapshot 在启动时读取并验证 SHA-256，默认上下文组件和快照共用同一份内容。
- ComponentRevision 可在实现类或 Bean 工厂方法声明；实现类优先。装配后核对记忆、上下文、存储和执行器与清单相符。
- VersionedToolCallback 绑定实际回调、版本、Schema 和实现类指纹。候选可并存多个版本，实际只暴露选中的版本。
- ExecutionManifest 保存发布 ID、提示词原文与摘要、组件和工具元数据、参数及整体指纹；自定义参数由 ConfigurationSnapshot 显式提供，不反射读取字段或密钥。
- 参数使用 JSON 值；指纹计算对 Map 键排序，避免字典迭代顺序造成伪差异。
- Harness 创建轮次时固定 manifest，后续检查点、完成和失败均保留原快照。Runtime 无版本解析逻辑。
- 新聊天 schemaVersion=2 必须包含 manifest。旧 schemaVersion=1 可继续读取，manifest 保持 null，不能补写成当前版本。
- 聊天响应返回 releaseId/configurationId，组件查询返回当前 manifest。快照不会调用模型或工具，也不代表外部返回值可完全复现。

本次版本化验证只运行 VersioningTest 的四项本地检查及一项组件替换装配检查。未运行浏览器、外部模型、并发或完整回归测试。

## 精简后的职责来源

- 最后成功提交的 ConversationTurn 是本轮回答的来源。Runtime 不再返回重复消息 Result，complete() 直接使用当前记录的消息。
- ReleaseManifestFactory 从同一 BeanFactory 解析实际组件，不同时接收容器和容器中已有的五个组件。resolveNamedBean 保留 Spring 的组件选择规则。
- ChatController 的模式和工具信息读取已核对的 manifest，不额外注入模型、工具执行器来重复组装元数据。
- 默认工具注册表在构造时用 TreeMap 固定名称顺序，callbacks() 不再反复排序。
- 上下文和预算参数直接从配置记录转换为 JSON Map，模型连接参数继续使用不含密钥的白名单。

本次为行为等价的内部精简，提示词与发布清单保持原版本；原始实现保留在 Git 基线中，新提交和实现指纹区分源码修订。

本次精简复用 9 项本地小检查，覆盖普通结束、工具配对、预算、检查点失败、元信息、工具顺序和组件替换；未运行完整测试集、浏览器、并发或外部模型检查。
