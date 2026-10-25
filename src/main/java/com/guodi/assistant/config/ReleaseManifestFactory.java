package com.guodi.assistant.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;

import com.guodi.assistant.component.tool.VersionedToolCallback;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.AgentExecution;
import com.guodi.assistant.core.RuntimeHooks;
import com.guodi.assistant.core.AssistantHarness;
import com.guodi.assistant.port.*;
import com.guodi.assistant.versioning.ComponentRevision;
import com.guodi.assistant.versioning.ConfigurationSnapshot;
import com.guodi.assistant.versioning.ExecutionManifest;
import com.guodi.assistant.versioning.ExecutionManifest.*;
import com.guodi.assistant.versioning.Fingerprints;

/** Startup-only inspection; the core loop never resolves component versions. */
public final class ReleaseManifestFactory {
    private ReleaseManifestFactory() {}

    public static PromptSnapshot loadPrompt(AssistantProperties.Prompt prompt) throws IOException {
        String text = prompt.resource().getContentAsString(StandardCharsets.UTF_8);
        String hash = Fingerprints.sha256(text.getBytes(StandardCharsets.UTF_8));
        if (!hash.equals(prompt.sha256())) {
            throw new IllegalStateException("Prompt checksum mismatch for " + prompt.id() + "@" + prompt.version()
                    + "; create a new prompt revision and update its release manifest");
        }
        return new PromptSnapshot(new Ref(prompt.id(), prompt.version()),
                prompt.resource().getDescription(), hash, text);
    }

    public static ExecutionManifest create(AssistantProperties properties, PromptSnapshot prompt,
            ConfigurableListableBeanFactory beans, ObjectMapper json) {
        var selected = properties.components();
        var components = Map.of(
                "model", describe(ChatModel.class, beans),
                "memory", require(selected.memory(), describe(MemoryModule.class, beans)),
                "context", require(selected.context(), describe(ContextBuilder.class, beans)),
                "store", require(selected.store(), describe(ConversationStore.class, beans)),
                "toolExecutor", require(selected.toolExecutor(), describe(ToolExecutor.class, beans)));

        var selectedTools = beans.getBean(ToolExecutor.class).callbacks().stream().map(callback -> {
            if (!(callback instanceof VersionedToolCallback versioned)) {
                throw new IllegalStateException("Active tool has no version: " + callback.getToolDefinition().name());
            }
            return versioned.snapshot();
        }).toList();
        var expectedTools = properties.tools().stream()
                .sorted(Comparator.comparing(Ref::id).thenComparing(Ref::version)).toList();
        if (!selectedTools.stream().map(ToolSnapshot::revision).toList().equals(expectedTools)) {
            throw new IllegalStateException("Active tools do not match the release manifest");
        }

        var model = properties.model();
        var parameterType = new TypeReference<Map<String, Object>>() {};
        // Model connection properties contain credentials; only copy these safe fields.
        var settings = Map.<String, Object>of(
                "model", Map.of("name", Objects.toString(model.name(), ""),
                        "provider", model.provider().name(), "timeoutSeconds", model.timeoutSeconds()),
                "context", Map.copyOf(json.convertValue(properties.context(), parameterType)),
                "limits", Map.copyOf(json.convertValue(properties.limits(), parameterType)),
                "runtimeSha256", Fingerprints.implementationSha256(AgentRuntime.class),
                "harnessSha256", Fingerprints.implementationSha256(AssistantHarness.class),
                "assemblySha256", Fingerprints.implementationSha256(AssistantAutoConfiguration.class),
                "executionSha256", Fingerprints.implementationSha256(AgentExecution.class),
                "hooksSha256", Fingerprints.implementationSha256(RuntimeHooks.class),
                "contextContractSha256", Fingerprints.implementationSha256(ContextBuilder.class));
        var draft = new ExecutionManifest(properties.releaseId(), "", prompt, components, selectedTools, settings);
        try {
            byte[] encoded = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(draft);
            return new ExecutionManifest(draft.releaseId(), Fingerprints.sha256(encoded),
                    prompt, components, selectedTools, settings);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot serialize the execution manifest", error);
        }
    }

    private static ComponentSnapshot require(Ref selected, ComponentSnapshot actual) {
        if (!selected.equals(actual.revision())) {
            throw new IllegalStateException("Requested component " + selected + " but assembled " + actual.revision());
        }
        return actual;
    }

    private static ComponentSnapshot describe(Class<?> port, ConfigurableListableBeanFactory beans) {
        var bean = beans.resolveNamedBean(port);
        String name = bean.getBeanName();
        Object component = bean.getBeanInstance();
        Class<?> implementation = ClassUtils.getUserClass(component);
        ComponentRevision revision = implementation.getAnnotation(ComponentRevision.class);
        if (revision == null) {
            revision = beans.findAnnotationOnBean(name, ComponentRevision.class);
        }
        if (revision == null || revision.id().isBlank() || revision.version().isBlank()) {
            throw new IllegalStateException("Component bean must declare @ComponentRevision: " + name);
        }
        var definition = beans.getMergedBeanDefinition(name);
        String factoryHash = "";
        if (definition instanceof RootBeanDefinition root && root.getResolvedFactoryMethod() != null) {
            var method = root.getResolvedFactoryMethod();
            factoryHash = Fingerprints.implementationSha256(method.getDeclaringClass());
            if (implementation.isSynthetic()) {
                implementation = method.getDeclaringClass();
            }
        }
        String codeHash = Fingerprints.sha256((Fingerprints.implementationSha256(implementation)
                + factoryHash).getBytes(StandardCharsets.UTF_8));
        return new ComponentSnapshot(new Ref(revision.id(), revision.version()), implementation.getName(), codeHash,
                component instanceof ConfigurationSnapshot source ? source.versionedSettings() : Map.of());
    }
}
