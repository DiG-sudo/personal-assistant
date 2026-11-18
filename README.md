# Personal Assistant

Java 21 + Spring Boot + Spring AI 的个人助手。固定 ReAct / Harness 主链路，模型、工具、上下文与存储通过组件插入。

陪伴助手的人格、记忆、行为设计与开发记录见 [开发总览](companion-agent/README.md)，当前优先推进 [人格设计](companion-agent/personality/README.md)。

上下文组织的核心准则见 [AGENTS.md](AGENTS.md)；具体组成逐步确定后再按准则组织。

## 启动

启动前设置独立的数据目录。Shell 默认使用 `ubuntu:24.04` 基础镜像，首次创建容器时若本地缺少镜像会自动拉取。项目目录由每次聊天请求的 `projectPath` 提供，CLI 自动携带启动时的当前目录。

```bash
export ASSISTANT_DATA_DIR="$HOME/.personal-assistant-data"
mvn -DskipTests package
mvn spring-boot:run
```

后端默认监听 http://127.0.0.1:18081，通过 `personal-agent-cli` 或下方 HTTP API 连接。当前 YAML 配置为真实模型模式，需要设置 API Key；不依赖 MySQL 或 Redis。

可尝试“你好”，或让模型读取当前项目的 README。

演示模型用于检查调用链，不具备真实大模型的自由对话能力。

## CLI 心跳与取消

CLI 持续读取输入，聊天请求在后台执行。`/cancel` 取消后端收到命令时正在执行的任务；没有任务时忽略。Ctrl+C、Ctrl+D 和 `/exit` 退出窗口，不发送任务取消请求。

CLI 启动时调用 `POST /api/control/open`，再定期调用 `/api/control/heartbeat`；`/api/control/cancel` 提交主动取消，三个接口均接收 `{"conversationId":"..."}`。心跳参数统一配置于 `assistant.control`，注册响应返回参数。默认每 5 秒发送心跳，请求超时 3 秒，30 秒未收到心跳视为窗口失联；过期控制对象不再续期，需重新打开会话。

Runtime 在每次循环开头、组织上下文前检查。当前模型调用和工具批次完成、结果保存后才停止；如果已获得最终回答则允许正常完成。Harness 沿用原有 FAILED/COMPLETED 收尾；原始记录保留，未消费 steer 丢弃。取消后继续保留窗口与心跳，收尾清除取消标记，允许继续输入。

后端按同一心跳间隔定时检查 Session。连接过期后，运行中的任务先在原检查点安全停止；空闲时重启项目容器，结束其中的后台进程，保留项目文件及容器内安装的依赖。成功回收后不会重复重启；失败后继续检查重试。正常完成、模型报错和 `/cancel` 不触发这一失联回收。重新打开同一会话时，旧执行尚未结束则立即提示稍后重试，不挂起注册请求；旧执行结束后先完成尚未执行的回收，再启用新连接。

## 注册工具

在 Spring 中注册带执行类型的 `ToolBinding` Bean，默认注册表会接入全部 `ToolBinding` Bean。访问 `GET /api/components` 可以查看注册的工具和模型模式。

内置工具已统一声明为 Bean，包括 `list_files`、`glob_files`、`grep_files`、`read_file`、`write_file`、`edit_file` 和 `execute`。名称重复会导致启动失败。

请求中的项目目录必须与助手数据目录分开，启动示例将数据保存在用户目录下。文件工具从本轮 `ToolContext.projectPath` 取得根目录，不在共享实例中保存项目路径。

`execute` 使用 YAML 中配置的 Linux 镜像，默认 `ubuntu:24.04`，可用 `ASSISTANT_SHELL_IMAGE` 覆盖；实际执行时需要 Docker 可用。镜像需要提供 `/bin/sh` 和 `sleep`；项目依赖可在容器中安装。首次 Shell 调用按规范化项目路径创建容器，本地缺少镜像时自动拉取，拉取与创建共限时 300 秒；将项目目录可写挂载到 `/workspace`，开放容器网络；后续调用通过 `docker exec` 复用容器，已停止时自动启动。命令产生的项目修改会同步到宿主机，容器内安装的依赖在停止后保留。每次调用使用新的 Shell，`cd`、`export` 不会自动延续。Shell 命令超时或其执行线程被中断时停止整个项目容器，下次调用重新启动；执行失败不会回退到宿主机。每个容器限制为 2 核 CPU、1 GiB 内存、禁用 swap、128 个进程/线程；这些是使用上限，不预占全部资源，磁盘空间尚未限制。更换镜像需要手动删除旧容器后重新创建。

