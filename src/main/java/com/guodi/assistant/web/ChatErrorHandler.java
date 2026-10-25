package com.guodi.assistant.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.guodi.assistant.core.AgentException;

@RestControllerAdvice
public class ChatErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatErrorHandler.class);

    @ExceptionHandler(AgentException.class)
    ResponseEntity<Map<String, String>> agentFailure(AgentException error) {
        return ResponseEntity.status(502).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<Map<String, String>> invalidRequest(jakarta.validation.ConstraintViolationException error) {
        return ResponseEntity.badRequest().body(Map.of("error", "会话标识格式不正确。"));
    }

    @ExceptionHandler(java.io.UncheckedIOException.class)
    ResponseEntity<Map<String, String>> storageFailure(java.io.UncheckedIOException error) {
        log.error("Conversation storage failure", error);
        return ResponseEntity.status(503).body(Map.of("error", "对话存储暂时不可用，请查看服务日志后重试。"));
    }
}
