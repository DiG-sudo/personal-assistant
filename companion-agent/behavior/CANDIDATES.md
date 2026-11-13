# 专门面向活人感的候选项目

检索日期：2026-10-29。星数为 GitHub 页面显示的约数。本轮按用户要求追加检索，以自然社交、持续陪伴、自主生活或情绪表达为目标筛选；候选不自动成为本项目设计决定。当前 MaiBot 主参考与已定两层设计保持不变。

## 筛选结果

| 项目 | Stars 约数 | 专门解决什么 | 本次证据与适用范围 |
| --- | --- | --- | --- |
| [N.E.K.O.](https://github.com/Project-N-E-K-O/N.E.K.O) | 3.1k | 面向现实环境的主动陪伴：找话题、分享内容、跟进未收尾话题，并结合人格生成表达 | 已读主动 service / delivery 关键段落；发现话题筛选与人格生成两阶段及用户介入后的放弃投递逻辑；优先作为新候选继续考察 |
| [Generative Agents](https://github.com/joonspk-research/generative_agents) | 22.3k | 仿真中模拟人物的记忆、日程、计划和社会互动，而非只被动回复 | 已读 Persona.move、plan 和反应判断；属于研究原型及模拟环境，不是开箱即用陪伴插件 |
| [MaiBot](https://github.com/Mai-with-u/MaiBot) | 6.7k | 自然接话、聊天节奏、选择回应/等待/结束 | 复用已核查本地仓库和行为 agent.md，不重复远程读取；仍为当前已定主参考 |
| [AIRI](https://github.com/moeru-ai/airi) | 50.9k | 数字角色与实时语音、多模态和游戏交互 | 复用 R38 的事件编排/通知与可见输出证据；本轮只刷新项目定位与星数，不重新抓取已读源码 |
| [KouriChat](https://github.com/KouriChat/KouriChat) | 3.3k | 角色化情感陪伴、逐句回复、表情和打断 | 已读人设与会话逻辑，确认流式逐句回送、加话打断、自然结束后的表情投递；当前 README 标为 1.5 重构开发版 |
| [LingChat](https://github.com/SlimeBoyOwO/LingChat) | 2.4k | 情绪化文字、动作及语音等角色呈现 | 已读情绪分类器与片段处理器；分类发生于输出标签/文本，不等于持续内在情绪或主动决策，主要适合表达层参考 |

当前问题若偏“会感知我的处境、找值得说的话”，N.E.K.O. 更直接；若偏“角色有自己的日程和行动连续性”，Generative Agents 的认知模块更直接。这是源码阅读后的适配判断，不是模型效果评测，也不构成替换当前主参考的决定。

## 插件与模块检索结果

[Kanadego/dsh-heartbeat](https://github.com/Kanadego/dsh-heartbeat)直接以日常交流活人感为目标，当前约 22 stars；执行门禁源码已在 R10 记录，属于贴题但低星的插件。[swaylq/human-harness](https://github.com/swaylq/human-harness)自述有作息、情绪和内驱，检索时为 0 stars，本轮未深入其实现，不列为高星或成熟依据。

本轮找到的高星实现主要是完整陪伴系统或研究型认知模块。纯写作去 AI 味的 Skill、仅有头像动画的项目、通用任务框架以及只因宿主高星的低星插件，未据此作为高星行为模块推荐。此结论限定本轮检索范围，不声称不存在其他项目。

## 已读取的源码范围与本地缓存

下列 8 份文件是选读源码快照，不是完整 Git checkout；仅在标明的函数/行号范围内形成结论。每份快照都核对了 Git blob SHA，位于主项目忽略的 references/source-cache，后续优先局部读取本地快照。星数来源为上方项目主页，机制来源为下表源码。

| 固定提交源码链接 | 本地读取位置 | 文件 blob SHA | 已读函数 / 范围 | 结论与边界 |
| --- | --- | --- | --- | --- |
| [Project-N-E-K-O/N.E.K.O: main_logic/proactive_chat/service.py](https://github.com/Project-N-E-K-O/N.E.K.O/blob/560f5e25d11f82f67feab03748b072ceb5cbb549/main_logic/proactive_chat/service.py) | [本地快照](../../references/source-cache/Project-N-E-K-O/N.E.K.O/560f5e25d11f82f67feab03748b072ceb5cbb549/main_logic/proactive_chat/service.py) | `8ce33b61d669b03558dc8afe23c6fadac68828da` | handle_proactive_chat；566—590、624—650、1880—1922、2140—2185、2485—2552、2570—2638 | 候选话题筛选与人格生成分阶段；可用未收尾话题跟进；生成/交付前处理用户重新参与，不能描述为定时必发。 |
| [Project-N-E-K-O/N.E.K.O: main_logic/proactive_chat/delivery.py](https://github.com/Project-N-E-K-O/N.E.K.O/blob/560f5e25d11f82f67feab03748b072ceb5cbb549/main_logic/proactive_chat/delivery.py) | [本地快照](../../references/source-cache/Project-N-E-K-O/N.E.K.O/560f5e25d11f82f67feab03748b072ceb5cbb549/main_logic/proactive_chat/delivery.py) | `fb1168b6bb1fdb6b7a10c3889feef138eaf67a2c` | _commit_proactive_delivery；294—345 | 按当前回合及用户参与时间提交输出；用户接管时可以放弃主动投递。未审计全部音频或发送链路。 |
| [joonspk-research/generative_agents: reverie/backend_server/persona/persona.py](https://github.com/joonspk-research/generative_agents/blob/fe05a71d3e4ed7d10bf68aa4eda6dd995ec070f4/reverie/backend_server/persona/persona.py) | [本地快照](../../references/source-cache/joonspk-research/generative_agents/fe05a71d3e4ed7d10bf68aa4eda6dd995ec070f4/reverie/backend_server/persona/persona.py) | `c4a3966fdd37f4e919d1203b74c8433e18a13a96` | Persona.move；185—231 | 感知、检索、计划、反思、执行是主循环；仅确认调用关系，反思子算法未在本轮展开。 |
| [joonspk-research/generative_agents: reverie/backend_server/persona/cognitive_modules/plan.py](https://github.com/joonspk-research/generative_agents/blob/fe05a71d3e4ed7d10bf68aa4eda6dd995ec070f4/reverie/backend_server/persona/cognitive_modules/plan.py) | [本地快照](../../references/source-cache/joonspk-research/generative_agents/fe05a71d3e4ed7d10bf68aa4eda6dd995ec070f4/reverie/backend_server/persona/cognitive_modules/plan.py) | `901d2433111b931401b77690191eb6d57a9739d9` | _long_term_planning / _should_react / plan；461—520、699—746、931—1007 | 新一天产生计划，遇到事件决定交谈/等待/继续；这是仿真环境中的角色生活机制，不是即插即用的陪伴插件。 |
| [SlimeBoyOwO/LingChat: src-tauri/src/ai_service/emotion/classifier.rs](https://github.com/SlimeBoyOwO/LingChat/blob/848fd3448c20ca32e0436a498d20e85f63393e7f/src-tauri/src/ai_service/emotion/classifier.rs) | [本地快照](../../references/source-cache/SlimeBoyOwO/LingChat/848fd3448c20ca32e0436a498d20e85f63393e7f/src-tauri/src/ai_service/emotion/classifier.rs) | `a2ccb403f6997478c1dd2f2f4f1f147a3222f65c` | EmotionClassifier.predict / run_inference；152—195、232—270 | 对情绪标签/文本做分类，输出置信度；这不等于有持续的内部情绪状态。 |
| [SlimeBoyOwO/LingChat: src-tauri/src/ai_service/message_system/processor.rs](https://github.com/SlimeBoyOwO/LingChat/blob/848fd3448c20ca32e0436a498d20e85f63393e7f/src-tauri/src/ai_service/message_system/processor.rs) | [本地快照](../../references/source-cache/SlimeBoyOwO/LingChat/848fd3448c20ca32e0436a498d20e85f63393e7f/src-tauri/src/ai_service/message_system/processor.rs) | `f318cb3e4f0c9014127aa54b29f1545d163a114c` | parse_and_classify_emotional_segments；114—184 | 将模型输出拆为情绪、正文、动作、翻译及语音文件信息，偏表达呈现。未完整审计语音生成和播放。 |
| [KouriChat/KouriChat: kourichat/logic/persona.py](https://github.com/KouriChat/KouriChat/blob/a6d0fea0e1a9d6c44623304dc837eb087055c735/kourichat/logic/persona.py) | [本地快照](../../references/source-cache/KouriChat/KouriChat/a6d0fea0e1a9d6c44623304dc837eb087055c735/kourichat/logic/persona.py) | `8706a070a42a0a08b45bdaf59896fef7b5611de5` | apply / _command_handler；全文 | 人设插件扫描角色定义、注册当前人格和人设命令；未据此断言存在自主日常生活模型。 |
| [KouriChat/KouriChat: kourichat/logic/session.py](https://github.com/KouriChat/KouriChat/blob/a6d0fea0e1a9d6c44623304dc837eb087055c735/kourichat/logic/session.py) | [本地快照](../../references/source-cache/KouriChat/KouriChat/a6d0fea0e1a9d6c44623304dc837eb087055c735/kourichat/logic/session.py) | `7c5a27bafaa8e6895221e0e1d7429f4d90ef786c` | apply / _drive / _handle；全文 | 逐句流式发送、支持用户加话打断和停止；表情在自然结束后发送，中断时清理待发表情。当前为 1.5 重构开发版。 |

未安装、运行这些新候选或调用付费模型。新增候选若最终选为主参考，再获取完整 Git checkout 并更新模块 agent.md；当前只保存已获取的相关文件，避免为筛选下载整个大仓库。

## DSH 专项补查

此前插件筛选结果补充见 [DSH-PLUGINS.md](DSH-PLUGINS.md)：鱼的记忆、鱼的心情、主动搭话和大肥鱼养成，以及星数与使用量的区别。
