package com.jingshanghui.pos.operations.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyCloseRulesTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void acceptsExactMoneyIdentityAndSafeCommands() {
        assertThat(DailyCloseRules.store(1L)).isEqualTo(1L);
        assertThat(DailyCloseRules.date(LocalDate.of(2026,8,23))).isEqualTo("2026-08-23");
        assertThat(DailyCloseRules.key("daily-close-001")).isEqualTo("daily-close-001");
        assertThat(DailyCloseRules.correlation("trace:001")).isEqualTo("trace:001");
        assertThat(DailyCloseRules.reason("  日结差异已核实并批准  ")).isEqualTo("日结差异已核实并批准");
        assertThat(DailyCloseRules.hash(HASH)).isEqualTo(HASH);
        DailyCloseRules.requireSameHash(HASH,HASH);
        DailyCloseRules.makerChecker(1L,2L,"审批");
        DailyCloseRules.money(1000,100,20,920);
    }

    @Test
    void rejectsInvalidInputConflictAndBrokenMoney() {
        bad(() -> DailyCloseRules.store(null));
        bad(() -> DailyCloseRules.store(0L));
        bad(() -> DailyCloseRules.date(null));
        bad(() -> DailyCloseRules.key(null));
        bad(() -> DailyCloseRules.key("short"));
        bad(() -> DailyCloseRules.key("unsafe/key"));
        bad(() -> DailyCloseRules.correlation(null));
        bad(() -> DailyCloseRules.correlation("bad value"));
        bad(() -> DailyCloseRules.reason(null));
        bad(() -> DailyCloseRules.reason("太短"));
        bad(() -> DailyCloseRules.reason("a".repeat(257)));
        bad(() -> DailyCloseRules.hash(null));
        bad(() -> DailyCloseRules.hash("A".repeat(64)));
        bad(() -> DailyCloseRules.requireSameHash(HASH,"b".repeat(64)));
        bad(() -> DailyCloseRules.makerChecker(1L,1L,"签署"));
        bad(() -> DailyCloseRules.money(-1,0,0,0));
        bad(() -> DailyCloseRules.money(100,101,0,0));
        bad(() -> DailyCloseRules.money(100,0,-1,99));
        bad(() -> DailyCloseRules.money(100,0,0,-1));
        bad(() -> DailyCloseRules.money(100,1,0,100));
    }

    private void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(ServiceException.class);
    }
}
