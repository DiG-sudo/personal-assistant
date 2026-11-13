// 声明当前文件的 Java 包；目录分组对应类型的完整名称，其他包通过 import 引用这里的类型。
package com.guodi.assistant.component.tool;

// 导入 ArrayList：可追加的列表，用于构建消息或工具结果。
import java.util.ArrayList;
// 导入 LinkedHashMap：保持登记顺序的映射。
import java.util.LinkedHashMap;
// 导入 List：列表接口及 List.of / List.copyOf 等不可修改列表工厂。
import java.util.List;

// 导入 ToolContext：不由模型参数直接提供的内部工具上下文。
import org.springframework.ai.chat.model.ToolContext;
// 导入 ToolCallbacks：把带 @Tool 的对象转换为工具回调的 Spring AI 工具类。
import org.springframework.ai.support.ToolCallbacks;
// 导入 ToolCallback：Spring AI 的工具定义与调用接口。
import org.springframework.ai.tool.ToolCallback;
// 导入 ToolDefinition：工具的名称、描述和输入 Schema。
import org.springframework.ai.tool.definition.ToolDefinition;
// 导入 ToolMetadata：工具额外执行元数据。
import org.springframework.ai.tool.metadata.ToolMetadata;

// 导入 Ref：组件或工具名称与版本的组合。
import com.guodi.assistant.versioning.ExecutionManifest.Ref;
// 导入 ToolSnapshot：执行 manifest 中保存的工具版本快照。
import com.guodi.assistant.versioning.ExecutionManifest.ToolSnapshot;
// 导入 Fingerprints：计算代码 SHA-256 指纹的工具。
import com.guodi.assistant.versioning.Fingerprints;
// 导入 ConfigurationSnapshot：组件显式提供脱敏配置快照的接口。
import com.guodi.assistant.versioning.ConfigurationSnapshot;

/** Binds a tool revision to the same callback that will actually be executed. */
// ToolCallback 的包装器：保留真实回调，同时把工具版本与执行定义绑定在同一个对象上。
public final class VersionedToolCallback implements ToolCallback {
    // delegate 是真正执行工具的回调；本类的 call 最终都转交给它。
    private final ToolCallback delegate;
    // snapshot 是不可变的工具版本快照，记录版本、Schema、描述、指纹和脱敏配置。
    private final ToolSnapshot snapshot;

    // 把版本字符串、真实回调和实现对象绑定；实现对象用于读取类名、字节码和可选配置。
    public VersionedToolCallback(String version, ToolCallback delegate, Object implementation) {
        // 版本是发布清单精确选工具的依据，不能缺失或只含空白。
        if (version == null || version.isBlank()) {
            // 没有有效版本时拒绝构造，避免工具绕过版本约束。
            throw new IllegalArgumentException("Tool version must not be blank");
        }
        // 保存真实回调引用，执行时保持相同目标。
        this.delegate = delegate;
        // 从真实回调读取工具定义，避免单独再维护一份名称、描述或参数 Schema。
        var definition = delegate.getToolDefinition();
        // 创建工具快照；Ref 由真实工具名和指定版本构成，同时记录实现类的完整名称。
        this.snapshot = new ToolSnapshot(new Ref(definition.name(), version), implementation.getClass().getName(),
                // 读取实现类的编译后 .class 字节并计算 SHA-256，用于识别这份实现代码。
                Fingerprints.sha256(Fingerprints.classBytes(implementation.getClass())),
                // 描述和输入 Schema 都取自即将执行的同一个回调，避免快照与实际工具定义不一致。
                definition.description(), definition.inputSchema(),
                // 实现类若提供 ConfigurationSnapshot，就记录其脱敏 JSON 配置；否则使用空 Map，不自动反射字段。
                implementation instanceof ConfigurationSnapshot source ? source.versionedSettings() : java.util.Map.of());
    }

    // 把带 @Tool 方法的普通业务对象转换为版本化工具数组；一个对象可以声明多个工具。
    public static VersionedToolCallback[] from(String version, Object tool) {
        // ToolCallbacks.from 先生成实际回调数组；Arrays.stream 再把数组转成流以便逐个包装。
        return java.util.Arrays.stream(ToolCallbacks.from(tool))
                // 给每个回调绑定同一个实现对象及版本，保留对应的真实工具名称和 Schema。
                .map(callback -> new VersionedToolCallback(version, callback, tool))
                // 把流收集回 VersionedToolCallback 数组；构造器引用负责创建正确类型的数组。
                .toArray(VersionedToolCallback[]::new);
    }

    /** Select exactly the revisions declared by the release; never fall back to a newer version. */
    // 启动时根据发布清单精确挑选工具；available 是候选目录，selected 是允许的名称和版本组合。
    public static List<ToolCallback> select(List<ToolCallback> available, List<Ref> selected) {
        // 用工具名+版本的 Ref 作为键建立候选目录，LinkedHashMap 保持登记顺序。
        var catalog = new LinkedHashMap<Ref, VersionedToolCallback>();
        // 遍历所有候选工具回调，先确认它们都满足版本包装约束。
        for (var callback : available) {
            // 模式匹配只接受 VersionedToolCallback；普通 ToolCallback 缺少版本快照，不能进入本目录。
            if (!(callback instanceof VersionedToolCallback tool)) {
                // 遇到没有版本声明的工具时立即报告具体名称，拒绝启动装配。
                throw new IllegalStateException("Tool must declare a version: " + callback.getToolDefinition().name());
            }
            // 同一名称和同一版本只能登记一次；允许同名的不同版本并存，禁止相同 Ref 重复。
            if (catalog.putIfAbsent(tool.snapshot.revision(), tool) != null) {
                // 重复版本会使实际实现不确定，因此报错而不是任选一个。
                throw new IllegalStateException("Duplicate tool revision: " + tool.snapshot.revision());
            }
        }
        // 保存最终被发布清单选中的回调。
        var result = new ArrayList<ToolCallback>();
        // 按发布清单给定的 Ref 顺序逐项选择，不自动选择最高版本。
        for (var revision : selected) {
            // 用完整名称和版本查找候选，而不是只按工具名查找。
            var tool = catalog.get(revision);
            // 配置引用的精确版本没有注册时，禁止悄悄回退到其他版本。
            if (tool == null) {
                // 报告缺失的 Ref，要求修正清单或注册对应实现。
                throw new IllegalStateException("Tool revision is not registered: " + revision);
            }
            // 把找到的版本包装器本身加入结果；后续执行仍通过这个对象进入真实回调。
            result.add(tool);
        }
        // 返回不可修改的已选工具列表，供执行器建立本次启动固定的允许集合。
        return List.copyOf(result);
    }

    // 返回构造时建立的不可变快照，版本系统据此记录本轮实际工具。
    public ToolSnapshot snapshot() { return snapshot; }
    // 实现 ToolCallback 的定义读取方法，直接转发到真实回调，不维护第二份定义。
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    // 直接转发 Spring AI 工具元数据，保留真实回调的执行设置。
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    // 无内部上下文的调用入口：把 JSON 参数原样交给真实工具回调执行。
    @Override public String call(String arguments) { return delegate.call(arguments); }
    // 带内部上下文的调用入口：同时转交参数和 ToolContext，保留会话/轮次信息。
    @Override public String call(String arguments, ToolContext context) { return delegate.call(arguments, context); }
}
