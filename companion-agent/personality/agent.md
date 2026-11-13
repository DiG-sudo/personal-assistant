# 人格模块：已定设计与代码范围

先读本文件，再按需读取列出的代码。完整提示词以源码为准；研究背景见 [README.md](README.md)，历史来源见 [REFERENCES.md](../REFERENCES.md)。根目录 [agent.md](../../agent.md) 和 [AGENTS.md](../../AGENTS.md) 的职责、风格与版本约束继续适用。

## 当前结论

| 已确定结论 | 涉及的本项目代码范围 | 状态 |
| --- | --- | --- |
| 人格是独立的不可变数据对象，包含核心设定、表达约定、对话示例三段 | [Personality.java](../../src/main/java/com/guodi/assistant/component/personality/Personality.java)：12—24 行，record 与构造器 | 已实现 |
| 实现版本 personality@1.0.0；具体人设 lively-companion@1.0.0，方向为活泼、跳脱、抽象及适量黑色幽默 | [Personality.java](../../src/main/java/com/guodi/assistant/component/personality/Personality.java)：11、26—76 行，livelyV1 | 已实现；不在本文重复维护完整人设 |
| 完整文本按三段固定顺序生成，UTF-8 SHA-256 直接从文本推导 | [Personality.java](../../src/main/java/com/guodi/assistant/component/personality/Personality.java)：78—87 行，text / sha256；[Fingerprints.java](../../src/main/java/com/guodi/assistant/versioning/Fingerprints.java)：15—22 行，sha256 | 已实现 |
| 显式声明内容版本，不使用 latest；复用既有版本身份 | [Personality.java](../../src/main/java/com/guodi/assistant/component/personality/Personality.java)：14—23 行；[ExecutionManifest.java](../../src/main/java/com/guodi/assistant/versioning/ExecutionManifest.java)：18—22 行，Ref / PromptSnapshot | Ref 已使用；人格尚未生成 PromptSnapshot |
| 已发布内容保留；人设变化新增内容版本，文本组织行为变化新增实现版本 | [Personality.java](../../src/main/java/com/guodi/assistant/component/personality/Personality.java)：livelyV1 / text；[PersonalityTest.java](../../src/test/java/com/guodi/assistant/PersonalityTest.java)：14—49 行 | 版本、旧对象稳定性和固定内容指纹已有检查 |
| 暂不接入上下文、不注册 Bean、不参与执行 manifest | 当前人格类与 [AssistantAutoConfiguration.java](../../src/main/java/com/guodi/assistant/config/AssistantAutoConfiguration.java)：83—93 行；[WindowContextBuilder.java](../../src/main/java/com/guodi/assistant/component/context/WindowContextBuilder.java)：prepare | 当前阶段边界；资源加载与上下文接入待开发 |

内置人设内容 SHA-256：`4fa10114de827ce9b4b730417404cbed357a3bb8ff9d76135ffc5cd541bbe3ee`。固定指纹以 PersonalityTest 为准；不要为覆盖旧内容而同步改掉旧版本检查。

## 读取与后续修改

只核对人格实现时，先读 Personality.java 与 PersonalityTest.java。只有涉及资源装配或上下文接入，才继续读上表的配置、Builder 与 manifest。外部项目当前主要用于设计溯源，无需重新检索所有角色预设。

已经确认的人格方向不再自动重新选型。可学习表达与真实用户经历归记忆；等待、回应时机与临时状态归行为。若改变已定结论，同步本文件和开发日志；具体算法、提示词和业务代码仍以各自版本为准。

最近验证：先前 `mvn -o -Dtest=PersonalityTest test` 编译及 3 个本地测试通过；尚未验证真实模型表现。本次仅新增设计索引，没有重跑模型或应用测试。记录日期：2026-10-29。
