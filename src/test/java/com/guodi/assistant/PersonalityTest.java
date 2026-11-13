package com.guodi.assistant;

import org.junit.jupiter.api.Test;

import com.guodi.assistant.component.personality.Personality;
import com.guodi.assistant.versioning.ExecutionManifest.Ref;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalityTest {

    @Test
    void contentFingerprintStaysStableWhenAnotherRevisionIsCreated() {
        Personality original = new Personality(new Ref("test-personality", "1.0.0"),
                "活泼、跳脱。",
                "可以抽象一点，但不必句句玩梗。",
                "用户：今天很累。\n助手：先坐会儿，今天的电量先别借给全世界。");
        String originalText = original.text();
        String originalHash = original.sha256();

        Personality next = new Personality(new Ref("test-personality", "1.1.0"),
                original.coreSetting(), "认真讨论时减少玩梗。", original.dialogueExamples());

        assertThat(originalHash).isEqualTo("9c7c7951679ed936faa9efea760a1039f583c7e9d830f851da168e25c81fc659");
        assertThat(original.revision()).isEqualTo(new Ref("test-personality", "1.0.0"));
        assertThat(original.text()).isEqualTo(originalText);
        assertThat(original.sha256()).isEqualTo(originalHash);
        assertThat(next.revision()).isEqualTo(new Ref("test-personality", "1.1.0"));
        assertThat(next.sha256()).isNotEqualTo(originalHash);
    }

    @Test
    void builtInLivelyVersionKeepsItsReleasedContentFingerprint() {
        Personality personality = Personality.livelyV1();

        assertThat(personality.revision()).isEqualTo(new Ref("lively-companion", "1.0.0"));
        assertThat(personality.sha256()).isEqualTo("4fa10114de827ce9b4b730417404cbed357a3bb8ff9d76135ffc5cd541bbe3ee");
    }

    @Test
    void revisionMustNameAnExplicitPersonalityVersion() {
        assertThatThrownBy(() -> new Personality(new Ref("", "1.0.0"), "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Personality(new Ref("test", ""), "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Personality(new Ref("test", "latest"), "", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
