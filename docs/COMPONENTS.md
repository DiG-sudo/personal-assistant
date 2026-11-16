# 组件开发指南

扩展配置和业务工具可以放在 `com.guodi.assistant.extension`。Harness 直接使用具体组件，Spring 在启动时装配。

## 新增工具

```java
package com.guodi.assistant.extension;

import org.springframework.ai.support.ToolCallbacks;
import com.guodi.assistant.component.tool.ToolBinding;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GreetingConfiguration {
    @Bean
    ToolBinding greetingTool() {
        return new ToolBinding(ToolBinding.Kind.READ, ToolCallbacks.from(new GreetingTool())[0]);
    }

    public static final class GreetingTool {
        @Tool(name = "greet", description = "生成一条中文问候语。")
        public String greet(String name) {
            return "你好，" + name;
        }
    }
}
```

声明 Bean 后，默认注册表会接入该工具。工具有 READ、WRITE 和 SHELL 三种执行类型。连续的 READ 可以并行，WRITE 和 SHELL 在当前工具轮中单独执行。启动时拒绝重复的工具名。具体工具负责参数校验、文件权限和 Shell 隔离。

内置工具也通过 `ToolBinding` Bean 接入，由 `BuiltinToolConfiguration` 声明回调和执行类型。`AssistantAutoConfiguration.toolRegistry` 收集全部 `ToolBinding` Bean，`ToolRegistry` 按名称保存并检查重名。自定义工具应使用唯一的工具名，与内置工具同名会导致启动失败。默认装配需要工作区和 Shell 镜像配置。

## 组件装配

| 组件 | 职责 |
| --- | --- |
| Spring AI `ChatModel` | 发起模型请求，保留工具调用 ID |
| `FileConversationStore` | 加载历史，按 turn ID 原子保存 |
| `WindowContextBuilder` | 统一处理系统提示词、工具定义、已有视图和新增消息，返回更新视图与 Prompt |
| `FileContextStore` | 原子保存派生视图及原始消息处理位置 |
| `ToolRegistry` | 按名称登记全部工具 Bean，向模型提供定义 |
| `ToolRoundExecutor` | 分批调度并保留模型给出的调用顺序 |
| `ToolExecutor` | 执行单个回调并返回成功状态与正文 |

默认组件由 `AssistantAutoConfiguration` 创建。用户提供同类型的 Bean 时，Spring 优先使用该 Bean。

## 调整执行策略的位置

- 聊天执行入口：ConversationService.send 负责会话串行，再调用 Runtime.chat(conversationId, message, steers)；Runtime 将 this::runLoop 交给 Harness.aroundRun，保留原有生命周期方向。历史查询由 Controller 直接调用 Harness.history。
- 一轮恢复和提交时机：AssistantHarness.aroundRun；executeTurn 调用 restoreHistory 并提交状态。
- 每步上下文构造：aroundReasoning 先应用并提交一条待处理 steer，保存成功后移除，再调用 WindowContextBuilder.prepare(systemPrompt, toolDefinitions, view, appendedMessages, projectPath, modelCall)，保存视图及处理位置后使用其 Prompt。
- 真实模型请求的包装：aroundModelCall；真实调用函数接受 Prompt，Runtime 在调用前检查最终请求和计数。当前没有自动重试。
- 工具组执行和结果检查点：aroundToolRound；工具表由 ToolRegistry 维护，调度由 ToolRoundExecutor 负责，单工具结果由 ToolExecutor 生成。
- 权威检查点：Harness.checkpoint / commit。工具意图成功保存后才执行，禁止包装层自动重放不确定的外部副作用。

策略组件不归档原始轮次；派生视图由 FileContextStore 保存，完整原始记录继续由 FileConversationStore 保存。新增资源由相应执行边界负责 finally 清理。

### 上下文视图与结果文件（2026-11-16）

- ContextView 是六字段 JSON 摘要对象和带 turnId 的保留消息；不复制完整 ConversationTurn。摘要及近期消息一次保存到现有 view.json，原始消息处理游标保持独立。
- WindowContextBuilder 统一处理新增结果、视图合并和自动 compact；按近期消息 token 预算保留完整单位（用户消息、普通助手消息、意图与全部结果组成的工具轮），允许轮内切分，turnId 不参与压缩选择。ToolResultStore 只写项目 .agent/tool-results/ 并返回相对路径。
- prepare 的 projectPath 只定位项目结果目录，不属于模型上下文。已有视图不会重新处理大结果。
- 预算集中于 assistant.budget：默认工作窗口上限 256K，输出预留 32K，摘要输出 8K；输入空间的 80% 触发压缩，15% 保留近期原文。大结果超过 8K 后落盘，首尾预览及引用合计 4K。Builder 用已有 CL100K tokenizer 统一估算并检查正常/摘要请求；Runtime 不重复计量。摘要通过本轮 modelCall 回调，与推理共享 100 次实际调用限制，单批工具上限 8。模型窗口和输出限制由配置装配时收紧；传输层映射输出参数。
