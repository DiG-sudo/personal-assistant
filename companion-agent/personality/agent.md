# 人格模块：已定设计与代码范围

[设计与研究](README.md) · [总体进度](../DEVELOPMENT.md)

- [Personality.java](../../src/main/java/com/guodi/assistant/component/personality/Personality.java) 保存核心设定、表达约定和对话示例。 `text()` 按这三个部分的顺序输出。
- `Personality.lively()` 提供活泼、跳脱、抽象并带适量黑色幽默的内置人设。完整内容以源码为准。
- [PersonalityTest.java](../../src/test/java/com/guodi/assistant/PersonalityTest.java) 覆盖输出顺序和内置内容。人格资源加载与上下文接入待开发。
- 用户资料和共同经历归记忆，主动联系、回应时机和临时状态归行为。
