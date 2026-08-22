package com.jingshanghui.pos.procurement.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 百万级纯规则趋势基线；只代表 CI 合成计算，不构成生产容量承诺。 */
class ReplenishmentMillionTrendTest {

    @Test
    void evaluatesOneMillionSyntheticDimensionsWithinInternalTrendBudget() {
        Instant started = Instant.now();
        long checksum = 0;
        for (int index = 0; index < 1_000_000; index++) {
            BigDecimal available = BigDecimal.valueOf(index % 8).setScale(6);
            var result = ReplenishmentRules.calculate(available, BigDecimal.ONE.setScale(6),
                new BigDecimal("10.000000"), new BigDecimal("30.000000"),
                new BigDecimal("2.000000"), new BigDecimal("3.000000"), 1, 1, true);
            checksum += result.orElseThrow().suggestedPurchaseQuantity().longValueExact();
        }
        long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
        assertThat(checksum).isEqualTo(26_625_000L);
        assertThat(elapsedMillis).as("宽松内部趋势阈值，不是商业 SLA").isLessThan(30_000L);
    }
}
