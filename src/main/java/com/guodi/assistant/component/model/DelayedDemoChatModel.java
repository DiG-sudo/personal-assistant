// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.model;

// 导入 ChatResponse：Spring AI 的模型响应容器。
import org.springframework.ai.chat.model.ChatResponse;
// 导入 Prompt：传给模型的消息及工具选项。
import org.springframework.ai.chat.prompt.Prompt;

// 导入 AgentException：交给核心框架处理的 Agent 执行异常。
import com.guodi.assistant.core.AgentException;

/** Adds local model latency so waiting messages and steer can be observed. */
// 继承原演示模型，只增加等待；识别文字和生成工具调用仍由父类完成。
public final class DelayedDemoChatModel extends DemoChatModel {
    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 实现一次模型请求；Harness 的模型耗时统计会包含下面的等待。
    public ChatResponse call(Prompt prompt) {
        // 把可被线程中断的等待放在 try 中，以便恢复中断标记并停止执行。
        try {
            // 阻塞当前请求线程 3000 毫秒；只用于演示排队和 steer，不创建新线程。
            Thread.sleep(3000);
        // 线程被中断时进入这里，不再继续返回正常演示结果。
        } catch (InterruptedException interrupted) {
            // sleep 抛出中断异常时会清除中断标记，这里重新设置，供上层识别中断状态。
            Thread.currentThread().interrupt();
            // 把等待中断交给通用 Agent 错误处理，本次模型调用不会继续执行父类逻辑。
            throw new AgentException("演示模型等待被中断。");
        }
        // 等待结束后调用父类的本地演示逻辑，返回普通文本。
        return super.call(prompt);
    }
}
