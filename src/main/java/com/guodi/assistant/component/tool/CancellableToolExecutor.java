// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.tool;

// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 CancellationException：表示取消操作的运行时异常。
import java.util.concurrent.CancellationException;

// 导入 ToolCallback：Spring AI 的工具定义与调用接口。
import org.springframework.ai.tool.ToolCallback;
// 导入 ComponentRevision：项目自定义的组件身份与版本注解。
import com.guodi.assistant.versioning.ComponentRevision;

/** serial@1.1.0 preserves ordinary error results but lets cancellation stop the run. */
// 声明实现的身份与行为版本，供启动校验和 manifest 记录；这个注解本身不负责把类注册为 Spring Bean。
@ComponentRevision(id = "serial", version = "1.1.0")
// 继承串行执行器，只调整取消异常的处理；工具查找、执行顺序和截断逻辑复用父类。
public final class CancellableToolExecutor extends RegisteredToolExecutor {
    // 接收同一套已选工具和结果上限。
    public CancellableToolExecutor(List<ToolCallback> callbacks, int resultChars) {
        // 调用父类构造器，建立只读工具注册表并保存结果上限。
        super(callbacks, resultChars);
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 覆盖父类的异常转结果逻辑，使取消能够停止整个 Agent 执行。
    protected String failureResult(RuntimeException error) {
        // MethodToolCallback may wrap the exception thrown by an annotated tool.
        // 从当前异常开始沿 getCause 因果链向内寻找；Spring 的方法回调可能把实际异常包在内部。
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // 识别取消异常并用模式匹配变量 cancelled 取得该异常对象。
            if (cause instanceof CancellationException cancelled) {
                // 直接继续抛出取消异常，不把取消伪装成模型可以继续处理的普通工具失败。
                throw cancelled;
            }
        }
        // 整条因果链都没有取消异常时，复用父类生成普通工具失败文本。
        return super.failureResult(error);
    }
}
