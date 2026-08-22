package com.jingshanghui.pos.migration.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationRulesTest {
    @Test
    void validatesExactIdentifiersHashesTextQuantityAndCost() {
        assertThat(MigrationRules.ulid("01K2A000000000000000000001", "id")).hasSize(26);
        assertThat(MigrationRules.sha256("a".repeat(64), "hash")).hasSize(64);
        assertThat(MigrationRules.text(" code ", 8, "code")).isEqualTo("code");
        assertThat(MigrationRules.optionalText("  ", 8, "optional")).isNull();
        assertThat(MigrationRules.optionalText(" value ", 8, "optional")).isEqualTo("value");
        assertThat(MigrationRules.quantity("12.340000", "quantity")).isEqualByComparingTo(new BigDecimal("12.340000"));
        assertThat(MigrationRules.nonNegativeCost("21.125")).isEqualByComparingTo(new BigDecimal("21.125000"));
        assertThat(MigrationRules.digest("same")).matches("^[a-f0-9]{64}$");
    }

    @Test
    void rejectsUnsafeOrInexactInputs() {
        assertThatThrownBy(() -> MigrationRules.ulid("bad", "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.ulid(null, "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.sha256("A".repeat(64), "hash")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.sha256(null, "hash")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.text(" ", 8, "name")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.text("123456789", 8, "name")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.text("bad\0value", 20, "name")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.quantity("-1", "quantity")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.quantity("1.0000001", "quantity")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.quantity("10000000000000", "quantity")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.quantity("x", "quantity")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.nonNegativeCost("-0.01")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.nonNegativeCost("bad")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MigrationRules.nonNegativeCost("100000000000000000000")).isInstanceOf(ServiceException.class);
        for (String formula : new String[]{"=1+1", "+SUM(A1)", "-2+3", "@cmd"}) {
            assertThatThrownBy(() -> MigrationRules.rejectFormula(formula, "cell"))
                .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-009");
        }
        MigrationRules.rejectFormula("012345", "barcode");
        MigrationRules.rejectFormula(null, "empty");
        MigrationRules.rejectFormula("", "empty");
    }
}