`execute` 可设置 `run_in_background: true`，通过 `docker exec -d` 启动后返回，不等待命令结束；默认 `false` 保持前台等待。返回仅表示已提交启动，服务是否就绪需后续命令检查。工具不自动保存后台输出；需要日志时在 `command` 中自行重定向，再用现有 `execute` 读取。前台 Shell 超时仍会停止整个项目容器，后台进程也会结束。

## 固定骨架与组件

```mermaid
flowchart TD
    A[聊天入口] --> S[ConversationService]
    S --> C[AgentRuntime]
    C --> B[AssistantHarness]
    C --> D[ChatModel]
    B --> E[ToolRoundExecutor]
    B --> T[ToolRegistry]
    E --> X[ToolExecutor]
    X --> T
    X --> F[工具回调]
    B --> G[FileConversationStore]
    B --> I[WindowContextBuilder]
    I --> C
```

| 位置 | 职责 |
| --- | --- |
| `core/ConversationService` | 每会话阻塞串行、等待输入与 steer |
| `core/AgentRuntime` | 模型与工具循环、实际请求预算、工具意图检查点顺序 |
| `core/AssistantHarness` | aroundRun / aroundReasoning / aroundModelCall / aroundToolRound、恢复与持久化 |
| `core/AgentExecution` | 每轮的已提交记录、上下文视图、待追加消息和实际请求计数 |
| `component/` | 工具执行器、文件存储、上下文组件 |
| `extension/`（按需创建） | 自定义组件与工具 Bean |
| `config/` | 启动装配和配置校验 |
| `resources/prompts/assistant/v1.txt` | 默认系统提示词 |

Harness 直接调用 `component/` 里的组件。Spring 在启动时装配它们，同类型的用户 Bean 优先。

每次模型推理前，上下文组件统一接收系统提示词、工具定义、已有视图和新增消息，保存更新后的派生视图，原始轮次记录保持独立。调用方向为 Controller → ConversationService → Runtime.chat → Harness.aroundRun → Runtime.runLoop；Harness 接收循环的方法引用，不反向依赖 Runtime。每个会话使用公平锁，由请求线程执行自己的消息并等待工具结果。连续的只读工具可在共享线程池并行。

排查问题先按 turnId 查阶段日志：restore → persistence → context → model → persistence → tools。职责表和错误处理见 [架构](docs/ARCHITECTURE.md)。日志不保存完整模型输入，也不替代恢复检查点。

开发示例见 [组件开发指南](docs/COMPONENTS.md)。

## 接入真实模型

模型配置统一在 [application.yml](src/main/resources/application.yml) 的 `assistant.model` 中修改，只有 API Key 从环境变量 `AI_API_KEY` 读取：

```yaml
assistant:
  model:
    provider: openai
    api-key: ${AI_API_KEY:}
    base-url: https://ws-34np4pi5koc2utbe.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
    completions-path: /chat/completions
    name: kimi-k3
    reasoning-effort: ""
    timeout-seconds: 60
```

```bash
export AI_API_KEY='填写服务商密钥'
mvn spring-boot:run
```

最终请求地址由 `base-url + completions-path` 组成，避免重复拼接 `/v1`。更换服务商时同步调整地址、路径、模型名和对应密钥。`reasoning-effort` 留空时沿用服务商默认行为。

`ASSISTANT_MODEL_PROVIDER`、`AI_BASE_URL`、`AI_COMPLETIONS_PATH`、`AI_MODEL`、`AI_REASONING_EFFORT` 不再用于模型配置。修改 YAML 后需要重启后端。模型默认连接与响应超时 600 秒，不自动重试。

