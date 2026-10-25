package com.guodi.assistant.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ClassUtils;

import com.guodi.assistant.component.tool.VersionedToolCallback;
import com.guodi.assistant.core.AgentRuntime;
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
            ChatModel model, ConversationStore store, MemoryModule memory, ContextBuilder context,
            ToolExecutor tools, ConfigurableListableBeanFactory beans, ObjectMapper json) {
        var components = new LinkedHashMap<String, ComponentSnapshot>();
        components.put("model", describe(model, ChatModel.class, beans));
        components.put("memory", require(properties.components().memory(), describe(memory, MemoryModule.class, beans)));
        components.put("context", require(properties.components().context(), describe(context, ContextBuilder.class, beans)));
        components.put("store", require(properties.components().store(), describe(store, ConversationStore.class, beans)));
        components.put("toolExecutor", require(properties.components().toolExecutor(), describe(tools, ToolExecutor.class, beans)));

        var selectedTools = tools.callbacks().stream().map(callback -> {
            if (!(callback instanceof VersionedToolCallback versioned)) {
                throw new IllegalStateException("Active tool has no version: " + callback.getToolDefinition().name());
            }
            return versioned.snapshot();
        }).toList();
        if (!selectedTools.stream().map(ToolSnapshot::revision).toList().equals(properties.tools().stream().sorted(java.util.Comparator.comparing(Ref::id).thenComparing(Ref::version)).toList())) {
            throw new IllegalStateException("Active tools do not match the release manifest");
        }

        var modelSettings = new LinkedHashMap<String, Object>();
        modelSettings.put("name", properties.model().name() == null ? "" : properties.model().name());
        modelSettings.put("provider", properties.model().provider().name());
        modelSettings.put("timeoutSeconds", properties.model().timeoutSeconds());
        // Never copy the API key, environment or credential-bearing endpoint URLs into a transcript.
        var settings = Map.<String, Object>of("model", Map.copyOf(modelSettings),
                "context", Map.of("recentTurns", properties.context().recentTurns(),
                        "historyChars", properties.context().historyChars(), "memoryChars", properties.context().memoryChars()),
                "limits", Map.of("maxModelCalls", properties.limits().maxModelCalls(),
                        "maxContextChars", properties.limits().maxContextChars(),
                        "maxToolsPerRound", properties.limits().maxToolsPerRound(),
                        "toolResultChars", properties.limits().toolResultChars()),
                "runtimeSha256", Fingerprints.sha256(Fingerprints.classBytes(AgentRuntime.class)),
                "harnessSha256", Fingerprints.sha256(Fingerprints.classBytes(AssistantHarness.class)),
                "assemblySha256", Fingerprints.sha256(Fingerprints.classBytes(AssistantAutoConfiguration.class)));
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

    private static <T> ComponentSnapshot describe(T component, Class<T> port, ConfigurableListableBeanFactory beans) {
        String name = beans.getBeansOfType(port).entrySet().stream()
                .filter(entry -> entry.getValue() == component).map(Map.Entry::getKey).findFirst().orElseThrow();
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
            factoryHash = Fingerprints.sha256(Fingerprints.classBytes(method.getDeclaringClass()));
            if (implementation.isSynthetic()) {
                implementation = method.getDeclaringClass();
            }
        }
        String codeHash = Fingerprints.sha256((Fingerprints.sha256(Fingerprints.classBytes(implementation))
                + factoryHash).getBytes(StandardCharsets.UTF_8));
        return new ComponentSnapshot(new Ref(revision.id(), revision.version()), implementation.getName(), codeHash,
                component instanceof ConfigurationSnapshot source ? source.versionedSettings() : Map.of());
    }
}
