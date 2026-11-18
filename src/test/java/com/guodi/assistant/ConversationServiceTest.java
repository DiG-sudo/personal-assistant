package com.guodi.assistant;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.guodi.assistant.component.storage.FileConversationStore;
import com.guodi.assistant.component.storage.FileSessionStore;
import com.guodi.assistant.component.storage.JsonFiles;
import com.guodi.assistant.component.tool.SandboxCleanup;
import com.guodi.assistant.core.AgentRuntime;
import com.guodi.assistant.core.ConversationService;
import com.guodi.assistant.domain.ConversationInfo;
import com.guodi.assistant.domain.ConversationTurn;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationServiceTest {
    @TempDir Path directory;

    private FileSessionStore sessions() {
        return new FileSessionStore(directory.resolve("sessions"),
                new JsonFiles(new ObjectMapper().findAndRegisterModules()));
    }

    private FileConversationStore transcripts() {
        return TestSupport.store(directory.resolve("conversations"));
    }

    @Test
    void freshServiceKeepsProjectBindingAndTitleAndRejectsAnotherProjectBeforeRuntime() {
        AgentRuntime runtime = mock(AgentRuntime.class);
        Path project = directory.resolve("project");
        var first = new ConversationService(runtime, sessions(), transcripts(), TestSupport.controlSettings(), mock(SandboxCleanup.class));
        first.send("chat", TestSupport.message("修复登录\n接口", project));
        ConversationInfo saved = first.info("chat").orElseThrow();
        assertThat(saved.title()).isEqualTo("修复登录 接口");
        assertThat(saved.projectPath()).isEqualTo(project.toString());

        var resumed = new ConversationService(runtime, sessions(), transcripts(), TestSupport.controlSettings(), mock(SandboxCleanup.class));
        clearInvocations(runtime);
        assertThat(resumed.info("chat")).contains(saved);
        assertThatThrownBy(() -> resumed.send("chat", TestSupport.message("改代码", directory.resolve("other"))))
                .isInstanceOf(ConversationService.ProjectPathMismatchException.class);
        verifyNoInteractions(runtime);
        assertThat(resumed.info("chat")).contains(saved);

        resumed.send("chat", TestSupport.message("继续", project));
        assertThat(resumed.info("chat").orElseThrow().title()).isEqualTo(saved.title());
        assertThat(resumed.info("chat").orElseThrow().createdAt()).isEqualTo(saved.createdAt());
        verify(runtime).chat(eq("chat"), eq("继续"), eq(project), anyList(), any(), any());
    }

    @Test
    void idleCleanupRetriesFailuresAndReopeningDoesNotRepeatSuccessfulCleanup() throws Exception {
        var cleanup = mock(SandboxCleanup.class);
        var service = new ConversationService(mock(AgentRuntime.class), sessions(), transcripts(),
                new com.guodi.assistant.config.AssistantProperties.Control(1, 1, 2), cleanup);
        service.openControl("idle");
        service.send("idle", TestSupport.message("hello", directory));
        service.reclaimExpiredSessions();
        verifyNoInteractions(cleanup);
        Thread.sleep(2200);
        doThrow(new IllegalStateException("Docker unavailable")).doNothing().when(cleanup).reset(directory);
        service.reclaimExpiredSessions();
        service.reclaimExpiredSessions();
        service.reclaimExpiredSessions();
        verify(cleanup, times(2)).reset(directory);
        service.openControl("idle");
        assertThat(service.heartbeat("idle")).isTrue();
        verifyNoMoreInteractions(cleanup);
    }

    @Test
    void listsRecentSessionsAndLegacyHistoryWithoutBindingOrRecoveringItOnRead() {
        var time = Instant.parse("2026-01-01T00:00:00Z");
        sessions().save(new ConversationInfo("recent", "/projects/a", "recent", time, time.plusSeconds(2)));
        sessions().save(new ConversationInfo("older", "/projects/b", "older", time, time));
        var old = new ConversationTurn(3, java.util.UUID.randomUUID(), time.plusSeconds(1),
                ConversationTurn.Status.RUNNING, ConversationTurn.start("旧会话").messages(), null);
        transcripts().save("legacy", old);
        var runtime = mock(AgentRuntime.class);
        var service = new ConversationService(runtime, sessions(), transcripts(), TestSupport.controlSettings(), mock(SandboxCleanup.class));

        assertThat(service.list()).extracting(ConversationInfo::conversationId)
                .containsExactly("recent", "legacy", "older");
        var legacy = service.info("legacy").orElseThrow();
        assertThat(legacy.projectPath()).isNull();
        assertThat(legacy.title()).isEqualTo("旧会话");
        assertThat(sessions().load("legacy")).isEmpty();
        assertThat(transcripts().load("legacy")).containsExactly(old);
        assertThat(service.info("missing")).isEmpty();
        verifyNoInteractions(runtime);

        service.send("legacy", TestSupport.message("明确使用此项目继续", directory));
        var bound = service.info("legacy").orElseThrow();
        assertThat(bound.projectPath()).isEqualTo(directory.toString());
        assertThat(bound.createdAt()).isEqualTo(old.startedAt());
        assertThat(bound.title()).isEqualTo("旧会话");
    }

    @Test
    void metadataMustBeCommittedBeforeExecutionAndActivityFailureDoesNotHideExecutionFailure() {
        var runtime = mock(AgentRuntime.class);
        var store = spy(sessions());
        var service = new ConversationService(runtime, store, transcripts(), TestSupport.controlSettings(), mock(SandboxCleanup.class));
        doThrow(new IllegalStateException("binding failed")).when(store).save(any());
        assertThatThrownBy(() -> service.send("blocked", TestSupport.message("hello", directory)))
                .hasMessage("binding failed");
        verifyNoInteractions(runtime);

        reset(store);
        doCallRealMethod().doThrow(new IllegalStateException("activity failed")).when(store).save(any());
        when(runtime.chat(anyString(), anyString(), any(), anyList(), any(), any()))
                .thenThrow(new IllegalStateException("model failed"));
        assertThatThrownBy(() -> service.send("failed", TestSupport.message("hello", directory)))
                .hasMessage("model failed");
        assertThat(sessions().load("failed")).isPresent();
    }
}
