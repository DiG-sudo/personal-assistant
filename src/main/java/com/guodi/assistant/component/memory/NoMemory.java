// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.memory;

// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;
// 导入 ConversationTurn：持久化的一轮对话数据。
import com.guodi.assistant.domain.ConversationTurn;
// 导入 MemoryModule：长期记忆接口。
import com.guodi.assistant.port.MemoryModule;

// 声明实现的身份与行为版本，供启动校验和 manifest 记录；这个注解本身不负责把类注册为 Spring Bean。
@com.guodi.assistant.versioning.ComponentRevision(id = "none", version = "1.0.0")
// MemoryModule 的关闭实现；用正常接口表示不启用记忆，Harness 不需要额外写开关分支。
public final class NoMemory implements MemoryModule {
    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 保持记忆查询接口，但不读取任何存储。
    public List<String> recall(String conversationId, String query) {
        // 返回不可修改的空列表，表示本轮没有召回记忆。
        return List.of();
    }

    // 标明下面的方法实现接口或覆盖父类方法；签名不匹配时编译器会报错。
    @Override
    // 空方法体：成功执行后也不保存备注，满足关闭记忆的行为。
    public void observe(String conversationId, ConversationTurn completedTurn) {}
}
