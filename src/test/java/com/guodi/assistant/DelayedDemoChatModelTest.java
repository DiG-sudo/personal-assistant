package com.guodi.assistant;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;

import com.guodi.assistant.component.model.DelayedDemoChatModel;

import static org.assertj.core.api.Assertions.assertThat;

class DelayedDemoChatModelTest {
    @Test
    void blocksForThreeSecondsThenReturnsTheDemoAnswer() {
        long started = System.nanoTime();
        var response = new DelayedDemoChatModel().call(new Prompt("排队演示"));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
        assertThat(response.getResult().getOutput().getText()).contains("排队演示");
    }
}
