// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.tool;

// 导入 ArrayList：可追加的列表，用于构建消息或工具结果。
import java.util.ArrayList;
// 导入 Collections：JDK 集合辅助方法，这里用于只读 Map 包装。
import java.util.Collections;
// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 Map：按键查找值的映射容器。
import java.util.Map;
// 导入 TreeMap：按工具名有序保存回调的映射。
import java.util.TreeMap;

// 导入 ToolCall：模型请求的一次工具调用，包含 ID、名称和参数。
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
// 导入 ToolResponse：对应某个工具调用 ID 的结果。
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
// 导入 ToolContext：不由模型参数直接提供的内部工具上下文。
import org.springframework.ai.chat.model.ToolContext;
// 导入 ToolCallback：Spring AI 的工具定义与调用接口。
import org.springframework.ai.tool.ToolCallback;

// 导入 ToolExecutor：本项目的工具执行接口。
import com.guodi.assistant.port.ToolExecutor;

/** One allowlist controls exposure and execution. Tools run sequentially in model call order. */
// 声明实现的身份与行为版本，供启动校验和 manifest 记录；这个注解本身不负责把类注册为 Spring Bean。
@com.guodi.assistant.versioning.ComponentRevision(id = "serial", version = "1.0.0")
// ToolExecutor 的串行实现：同一份工具映射同时决定模型可见集合和实际执行允许集合。
public class RegisteredToolExecutor implements ToolExecutor {
    // 按工具名称保存回调；构造后映射不可修改，防止执行期间允许列表漂移。
    private final Map<String, ToolCallback> tools;
    // 单条工具结果允许返回模型的最大正文字符数。
    private final int resultChars;

    // 接收发布清单已经选中的工具回调和结果字符上限，不在执行阶段再次选择工具版本。
    public RegisteredToolExecutor(List<ToolCallback> callbacks, int resultChars) {
        // TreeMap 按工具名排序，使工具暴露和版本快照的顺序保持稳定。
        var registered = new TreeMap<String, ToolCallback>();
        // 逐个登记已选工具。
        for (var callback : callbacks) {
            // 从回调的工具定义中读取模型实际调用的名称。
            String name = callback.getToolDefinition().name();
            // 原子语义地只在名称不存在时登记；返回旧值表示重复名称，不能让后一个工具静默覆盖前一个。
            if (registered.putIfAbsent(name, callback) != null) {
                // 重复工具名会导致调用目标不明确，所以在构造执行器时直接拒绝装配。
                throw new IllegalStateException("Duplicate tool name: " + name);
            }
        }
        // 把已构造映射包装成只读视图；局部变量不再交给外部修改。
        this.tools = Collections.unmodifiableMap(registered);
        // 保存工具结果截断上限。
        this.resultChars = resultChars;
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 返回当前工具回调，Harness 用这些定义构造每次模型请求。
    public List<ToolCallback> callbacks() {
        // 复制映射中的回调为不可修改列表，顺序沿用 TreeMap 的工具名排序。
        return List.copyOf(tools.values());
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 接收模型一次返回的多个工具调用，以及 Harness 提供的会话/轮次上下文。
    public List<ToolResponse> execute(List<ToolCall> calls, ToolContext context) {
        // 创建本轮工具响应容器；最终顺序必须与模型请求顺序一致。
        var results = new ArrayList<ToolResponse>();
        // 按模型给出的顺序逐个执行，不并发调度。
        for (var call : calls) {
            // 根据请求工具名查允许列表；只有映射中的工具才可能执行。
            ToolCallback callback = tools.get(call.name());
            // 声明当前调用的结果正文，后续分支保证为它赋值。
            String content;
            // 工具不存在或未启用时不调用任何实现，直接构造说明文本。
            if (callback == null) {
                // 把不允许执行的原因作为该调用的结果，留给模型修正请求。
                content = "工具未启用或不存在：" + call.name();
            // 找到允许的工具回调后进入真实调用分支。
            } else {
                // 普通工具业务异常在这里收敛为可读结果，避免仅因参数错误就丢掉整轮工具响应。
                try {
                    // 传入 JSON 参数和内部 ToolContext，真正执行工具；返回值是工具结果字符串。
                    content = callback.call(call.arguments(), context);
                    // 第三方回调可能返回 null，统一归一化为字符串以便后续长度检查和持久化。
                    if (content == null) {
                        // 空字符串表示工具没有正文，不把 null 传给模型消息。
                        content = "";
                    }
                // 捕获工具运行时异常；具体是否可转换由 failureResult 决定，子类可让取消异常继续传播。
                } catch (RuntimeException error) {
                    // 把普通业务异常转换成结果文本；这是可重写的方法调用，不一定总是吞掉异常。
                    content = failureResult(error);
                }
            }
            // 在工具执行完成后检查返回正文长度，避免单条结果无限膨胀模型上下文。
            if (content.length() > resultChars) {
                // 保留前 resultChars 个字符并追加截断说明；这个动作不重新执行工具。
                content = content.substring(0, resultChars) + "\n[工具结果已截断]";
            }
            // 用原调用 ID 和名称创建响应，保证 assistant 的工具意图与 tool 结果可以严格配对。
            results.add(new ToolResponse(call.id(), call.name(), content));
        }
        // 返回不可修改的整轮响应列表，Harness 随后将它作为一个工具结果检查点保存。
        return List.copyOf(results);
    }

    // protected 允许专门的执行器子类调整异常规则，基础实现只生成普通错误文本。
    protected String failureResult(RuntimeException error) {
        // 把异常信息写入工具结果，供模型理解失败原因并修正后续调用。
        return "工具执行失败：" + error.getMessage();
    }
}
