package com.jingshanghui.pos.foundation.application.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSanitizerTest {

    private final AuditSanitizer sanitizer = new AuditSanitizer();

    @Test
    void recursivelyRedactsSecretsAndIsDeterministic() {
        AuditSanitizer.SanitizedPayload first = sanitizer.sanitize(Map.of(
            "name", "synthetic",
            "nested", Map.of("accessToken", "never-store", "phone_number", "13800000000")
        ));
        AuditSanitizer.SanitizedPayload second = sanitizer.sanitize(Map.of(
            "nested", Map.of("phone_number", "different", "accessToken", "different"),
            "name", "synthetic"
        ));

        assertThat(first.json()).doesNotContain("never-store", "13800000000").contains("***");
        assertThat(first.sha256()).isEqualTo(second.sha256());
    }

    @Test
    void replacesOversizedSummaryWithHashReference() {
        AuditSanitizer.SanitizedPayload payload = sanitizer.sanitize(Map.of("data", "x".repeat(9000)));

        assertThat(payload.json()).contains("truncated", payload.sha256());
    }

    @Test
    void normalizesObjectsCollectionsAndParsesStoredMap() {
        AuditSanitizer.SanitizedPayload payload = sanitizer.sanitize(List.of(
            new SyntheticPayload("safe", "never-store"), Map.of("id_no", "440000")));

        assertThat(payload.json()).contains("safe", "***").doesNotContain("never-store", "440000");
        assertThat(sanitizer.parseMap("{\"b\":2,\"a\":1}"))
            .containsEntry("a", 1).containsEntry("b", 2);
    }

    @Test
    void rejectsCorruptStoredSummary() {
        assertThatThrownBy(() -> sanitizer.parseMap("{not-json"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not valid JSON");
    }

    private record SyntheticPayload(String name, String privateKey) {
    }
}
