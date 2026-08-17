package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.promotion.domain.LargestRemainderAllocator.Weight;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** 促销边界、不变量、溢出保护和规则包验签回归。 */
class PromotionDomainRulesTest {
    @Test
    void largestRemainderShouldConserveAndUseStableTieOrder() {
        Map<String, Long> result = LargestRemainderAllocator.allocate(2, List.of(
            new Weight("B", 2, 2L, 1), new Weight("A", 1, 1L, 1), new Weight("C", 3, 3L, 1)));
        assertThat(result).containsExactly(entry("A", 1L), entry("B", 1L), entry("C", 0L));
        assertThatThrownBy(() -> LargestRemainderAllocator.allocate(4,
            List.of(new Weight("A", 1, 1L, 3)))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> LargestRemainderAllocator.allocate(-1,
            List.of(new Weight("A", 1, 1L, 3)))).isInstanceOf(ServiceException.class);
    }

    @Test
    void engineShouldFailClosedForBadInputsAndParameters() {
        assertThatThrownBy(() -> new BasketLine("bad", 1, 1L, null, null, BigDecimal.ONE, 1))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new RuleVersion("01K5R000000000000000000001", RuleType.PERCENT_OFF,
            1, StackMode.BEST_OF_GROUP, null, OffsetDateTime.now(), null,
            new RuleScope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()),
            new RuleBenefit(null, BigDecimal.TEN, null, null, null, null, List.of())))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void packageShouldBeCanonicalAndRejectTampering() throws Exception {
        Instant generated = Instant.parse("2026-08-17T00:00:00Z");
        var encoded = PromotionPackageCodec.encode("tenant-a", 1101L, 2, 1, generated,
            generated.plusSeconds(3600), List.of(
                new PromotionPackageCodec.Record("01K5R000000000000000000002", "rule-b"),
                new PromotionPackageCodec.Record("01K5R000000000000000000001", "rule-a")));
        var encodedAgain = PromotionPackageCodec.encode("tenant-a", 1101L, 2, 1, generated,
            generated.plusSeconds(3600), List.of(
                new PromotionPackageCodec.Record("01K5R000000000000000000001", "rule-a"),
                new PromotionPackageCodec.Record("01K5R000000000000000000002", "rule-b")));
        assertThat(encoded.payload()).containsExactly(encodedAgain.payload());
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(encoded.payload());
        byte[] signature = signer.sign();
        assertThat(PromotionPackageCodec.verify(encoded, encoded.sha256(), signature, keyPair.getPublic())).isTrue();
        assertThat(PromotionPackageCodec.verify(encoded, "0".repeat(64), signature, keyPair.getPublic())).isFalse();
        assertThatThrownBy(() -> PromotionPackageCodec.encode("", 0L, 1, 1, generated,
            generated, List.of())).isInstanceOf(ServiceException.class);
    }
}
