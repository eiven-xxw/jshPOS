package com.jingshanghui.pos.catalog.domain;

import com.jingshanghui.pos.catalog.domain.LotExpiryRules.PolicySpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LotExpiryRulesTest {
    private final PolicySpec production = new PolicySpec("01J6ZP4B9Q7X3C5N8M2K6T1R0V", 10L, 20L,
        true, "PRODUCTION_DATE", 30, 7, Instant.parse("2026-08-23T00:00:00Z"));

    @Test
    void resolvesCalendarDatesAndClassifiesThresholdsDeterministically() {
        LocalDate expiry = LotExpiryRules.resolveExpiry(production, LocalDate.of(2024, 2, 29),
            LocalDate.of(2024, 3, 1), null);
        assertThat(expiry).isEqualTo(LocalDate.of(2024, 3, 29));
        assertThat(LotExpiryRules.classify(LocalDate.of(2024, 3, 22), expiry, 7)).isEqualTo("NEAR_EXPIRY");
        assertThat(LotExpiryRules.classify(expiry, expiry, 7)).isEqualTo("NEAR_EXPIRY");
        assertThat(LotExpiryRules.classify(expiry.plusDays(1), expiry, 7)).isEqualTo("EXPIRED");
    }

    @Test
    void explicitExpiryRejectsShelfLifeAndDatesBeforeReceipt() {
        PolicySpec explicit = new PolicySpec("01J6ZP4B9Q7X3C5N8M2K6T1R0W", 10L, 20L, true,
            "EXPLICIT_EXPIRY_DATE", null, 0, Instant.parse("2026-08-23T00:00:00Z"));
        assertThat(LotExpiryRules.resolveExpiry(explicit, null, LocalDate.of(2026, 8, 23),
            LocalDate.of(2026, 8, 23))).isEqualTo(LocalDate.of(2026, 8, 23));
        assertThatThrownBy(() -> LotExpiryRules.resolveExpiry(explicit, null, LocalDate.of(2026, 8, 23),
            LocalDate.of(2026, 8, 22))).hasMessageContaining("CAT-LOT-003");
    }

    @Test
    void contentDigestIsStableAndSensitiveToPolicyContent() {
        assertThat(LotExpiryRules.contentSha256(production)).hasSize(64)
            .isEqualTo(LotExpiryRules.contentSha256(production));
        assertThat(LotExpiryRules.contentSha256(production)).isNotEqualTo(LotExpiryRules.contentSha256(
            new PolicySpec(production.policyVersionId(), 10L, 20L, true, "PRODUCTION_DATE", 31, 7,
                production.effectiveFrom())));
    }
}