如需本地演示模式，将 YAML 中的 `provider` 改为 `demo`，无需 API Key。

## 数据与配置

启动、模型连接和执行预算均在 [application.yml](src/main/resources/application.yml) 配置。

| 环境变量 | 默认值 |
| --- | --- |
| `AI_API_KEY` | 无，真实模型模式必填 |
| `ASSISTANT_PORT` | `18081` |
| `ASSISTANT_DATA_DIR` | `./data`，相对于启动目录 |
| `ASSISTANT_SYSTEM_PROMPT` | `classpath:prompts/assistant/v1.txt` |
| `ASSISTANT_SHELL_IMAGE` | `ubuntu:24.04`，本地缺少时自动拉取 |

`data/sessions/` 保存会话 ID、项目路径、标题和创建/活动时间，`data/conversations/` 保存原始聊天和工具检查点，`data/contexts/` 保存派生视图及原始消息处理位置。数据目录已加入 Git 忽略规则。独立备注记忆已移除；旧的 `data/memory/` 文件保留在磁盘上，程序不再读取或写入。

上下文复用已保存视图，只追加尚未处理的消息，不按成功状态或固定轮数丢弃历史。系统提示词和工具定义每次由 Builder 组装；摘要与近期消息保存在派生视图中，原始记录保持完整。压缩允许进入当前轮内部，工具意图及全部结果始终作为一个完整单位，至少保留最新单位。

预算统一配置于 `assistant.budget`（1K = 1024 tokens）：

| 配置 | 默认值 |
| --- | --- |
| `window-tokens` | 262144（256K 工作窗口上限） |
| `max-output-tokens` / `summary-output-tokens` | 32768 / 8192 |
| `compact-ratio` / `keep-recent-ratio` | 输入空间的 80% / 15% |
| `tool-result-threshold-tokens` / `tool-result-preview-tokens` | 8192 / 4096 |
| `max-model-calls` / `max-tools-per-round` | 每次用户请求 100 次 / 单批 8 个工具 |

工作窗口与输出上限分别取应用配置和模型能力的较小值；输入空间等于工作窗口减正常输出预留。默认有效窗口为 256K 时，输入上限 229376 tokens，压缩触发线 183500，近期原文保留目标 34406。压缩后还包含系统提示词、工具定义和 JSON 摘要，不保证固定占总窗口的比例。

大工具结果完整保存在项目 `.agent/tool-results/`，模型看到首尾预览、相对路径及 `read_file` 分页说明；首尾和引用合计计入预览预算。普通推理、摘要及调用入口上的失败尝试共用一次用户请求的调用计数，新提问重新计数；默认适配器不自动重试。摘要请求先检查自身输入是否装得下，压缩后的普通请求再次检查；过大的完整工具轮不强行拆分，无法装下时明确停止。

更换模型时同步填写 `assistant.model.context-window-tokens` 和 `max-output-tokens`，以实际部署的限制为准，不按模型名称自动猜测。`output-token-parameter` 默认 `max-tokens`；要求 `max_completion_tokens` 的 OpenAI 兼容模型改为 `max-completion-tokens`。预算可适配不同窗口，但当前生产连接仍是 OpenAI 兼容接口，不代表已实现 Claude/Gemini 原生协议。

本地统一复用 JTokkit CL100K tokenizer 估算，包括系统提示词、工具定义、摘要、正文、工具参数与结果以及消息开销；这不是各服务商的精确计数。旧 `assistant.limits` 字符预算已移除。

## HTTP API

```bash
curl http://127.0.0.1:18081/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"my-chat","messageId":"550e8400-e29b-41d4-a716-446655440000","message":"你好","projectPath":"/absolute/path/to/project"}'

curl http://127.0.0.1:18081/api/conversations
curl http://127.0.0.1:18081/api/conversations/my-chat/session
curl http://127.0.0.1:18081/api/conversations/my-chat
curl http://127.0.0.1:18081/api/components
```

