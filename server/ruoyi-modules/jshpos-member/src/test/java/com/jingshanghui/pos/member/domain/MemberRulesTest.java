package com.jingshanghui.pos.member.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** 覆盖会员身份标准化、掩码和输入边界。 */
class MemberRulesTest {
    @Test void normalizesSyntheticIdentityAndMasksWithoutCleartext() {
        assertThat(MemberRules.normalizeIdentity("MOBILE", "  +8613800000000 ")).isEqualTo("+8613800000000");
        assertThat(MemberRules.mask("MOBILE", "+8613800000000")).isEqualTo("+86****0000");
        assertThat(MemberRules.normalizeIdentity("CARD", " SYNTHETIC-CARD-01 ")).isEqualTo("SYNTHETIC-CARD-01");
        assertThat(MemberRules.mask("CARD", "SYNTHETIC-CARD-01")).isEqualTo("SY****01");
        assertThat(MemberRules.mask("CARD", "ABCD")).isEqualTo("****");
    }

    @Test void rejectsUnknownMalformedAndMissingIdentity() {
        assertThatThrownBy(() -> MemberRules.normalizeIdentity("EMAIL", "synthetic@example.invalid"))
            .hasMessageContaining("MEM-IDENTITY-001");
        assertThatThrownBy(() -> MemberRules.normalizeIdentity("MOBILE", null))
            .hasMessageContaining("MEM-IDENTITY-001");
        assertThatThrownBy(() -> MemberRules.normalizeIdentity("MOBILE", "13800000000"))
            .hasMessageContaining("MEM-IDENTITY-001");
        assertThatThrownBy(() -> MemberRules.normalizeIdentity("CARD", "空 格"))
            .hasMessageContaining("MEM-IDENTITY-001");
    }

    @Test void validatesUlidAndReason() {
        MemberRules.requireUlid("01K5C000000000000000000001", "会员");
        assertThatThrownBy(() -> MemberRules.requireUlid(null, "会员")).hasMessageContaining("MEM-INPUT-001");
        assertThatThrownBy(() -> MemberRules.requireUlid("bad", "会员")).hasMessageContaining("MEM-INPUT-001");
        assertThat(MemberRules.requireReason(" 合成审批原因 ")).isEqualTo("合成审批原因");
        assertThatThrownBy(() -> MemberRules.requireReason(" ")).hasMessageContaining("MEM-INPUT-002");
        assertThatThrownBy(() -> MemberRules.requireReason("x".repeat(257))).hasMessageContaining("MEM-INPUT-002");
    }
}
