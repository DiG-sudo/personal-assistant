# 版本更新指南

## 版本层次

| 层次 | 当前例子 | 固定内容 |
| --- | --- | --- |
| 发布组合 | assistant-harness-1.1.0 | 一组组件、提示词、工具与参数 |
| 提示词 | assistant@1.0.0 | 资源内容和 SHA-256 |
| 组件 | window@1.0.0、notes@1.0.0 | 实现行为，运行时记录类指纹 |
| 工具 | video_search_links@1.0.0 | 工具实现、描述与输入 Schema |
| 数据格式 | ConversationTurn schemaVersion=2 | JSON 字段与兼容规则 |
| 应用源码 | Git 提交 | 实现代码、Maven 依赖和清单文件 |

这些版本分别表达不同变化。只调历史长度可以创建新发布组合，继续使用同一 window 实现版本；更换上下文算法则新增实现版本。模型供应商的模型名称和本项目的适配器版本分别记录。

## 更新规则

1. 已发布提示词、清单和组件实现保留原版本，不覆盖旧内容。
2. 修复行为或改变算法，创建新实现及版本；提示词修改创建新资源文件。
3. 新发布清单引用确定版本，不使用 latest。
4. 工具模型名称保持稳定；执行版本由清单固定。
5. 启动时版本不存在、重复注册同一工具版本、组件版本不匹配或提示词校验和错误时，拒绝启动。
6. 用新的清单启动后，旧对话仍保留旧快照；不会将旧轮次标记为当前版本。
7. 配置和代码一起提交 Git。数据、密钥和构建输出不入库。

当前默认 harness-v2.yml 引用 serial@1.1.0，工具取消异常向外传播；baseline-v1.yml 与 compact-v1.yml 保留 serial@1.0.0 的原行为。两个旧清单通过不同历史窗口比较配置。

本次 window 的历史/记忆选择算法未改变，1.0.0 增加每步适配与选择证据，属于实现修订。骨架每步构造上下文的行为由新发布组合、Git 修订和核心类指纹标识。只换回旧清单不恢复旧骨架，需要同时切回对应 Git 修订。

## 更新提示词

1. 复制 `prompts/assistant/v1.txt` 到新文件，例如 `prompts/assistant/v1.1.0.txt`。
2. 修改新文件，保留原文件。
3. 计算实际内容的 SHA-256：

```bash
shasum -a 256 src/main/resources/prompts/assistant/v1.1.0.txt
```

4. 复制发布清单，修改 release-id、prompt.version、prompt.resource 与 prompt.sha256。
5. 通过 ASSISTANT_RELEASE 选择新清单并重启。

提示词只读取一次；默认上下文组件与版本快照使用同一份已校验内容。进程运行期间修改外部提示词文件不会热更新。

ASSISTANT_SYSTEM_PROMPT 仍可覆盖资源路径，但其内容必须匹配清单 SHA；不同内容需要新提示词版本和校验和。不要仅为绕过检查而覆盖旧版本校验和。

## 更新上下文策略

当前类 WindowContextBuilder 表示 window@1.0.0。更新算法时新增类，例如 WindowContextBuilderV2，保留原实现。

可在实现类或工厂方法上声明：

```java
@ComponentRevision(id = "window", version = "2.0.0")
```

扩展配置中注册新的 ContextBuilder Bean，可用：

```java
@ConditionalOnProperty(name = "assistant.components.context.version", havingValue = "2.0.0")
```

然后在新清单中选择：

```yaml
assistant:
  components:
    context: {id: window, version: 2.0.0}
```

默认实现自动退出，版本核对后才接收聊天。实现类上的版本声明优先于 Bean 方法；复用已标注旧版本的类时，不能仅靠给 Bean 改名伪装成新版本。

记忆、存储和工具执行器采用相同方式。现有端口入口保持兼容，ContextBuilder 增加默认 prepare 适配；普通业务组件更新不修改 AgentRuntime。

