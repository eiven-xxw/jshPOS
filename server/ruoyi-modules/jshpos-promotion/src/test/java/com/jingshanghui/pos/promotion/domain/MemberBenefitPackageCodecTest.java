package com.jingshanghui.pos.promotion.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MemberBenefitPackageCodecTest {
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 23, 0, 0);
    private static final LocalDateTime TO = FROM.plusDays(30);
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @Test
    void canonicalPayloadIsStableSortedAndContainsNoMemberIdentity() {
        var benefits = List.of(
            benefit("01K30000000000000000000002", "SILVER", HASH_B),
            benefit("01K30000000000000000000001", "GOLD", HASH_A));
        var prices = List.of(
            price("01K30000000000000000000004", 1, "GOLD", 2L, 2L, null, 580, HASH_B),
            price("01K30000000000000000000003", 2, "GOLD", 1L, 1L, 1101L, 480, HASH_A));

        var encoded = MemberBenefitPackageCodec.encode("TENANT_A", 1101L, 1, 0,
            Instant.parse("2026-08-23T00:00:00Z"), Instant.parse("2026-09-23T00:00:00Z"), benefits, prices);
        String payload = new String(encoded.payload(), StandardCharsets.UTF_8);

        assertThat(payload).startsWith("JSHMBP|1.0|member-benefit-engine-1.0.0|TENANT_A|1101|1|0|");
        assertThat(payload.indexOf("|GOLD|")).isLessThan(payload.indexOf("|SILVER|"));
        assertThat(payload).doesNotContain("phone", "mobile", "identityValue", "memberId");
        assertThat(encoded.sha256()).matches("^[a-f0-9]{64}$");
        assertThat(encoded.benefitCount()).isEqualTo(2);
        assertThat(encoded.memberPriceCount()).isEqualTo(2);
    }

    @Test
    void corruptDelimiterInvalidMoneyAndNonContiguousVersionFailClosed() {
        assertThatThrownBy(() -> MemberBenefitPackageCodec.encode("TENANT|B", 1101L, 1, 0,
            Instant.EPOCH, Instant.EPOCH.plusSeconds(1), List.of(), List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MemberBenefitPackageCodec.encode("TENANT_A", 1101L, 3, 1,
            Instant.EPOCH, Instant.EPOCH.plusSeconds(1), List.of(), List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MemberBenefitPackageCodec.encode("TENANT_A", 1101L, 1, 0,
            Instant.EPOCH, Instant.EPOCH.plusSeconds(1), List.of(),
            List.of(price("01K30000000000000000000003", 1, "GOLD", 1L, 1L, null, -1, HASH_A))))
            .isInstanceOf(ServiceException.class);
    }

    private static MemberBenefitPackageCodec.BenefitRecord benefit(String id, String level, String hash) {
        return new MemberBenefitPackageCodec.BenefitRecord(id, level, true, false, "BEST_PRICE", false,
            0, FROM, TO, hash);
    }

    private static MemberBenefitPackageCodec.MemberPriceRecord price(String id, int version, String level,
                                                                      Long sku, Long unit, Long store,
                                                                      long amount, String hash) {
        return new MemberBenefitPackageCodec.MemberPriceRecord(id, version, level, sku, unit, store,
            amount, FROM, TO, hash);
    }
}
