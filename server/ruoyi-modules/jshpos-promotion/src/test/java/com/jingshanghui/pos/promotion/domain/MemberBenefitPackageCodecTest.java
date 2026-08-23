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

    @Test
    void everyPackageIdentityBoundaryFailsClosed() {
        Instant generated = Instant.parse("2026-08-23T00:00:00Z");
        Instant expires = generated.plusSeconds(1);
        assertInvalid(() -> encode(null, 1101L, 1, 0, generated, expires, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", null, 1, 0, generated, expires, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", 0L, 1, 0, generated, expires, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", 1101L, 0, 0, generated, expires, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, -1, generated, expires, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, 0, null, expires, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, 0, generated, null, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, 0, generated, generated, List.of(), List.of()));
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, 0, generated, expires,
            java.util.Collections.nCopies(2_001, benefit("01K30000000000000000000001", "GOLD", HASH_A)), List.of()));
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, 0, generated, expires, List.of(),
            java.util.Collections.nCopies(500_001,
                price("01K30000000000000000000003", 1, "GOLD", 1L, 1L, null, 1, HASH_A))));
        assertThat(encode("TENANT_A", 1101L, 1, 0, generated, expires, null, null).benefitCount()).isZero();
    }

    @Test
    void everyBenefitRecordBoundaryFailsClosed() {
        assertInvalidBenefit(null);
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord(null, "GOLD", true, false,
            "BEST_PRICE", false, 0, FROM, TO, HASH_A));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("bad", "GOLD", true, false,
            "BEST_PRICE", false, 0, FROM, TO, HASH_A));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("01K30000000000000000000001", null, true, false,
            "BEST_PRICE", false, 0, FROM, TO, HASH_A));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("01K30000000000000000000001", "bad level", true, false,
            "BEST_PRICE", false, 0, FROM, TO, HASH_A));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("01K30000000000000000000001", "GOLD", true, false,
            "UNKNOWN", false, 0, FROM, TO, HASH_A));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("01K30000000000000000000001", "GOLD", true, false,
            "BEST_PRICE", false, -1, FROM, TO, HASH_A));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("01K30000000000000000000001", "GOLD", true, false,
            "BEST_PRICE", false, 0, FROM, TO, null));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("01K30000000000000000000001", "GOLD", true, false,
            "BEST_PRICE", false, 0, null, TO, HASH_A));
        assertInvalidBenefit(new MemberBenefitPackageCodec.BenefitRecord("01K30000000000000000000001", "GOLD", true, false,
            "BEST_PRICE", false, 0, FROM, FROM, HASH_A));
    }

    @Test
    void everyMemberPriceRecordBoundaryFailsClosed() {
        assertInvalidPrice(null);
        assertInvalidPrice(price(null, 1, "GOLD", 1L, 1L, null, 1, HASH_A));
        assertInvalidPrice(price("bad", 1, "GOLD", 1L, 1L, null, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 0, "GOLD", 1L, 1L, null, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 1, null, 1L, 1L, null, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 1, "GOLD", null, 1L, null, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 1, "GOLD", 0L, 1L, null, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 1, "GOLD", 1L, null, null, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 1, "GOLD", 1L, 0L, null, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 1, "GOLD", 1L, 1L, 0L, 1, HASH_A));
        assertInvalidPrice(price("01K30000000000000000000003", 1, "GOLD", 1L, 1L, null, 1, null));
        assertInvalidPrice(new MemberBenefitPackageCodec.MemberPriceRecord("01K30000000000000000000003", 1,
            "GOLD", 1L, 1L, null, 1, null, TO, HASH_A));
        assertInvalidPrice(new MemberBenefitPackageCodec.MemberPriceRecord("01K30000000000000000000003", 1,
            "GOLD", 1L, 1L, null, 1, FROM, FROM, HASH_A));
    }

    private static MemberBenefitPackageCodec.EncodedPackage encode(String tenantId, Long storeId,
                                                                     long packageVersion, long previousVersion,
                                                                     Instant generatedAt, Instant expiresAt,
                                                                     List<MemberBenefitPackageCodec.BenefitRecord> benefits,
                                                                     List<MemberBenefitPackageCodec.MemberPriceRecord> prices) {
        return MemberBenefitPackageCodec.encode(tenantId, storeId, packageVersion, previousVersion,
            generatedAt, expiresAt, benefits, prices);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(ServiceException.class);
    }

    private static void assertInvalidBenefit(MemberBenefitPackageCodec.BenefitRecord record) {
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, 0, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
            java.util.Collections.singletonList(record), List.of()));
    }

    private static void assertInvalidPrice(MemberBenefitPackageCodec.MemberPriceRecord record) {
        assertInvalid(() -> encode("TENANT_A", 1101L, 1, 0, Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
            List.of(), java.util.Collections.singletonList(record)));
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
