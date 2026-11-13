# DeepSeek Harness 陪伴插件补查

检索日期：2026-10-29。按用户要求集中检索 DeepSeek Harness（DSH）插件。当前 MaiBot 主参考不变；本页为候选研究，不代表已采纳或已接入。

## 候选及人气口径

星数来自各仓库 GitHub 主页当次显示，不等于安装人数，也不把宿主或插件目录的星数算给插件。

| 插件 | GitHub stars | 用户可见作用 | 源码依据 |
| --- | --- | --- | --- |
| [鱼的记忆](https://github.com/Yuuyuko-uu/dsh-fish-memory) | 365 | 跨聊天保留事实，按相关性召回，随时间淡忘；纠正旧事实留痕 | 复用 REFERENCES.md R06：lib/index.js，recall / remember；未重复下载 |
| [鱼的心情](https://github.com/Yuuyuko-uu/dsh-feiyu-mood) | 184 | 对话后判断心情变化，保留持续情绪并随时间衰减，再影响表达 | 复用 R11：decay / judge / mountFor；判断会额外调用模型，不能称为零成本 |
| [主动搭话](https://github.com/Yuuyuko-uu/dsh-speak-up) | 165 | 用户没说话时也有机会在原会话开口；避开忙碌、安静时段和冷却 | 复用 R09：lib/host.js，门控 / 本地概率 / agent.followup；不是未完成事项跟进器 |
| [大肥鱼养成](https://github.com/ssamurias/dsh-fish) | 58 | 记录经历和自己的判断，修正旧认知；在时间窗内主动醒来并结合外部素材交流 | 本次补读下表四份文件的关键范围；不是模型权重训练 |
| [大肥鱼桌宠](https://github.com/QCYTSN/dsh-dafeiyu) | 548 | 桌面常驻角色、任务状态、动画和触摸互动 | 本次仅读 README.md，不当作已审计行为机制 |

同一作者的记忆、情绪和主动搭话三个插件，职责较清楚，适合分别理解我们的人格、记忆、行为设计。情绪是动态状态，不应每次改动都发布一个新的固定人格版本。此为适配判断，尚未改变本项目实现或确定接入方案。

[独立站“真装机周榜”](https://dpharness.com/ranking/installs)明确说明，它统计的是本站用户复制安装命令的人数。其 2026-10-23 起滚动七天窗口报告 1129 人；这不是安装成功数，也不是全球用户数。本轮没有取得可靠的实际安装人数，因此不把“很多人在用”写成已证实结论。

补查到的 ProjectKaren（30 stars）、dsh-humanized-deepseek-maid（10）、dsh-soul（9）、DeepSeek-Harness-biaoqingbao（4）仅做页面筛选，尚未读实现，不列为成熟或高星的技术依据。phfb/dsh-qq-bot 的 dsh-alive 与 R12 的 Kaitox326/dsh-alive 不是同一实现；前者当前为 0 stars，本轮只读项目说明，不将“未完事项关心”视为已经核查源码。

## 本次新增源码范围

固定提交：`88bd3e45eda43fdbb631472b4abd4fb5a77b080f`。以下为按提交保存的文件快照，不是完整 Git checkout；后续先读本地快照。只对列出的已读范围形成结论。

| 固定提交源码 | 本地文件 | Git blob SHA | 已读范围 | 结论与边界 |
| --- | --- | --- | --- | --- |
| [lib/scheduler.js](https://github.com/ssamurias/dsh-fish/blob/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/scheduler.js) | [本地快照](../../references/source-cache/ssamurias/dsh-fish/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/scheduler.js) | `e5e54825b0d1ea42391f284987a2f8bf4eb5519e` | startScheduler 94—200；wake 342—402、460—595 | 按时间窗、日程、配额和候选事件决定唤醒；lookBeforeWake 的调用先提供外部素材，处境注入动态 system 段，sc.prompt 向真实会话排队。未审计 browse.js 的完整检索、看视频和外部评论链路。 |
| [lib/derive.js](https://github.com/ssamurias/dsh-fish/blob/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/derive.js) | [本地快照](../../references/source-cache/ssamurias/dsh-fish/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/derive.js) | `3d9e57799215da73873662ad24bc09b05e615a23` | deriveSelf 262—376、446—486、636—696、762—792 | 经历导出当前状态；utterance 新增判断，revise 引用旧判断并标记修正/推翻；阶段受经过天数和事件门槛共同限制。只确认数据导出机制，不将作者的心理学命名当作效果验证。 |
| [lib/persona.js](https://github.com/ssamurias/dsh-fish/blob/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/persona.js) | [本地快照](../../references/source-cache/ssamurias/dsh-fish/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/persona.js) | `616a61ba0f6e1f5e5cbf84a909aa1d39efe0ce49` | personaLine / personaBudget 143—174；buildSections 180—258 | 当前身份、经历形成的判断、矛盾和未消化事件被渲染为提示词；仍然通过提示词影响模型，不是权重训练。 |
| [lib/identity.js](https://github.com/ssamurias/dsh-fish/blob/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/identity.js) | [本地快照](../../references/source-cache/ssamurias/dsh-fish/88bd3e45eda43fdbb631472b4abd4fb5a77b080f/lib/identity.js) | `36c600a47b9076a93db9dbe5c5f77bac743764df` | identityText 87—101；attachIdentity 178—208 | 读取身份文件并注册 systemPrompt.section，当前身份与本次唤醒处境分别注入。未照搬其文件缓存或全局 wakeText 状态设计。 |

更适合当前行为工作的参考顺序：先看“主动搭话”的事件触发与门控，再按需求考虑持续情绪；“大肥鱼养成”的认知演化涉及人格和记忆，不宜在这一轮整套引入。未安装或运行这些插件，未调用模型，未验证实际陪伴效果。
