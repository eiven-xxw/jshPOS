package com.jingshanghui.pos.saas.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/** SaaS 格式、幂等、审批、生效窗口和受控恢复固定向量。 */
class SaasRulesTest {
    @Test void normalizesAndValidatesCodesKeysAndHashes() {
        assertThat(SaasRules.code(" store_count ","feature")).isEqualTo("STORE_COUNT");
        assertThat(SaasRules.key("request-001")).isEqualTo("request-001");
        assertThat(SaasRules.hash("A".repeat(64))).isEqualTo("a".repeat(64));
        assertThat(SaasRules.required(" x ","x")).isEqualTo("x");
        assertThatThrownBy(() -> SaasRules.code("1bad","x")).hasMessageContaining("SAA-VALID-001");
        assertThatThrownBy(() -> SaasRules.key("short")).hasMessageContaining("SAA-IDEMP-001");
        assertThatThrownBy(() -> SaasRules.hash("bad")).hasMessageContaining("SAA-HASH-001");
        assertThatThrownBy(() -> SaasRules.required(" ","x")).hasMessageContaining("SAA-VALID-002");
    }
    @Test void enforcesWindowApprovalIdempotencyAndPositiveValues() {
        Instant start=Instant.parse("2026-08-23T00:00:00Z");
        assertThatCode(() -> SaasRules.window(start,start.plusSeconds(1))).doesNotThrowAnyException();
        assertThatCode(() -> SaasRules.window(start,null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SaasRules.window(null,null)).hasMessageContaining("SAA-ENT-001");
        assertThatThrownBy(() -> SaasRules.window(start,start)).hasMessageContaining("SAA-ENT-001");
        assertThatCode(() -> SaasRules.separate(1,2)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SaasRules.separate(1,1)).hasMessageContaining("SAA-APPROVAL-001");
        assertThatCode(() -> SaasRules.sameHash("a".repeat(64),"A".repeat(64))).doesNotThrowAnyException();
        assertThatThrownBy(() -> SaasRules.sameHash("a".repeat(64),"b".repeat(64))).hasMessageContaining("SAA-IDEMP-002");
        assertThatCode(() -> SaasRules.positive(1,"value")).doesNotThrowAnyException();
        assertThatThrownBy(() -> SaasRules.positive(0,"value")).hasMessageContaining("SAA-VALID-003");
    }
    @Test void suspendedTenantOnlyKeepsControlledRecoveryCapabilities() {
        assertThat(SaasRules.featureAllowed("ACTIVE",true,"SALE")).isTrue();
        assertThat(SaasRules.featureAllowed("ACTIVE",false,"SALE")).isFalse();
        assertThat(SaasRules.featureAllowed("SUSPENDED",true,"SALE")).isFalse();
        assertThat(SaasRules.featureAllowed("SUSPENDED",false,"REFUND")).isTrue();
        assertThat(SaasRules.featureAllowed("TERMINATED_LOGICAL",false,"LEGAL_EXPORT")).isTrue();
    }
}
