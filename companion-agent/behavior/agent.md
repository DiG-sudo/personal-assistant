# 行为模块：已定设计与本地源码范围

先读本文件，再按结论定位本地源码；无需重新远程获取同一批文件。设计解释见 [README.md](README.md)，历史调查与完整 blob SHA 见 [REFERENCES.md](../REFERENCES.md)。根目录 [agent.md](../../agent.md) 和 [AGENTS.md](../../AGENTS.md) 继续适用。

## 本地参考基线

- 唯一主参考：MaiBot，目标是自然接话与“活人感”。
- 本地 checkout：[references/MaiBot](../../references/MaiBot)，上游 https://github.com/Mai-with-u/MaiBot.git。
- 固定提交：`2a9af62cec37035d29237acbe2cf3adceb700b27`，采用 detached HEAD；定位以该提交为准。
- 参考仓库独立保留 Git 数据，由主项目 .gitignore 排除；不自动 pull、切换分支或改变基线。
- 下列范围均为已核查源码。范围外的动态发言门控等算法尚未完整核查，不将调用点等同于全部实现已审计。

## 已确定结论及代码范围

下表“本项目涉及位置”表示当前职责或后续影响范围；行为组件尚未编码，不能据此宣称这些位置已实现社交式等待或静默。

| 已确定结论 | MaiBot 本地源码范围 | 本项目涉及位置 / 状态 |
| --- | --- | --- |
| 行为按提示词控制、事件与状态控制两层组织 | [maisaka_chat.prompt](../../references/MaiBot/prompts/zh-CN/maisaka_chat.prompt)：1—20 行；[scheduler.py](../../references/MaiBot/src/maisaka/turn_trigger/scheduler.py)：16—105 行 | 当前设计已定；行为 Prompt 与人格分别维护，事件组件从外部进入会话执行链 |
| 模型决定回应对象与下一步，发言时提供上下文和回复方向 | [maisaka_chat.prompt](../../references/MaiBot/prompts/zh-CN/maisaka_chat.prompt)：1—20 行；[reply.py](../../references/MaiBot/src/maisaka/builtin_tool/reply.py)：123—147、493—530 行，get_tool_spec / execute_reply | 后续行为提示词及动作协议；接入时才涉及 ContextBuilder.prepare 与 Harness.aroundReasoning |
| Replyer 依据人格、风格及当前话题生成表达，避免重复回复同一对象 | [maisaka_replyer.prompt](../../references/MaiBot/prompts/zh-CN/maisaka_replyer.prompt)：1—6 行；[maisaka_generator_base.py](../../references/MaiBot/src/chat/replyer/maisaka_generator_base.py)：532—624 行；[reply.py](../../references/MaiBot/src/maisaka/builtin_tool/reply.py)：328—366、493—530 行 | 保留本项目 Personality 人设；是否拆分实际模型调用尚未决定，新增调用必须纳入 Runtime 预算 |
| 连续输入可以先收齐，再判断如何接话；私聊与群聊策略分别取舍 | [scheduler.py](../../references/MaiBot/src/maisaka/turn_trigger/scheduler.py)：32—105 行，schedule_message_turn / _evaluate；[runtime.py](../../references/MaiBot/src/maisaka/runtime.py)：1650—1703 行 | 后续事件调度组件与 ConversationService；当前尚未实现连续消息批处理，不将群聊概率用于随机忽略私聊 |
| 等待与结束有不同语义；到期和新消息可按模式重新触发 | [wait.py](../../references/MaiBot/src/maisaka/builtin_tool/wait.py)：get_tool_spec / handle_tool；[stop.py](../../references/MaiBot/src/maisaka/builtin_tool/stop.py)：get_tool_spec / handle_tool；[runtime.py](../../references/MaiBot/src/maisaka/runtime.py)：1705—1810 行；[scheduler.py](../../references/MaiBot/src/maisaka/turn_trigger/scheduler.py)：32—79 行 | 后续动作结果、状态及唤醒设计；当前 Runtime.validateResponse 仍拒绝空回答，不能用空文本冒充静默 |
| 模型选择由运行时落实，不能只写在提示词里 | [reasoning_engine.py](../../references/MaiBot/src/maisaka/reasoning_engine.py)：367—455、2145—2166 行，_handle_planner_response_actions / pause_execution / stop_after_execution | Harness 的执行与检查点边界；具体行为工具按名称启用，业务工具名不写进 Runtime |
| 借鉴有事进入、无事能停，不直接复制群聊空闲退避 | [idle_backoff.py](../../references/MaiBot/src/maisaka/turn_trigger/idle_backoff.py)：16—91 行；[runtime.py](../../references/MaiBot/src/maisaka/runtime.py)：1193—1239 行 | 私聊规则待设计；不预设固定睡眠、后台轮询、情绪分数或亲密度模型 |

## 本项目接入位置

| 文件 / 方法 | 范围与责任 |
| --- | --- |
| [ConversationService.java](../../src/main/java/com/guodi/assistant/core/ConversationService.java)：send / Session | 会话串行、输入和 steer；事件调度位于 Harness 外 |
| [AssistantHarness.java](../../src/main/java/com/guodi/assistant/core/AssistantHarness.java)：aroundRun、aroundReasoning、aroundToolRound、checkpoint / commit | 恢复、上下文准备、执行和检查点生命周期；不是社交定时器 |
| [AgentRuntime.java](../../src/main/java/com/guodi/assistant/core/AgentRuntime.java)：39—96 行，runLoop / callModel / validateResponse | 模型和工具循环、实际调用预算及协议；新增等待/结束支持时必须显式设计协议 |
| [WindowContextBuilder.java](../../src/main/java/com/guodi/assistant/component/context/WindowContextBuilder.java)：prepare | 未来接入已选定行为内容；只构造临时视图，不改写历史 |

## 后续工作与记录规则

当前确定的是主参考、目标和两层职责。下一步明确私聊的发言、等待与结束结果，随后写行为 Prompt，再实现必要事件和状态控制；暂不接入上下文。模型调用次数、完整动作字段、状态持久化和恢复协议仍待设计。

继续工作时先查本文件与固定提交，使用 rg 定位具名函数、sed 读取对应范围。只在新问题超出已读范围或基线有意升级时补读，并同步记录结论、代码范围、实现状态。历史多项目调查不再自动展开。

本地 checkout 与文件完整性核对结果见 [本地参考说明](../../references/README.md)；参考项目未安装或运行，活人感效果尚未验证。记录日期：2026-10-29。

## 追加候选检索（尚未采纳）

2026-10-29 按用户要求补查专做活人感的高星项目，结果和精确源码范围见 [CANDIDATES.md](CANDIDATES.md)。N.E.K.O.、Generative Agents、KouriChat、LingChat 的 8 份关键源码已缓存到 references/source-cache，后续先读记录和本地快照。当前主参考仍是 MaiBot；此条是研究索引，不改变已定模块设计。

## DSH 陪伴插件补查

2026-10-29 结果见 [DSH-PLUGINS.md](DSH-PLUGINS.md)。复用 R06/R09/R11 的记忆、主动搭话及情绪源码证据；新增大肥鱼养成四份固定提交源码快照及精确已读范围。GitHub stars 与复制安装命令统计分别记录，未取得真实安装人数；这些仍是候选，不改变 MaiBot 主参考或当前实现。
