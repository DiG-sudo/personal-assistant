package com.guodi.assistant;

import com.guodi.assistant.component.personality.Personality;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalityTest {
    @Test
    void rendersTheThreeSectionsInPromptOrder() {
        var personality = new Personality("core", "expression", "example");

        assertThat(personality.text()).isEqualTo("""
                ## 核心设定
                core

                ## 表达约定
                expression

                ## 对话示例（风格参考，非真实聊天记录）
                example
                """);
    }

    @Test
    void builtInPersonalityStillContainsAllThreeSections() {
        var personality = Personality.lively();

        assertThat(personality.coreSetting()).contains("活泼");
        assertThat(personality.expression()).isNotBlank();
        assertThat(personality.dialogueExamples()).contains("用户：", "助手：");
    }
}