聊天请求的 `projectPath` 必须是后端可访问的现有项目绝对目录。第一条开始执行的消息会持久化会话与项目的绑定；后续请求必须使用相同的真实路径，否则返回 HTTP 409，不进入 Agent 执行。同一执行轮次不接受其他项目的消息作为 steer。普通聊天返回 `conversationId、turnId、answer、modelCalls`。客户端为每条输入生成 messageId，可在执行期间继续发送。

会话恢复使用三个查询接口：

- `GET /api/conversations`：返回全部会话信息，按 `updatedAt` 倒序排列，每条包含 `conversationId、projectPath、title、createdAt、updatedAt`。标题取首条用户消息摘要。
- `GET /api/conversations/{id}/session`：返回指定会话信息，不存在返回 404。查询不会创建内存 Session，也不会执行中断恢复。
- `GET /api/conversations/{id}`：返回按时间排列的全部已保存轮次，包含用户/助手消息、工具调用与结果、轮次状态和失败原因，不受模型上下文裁剪影响。

恢复客户端先取得原项目路径、切换工作目录并展示历史，再使用原 `conversationId` 和 `projectPath` 发送新消息。后端重启后会重新创建内存 Session，由现有 Harness 加载历史、将遗留 RUNNING 标为 FAILED，不重放工具。CLI 的 `agent resume` 命令尚未接入。

旧会话没有保存项目路径，仍会出现在列表中，但 `projectPath` 返回 `null`；查询不会猜测目录或修改旧记录。客户端需要让用户明确指定项目后发送下一条聊天请求，此时绑定该目录。旧会话的活动时间暂取最后轮次开始时间。元数据与轮次使用独立原子文件，不提供跨文件事务；完成后的活动时间更新失败只记录服务日志，不回滚回答。

CLI 可用同样的请求体读取纯文本进度。`-N` 让 curl 收到内容就显示：

```bash
curl -N http://127.0.0.1:18081/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"my-chat","messageId":"550e8400-e29b-41d4-a716-446655440001","message":"你好","projectPath":"/absolute/path/to/project"}'
```

每次实际请求模型时显示调用序号。工具进度包含名称、参数、结果摘要、成功与失败数和批次耗时。流的最后输出回答。如果开始输出后执行失败，错误写在流内；被提升为补充的请求返回空的 HTTP 202。

`POST /api/steer` 接收 `{conversationId,messageId}`，仅把已登记且未执行的普通消息转为当前任务补充。成功返回 STEERED；不能提升时返回 409。被提升的原聊天请求返回 202 STEERED，不再单独执行。客户端通过该接口将等待消息转为当前任务的补充。

Harness 在推理前逐条保存补充，最终文本返回前还会检查剩余补充。补充采用尽力处理语义，极晚到达可能未生效，不自动带入下一轮。

## 恢复与边界

- 单轮状态为 `RUNNING → COMPLETED / FAILED`。
- 工具意图保存成功后才执行；重启后的未完成轮次保留为失败记录，不自动重放。
- 恢复派生视图并补齐未处理的原始消息；缺少结果的工具意图在视图中标记结果未知，不自动重放，也不修改原始记录。
- 新记录使用 `schemaVersion=3`，历史格式 `1` 和 `2` 仍可读取。
- 当前为单进程、同一会话串行、本地原子文件替换；两个进程不能共用数据目录。
- 默认仅监听 127.0.0.1。身份授权、SSE、跨会话记忆、后台任务和动态热加载尚未实现。

详见 [架构](docs/ARCHITECTURE.md) 与 [后续事项](docs/ISSUES.md)。

## 轻量检查

默认只做编译和与本次变动有关的小检查，不启动浏览器或访问外部模型。

```bash
mvn -DskipTests compile
mvn -Dtest=WorkspaceToolsTest,ComponentWiringTest test
```

已有其他测试保留为按需入口，不默认执行完整测试集。

项目固定使用 Spring AI 1.1.8；[手动工具执行文档](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html#_user_controlled_tool_execution) 是模型工具协议参考。

本地演示模型每次调用等待 3 秒。连续发送 A、B、C 可观察顺序执行；A 尚未结束时，将 C 点为“转为补充”，C 会在后续推理边界加入 A，B 仍在 A 结束后独立执行。应用补充会再次调用模型，因此 A 可能超过 3 秒。
