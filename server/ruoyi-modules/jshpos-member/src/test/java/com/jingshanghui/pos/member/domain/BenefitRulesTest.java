package com.jingshanghui.pos.member.domain;

import com.jingshanghui.pos.member.application.model.BenefitCommands.LevelRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** 覆盖权益等级、门店范围和生效窗口的失败关闭规则。 */
class BenefitRulesTest {
    @Test void acceptsDistinctLevelRulesAndStores() {
        assertThat(BenefitRules.requireLevelRules(List.of(new LevelRule("GOLD",true,false)))).hasSize(1);
        assertThat(BenefitRules.requireStoreIds(List.of(1001L,1002L))).containsExactly(1001L,1002L);
        assertThatCode(() -> BenefitRules.requireWindow(LocalDateTime.of(2026,8,23,1,0),
            LocalDateTime.of(2026,8,24,1,0))).doesNotThrowAnyException();
    }

    @Test void rejectsMissingDuplicateAndMalformedLevelRules() {
        assertThatThrownBy(() -> BenefitRules.requireLevelRules(null)).hasMessageContaining("MEM-BENEFIT-001");
        assertThatThrownBy(() -> BenefitRules.requireLevelRules(List.of())).hasMessageContaining("MEM-BENEFIT-001");
        assertThatThrownBy(() -> BenefitRules.requireLevelRules(List.of(new LevelRule("bad code",true,false))))
            .hasMessageContaining("MEM-BENEFIT-002");
        assertThatThrownBy(() -> BenefitRules.requireLevelRules(List.of(new LevelRule("GOLD",true,false),
            new LevelRule("GOLD",false,false)))).hasMessageContaining("MEM-BENEFIT-003");
    }

    @Test void rejectsMissingDuplicateAndInvalidStoreScopes() {
        assertThatThrownBy(() -> BenefitRules.requireStoreIds(null)).hasMessageContaining("MEM-BENEFIT-004");
        assertThatThrownBy(() -> BenefitRules.requireStoreIds(List.of())).hasMessageContaining("MEM-BENEFIT-004");
        assertThatThrownBy(() -> BenefitRules.requireStoreIds(List.of(1001L,1001L)))
            .hasMessageContaining("MEM-BENEFIT-005");
        assertThatThrownBy(() -> BenefitRules.requireStoreIds(java.util.Arrays.asList(1001L,null)))
            .hasMessageContaining("MEM-BENEFIT-005");
        assertThatThrownBy(() -> BenefitRules.requireStoreIds(List.of(-1L)))
            .hasMessageContaining("MEM-BENEFIT-005");
    }

    @Test void rejectsInvalidWindows() {
        LocalDateTime at=LocalDateTime.of(2026,8,23,1,0);
        assertThatThrownBy(() -> BenefitRules.requireWindow(null,at)).hasMessageContaining("MEM-BENEFIT-006");
        assertThatThrownBy(() -> BenefitRules.requireWindow(at,at)).hasMessageContaining("MEM-BENEFIT-006");
        assertThatCode(() -> BenefitRules.requireWindow(at,null)).doesNotThrowAnyException();
    }
}
