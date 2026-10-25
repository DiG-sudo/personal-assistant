# 组件开发指南

扩展放在 com.guodi.assistant.extension 包及子包中。每个端口只能有一个活动 Bean；默认实现自动退出。版本组合由 resources/releases 下的清单选择。

## 新增工具

```java
package com.guodi.assistant.extension;

import com.guodi.assistant.component.tool.VersionedToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GreetingConfiguration {
    @Bean
    ToolCallback greetingTool() {
        return VersionedToolCallback.from("1.0.0", new GreetingTool())[0];
    }

    public static final class GreetingTool {
        @Tool(name = "greet", description = "生成一条中文问候语。")
        public String greet(String name) {
            return "你好，" + name;
        }
    }
}
```

复制一个新发布清单，在 tools 列表加入 `{id: greet, version: 1.0.0}`，启动时选择该清单。只注册 Bean 不会自动暴露未被选择的工具。

多方法工具可把每个版本化回调分别注册为 Bean。相同工具名允许注册不同版本，但发布清单只能选择其中一个。普通未声明版本的 ToolCallback 不进入生产装配。

真实视频搜索可提供新工具版本，返回有来源 URL 的结构化结果。网络超时、输入校验、权限与写操作幂等由工具实现负责。

## 替换记忆

```java
package com.guodi.assistant.extension;

import java.util.List;
import com.guodi.assistant.domain.ConversationTurn;
import com.guodi.assistant.port.MemoryModule;
import com.guodi.assistant.versioning.ComponentRevision;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyMemoryConfiguration {
    @Bean
    @ComponentRevision(id = "my-memory", version = "1.0.0")
    MemoryModule myMemory() {
        return new MyMemory();
    }

    static final class MyMemory implements MemoryModule {
        public List<String> recall(String conversationId, String query) {
            return List.of(); // 接入自己的检索逻辑。
        }
        public void observe(String conversationId, ConversationTurn completedTurn) {
            // 接入自己的记忆写入；按 completedTurn.id() 去重。
        }
    }
}
```

新清单选择：

```yaml
assistant:
  components:
    memory: {id: my-memory, version: 1.0.0}
```

版本注解也可以放在具体实现类上。类注解优先于工厂方法。自定义实现与清单不匹配时拒绝启动。

observe 只接收已提交的成功轮次，不修改原始聊天。更换实现前明确记忆范围、数据格式与迁移方式。默认按 conversationId 隔离；跨会话记忆需要单独明确 owner 身份。

## 替换其他组件

| Bean 类型 | 清单或版本声明 | 契约 |
| --- | --- | --- |
| ChatModel | 类或 Bean 方法的 ComponentRevision | 一次 call 做一次模型调用，保留工具 ID，尊重手动执行设置 |
| ConversationStore | components.store | 按时间加载，按 turn ID 原子保存，保留 manifest |
| ContextBuilder | components.context | 每步 prepare 选择模型视图、保留本轮完整工具配对；不改历史 |
| ToolExecutor | components.tool-executor | 暴露与执行共用允许列表；结果顺序与 ID 匹配清单工具 |

模型继续使用 Spring AI ChatModel，不增加另一套模型调用协议。第三方最终类或 Lambda 可以在 Bean 工厂方法上声明版本。

上下文的旧 build(history, memories, userMessage) 仍可使用。默认 prepare 每次推理调用它，并追加本轮已经提交的 assistant/tool 消息。只实现 build 时，日志明确标记 selection=unreported。

若要根据本轮工具结果调整策略，覆盖 prepare(history, memories, activeTurn)，返回 ContextBuilder.ModelContext；其 messages 仅供本次模型请求使用。可通过 Optional.of(new Selection(turnIds, memoryIndexes)) 报告真实选择，记忆索引对应传入的 memories；未报告时用 Optional.empty()。这些证据不另存一份聊天或记忆。

自定义上下文组件可注入 ExecutionManifest.PromptSnapshot，使用其 text 作为已校验系统提示；不要自行再次读取可变文件。

额外参数可通过 ConfigurationSnapshot.versionedSettings() 提供给运行快照。只返回不可变、脱敏 JSON 数据。

## 多版本选择

多个实现可通过 ConditionalOnProperty 或 Profile 分别启用。例如根据 assistant.components.context.version 选择上下文组件。任何时候同一端口仅启用一个实现。

默认装配只提供当前已实现版本；修改清单版本号不会自动生成新算法。

更新与回退步骤见 [版本更新指南](VERSIONING.md)。Runtime 与 Harness 不负责组件目录、版本解析或热加载。

## 调整执行策略的位置

- 一轮恢复、提交和记忆时机：AssistantHarness.aroundRun。
- 每步上下文构造：aroundReasoning 调用 ContextBuilder.prepare。
- 真实模型请求的包装：aroundModelCall；真实调用函数接受 Prompt，Runtime 在调用前检查最终请求和计数。当前没有自动重试。
- 工具组的执行包装：aroundToolRound；允许列表、顺序和普通业务错误属于 ToolExecutor。
- 权威检查点：Harness.checkpoint / commit。工具意图成功保存后才执行，禁止包装层自动重放不确定的外部副作用。

不要在策略组件中重新保存对话或维护另一份消息列表。新增资源由相应执行边界负责 finally 清理。