## 更新工具

用带版本的回调注册实现：

```java
@Bean
ToolCallback videoSearchV1() {
    return VersionedToolCallback.from("1.0.0", new VideoSearchV1())[0];
}

@Bean
ToolCallback videoSearchV2() {
    return VersionedToolCallback.from("2.0.0", new VideoSearchV2())[0];
}
```

两个实现的 @Tool name 可保持相同。清单只选择一个：

```yaml
assistant:
  tools:
    - {id: current_time, version: 1.0.0}
    - {id: video_search_links, version: 2.0.0}
```

清单中的 id 为实际模型工具名称。没有列入清单的工具不向模型暴露，也不能靠猜测名称执行。删除列表条目即禁用该工具。

工具不存在请求的版本时直接报错，不选择另一个版本。清单不能同时选择同名工具的多个版本。

## 自定义组件参数

上下文窗口、模型名称和执行预算自动纳入快照。自定义组件或工具具有额外参数时，可实现：

```java
public interface ConfigurationSnapshot {
    Map<String, Object> versionedSettings();
}
```

返回不可变、JSON 可序列化的参数，例如 `Map.of("maxResults", 10)`。这些参数会参与配置指纹。不要返回 API Key、访问令牌或含凭据的 URL。

未实现该接口时，自定义组件仍记录版本和代码指纹，但其额外运行参数不会自动被反射收集。

## 比较两个版本

手动测试时，为两个版本使用独立数据目录，避免记忆和历史影响对比：

```bash
ASSISTANT_DATA_DIR=./data/baseline \
ASSISTANT_RELEASE=classpath:releases/baseline-v1.yml \
mvn spring-boot:run
```

另一个终端：

```bash
ASSISTANT_PORT=18082 \
ASSISTANT_DATA_DIR=./data/compact \
ASSISTANT_RELEASE=classpath:releases/compact-v1.yml \
mvn spring-boot:run
```

使用相同输入，查看响应 configurationId 和历史里的 manifest。版本指纹用于归因；模型随机性、工具数据变化和记忆内容仍可能影响回答。

## 回退

恢复旧 Git 提交中的代码，选择对应旧清单并重启。只把清单改回旧版本但未保留旧实现时，启动会报告缺失版本。

数据格式版本独立于代码与提示词版本。旧聊天 schemaVersion=1 不补造版本信息；新聊天 schemaVersion=2 带完整快照。组件版本回退不意味着其持久化数据自动降级。

## 轻量验证

仅执行与当前改动有关的小检查。版本选择、校验和、快照与旧记录读取可用：

```bash
mvn -Dtest=VersioningTest test
```

无需搭建评测平台、访问真实模型或执行浏览器流程。

## Harness 修订的归因

每轮 manifest 继续保存原始发布与配置，schemaVersion 仍为 2。当前骨架记录 AgentExecution、ContextBuilder 契约及 Runtime、Harness、装配类指纹；组件与骨架类指纹包含继承实现和直接成员类，防止共享基类或选择证据记录类型变化被遗漏。旧轮次中已有的 hooksSha256 保留在原始 manifest 中，新轮次不再包含已删除接口的指纹。工具的具体实现、Schema 和版本继续由原工具快照记录。

指纹不覆盖所有传递依赖、外部模型或记忆内容；依赖仍由 pom.xml 与 Git 修订管理。回放和完整模型输入归档尚未实现。

## 可读性整理

本次将调用方向整理为 Runtime → 具体 Harness，aroundRun 接收循环方法引用，删除仅由 Harness 实现的协调接口以及 stage/diagnostic 回调包装。HTTP 输入输出、组件算法、提示词、预算与持久化数据格式保持原有行为。

这是骨架内部重构，继续使用 assistant-harness-1.1.0 发布组合；不改已发布清单、提示词或旧标签。新 Git 修订和实现指纹识别这次源码变化；回退骨架时同时切换对应 Git 修订。
