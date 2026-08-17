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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** 覆盖促销模型、计算、分摊、状态机和离线包的全部失败关闭分支。 */
class PromotionBoundaryCoverageTest {
    private static final String LINE_A = "01K5R000000000000000000101";
    private static final String LINE_B = "01K5R000000000000000000102";
    private static final String RULE = "01K5R000000000000000000001";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-17T10:00:00+08:00");

    @Test
    void basketAndQuoteContextRejectEveryInvalidComponent() {
        assertBadLine(null, 1, 1L, BigDecimal.ONE, 100);
        assertBadLine("bad", 1, 1L, BigDecimal.ONE, 100);
        assertBadLine(LINE_A, 0, 1L, BigDecimal.ONE, 100);
        assertBadLine(LINE_A, 1, null, BigDecimal.ONE, 100);
        assertBadLine(LINE_A, 1, 0L, BigDecimal.ONE, 100);
        assertBadLine(LINE_A, 1, 1L, null, 100);
        assertBadLine(LINE_A, 1, 1L, BigDecimal.ZERO, 100);
        assertBadLine(LINE_A, 1, 1L, new BigDecimal("1.0000001"), 100);
        assertBadLine(LINE_A, 1, 1L, BigDecimal.ONE, -1);

        BasketLine line = line(LINE_A, 1, 1L, BigDecimal.ONE, 100);
        assertBadQuote(null, 1101L, "POS", List.of(line));
        assertBadQuote(AT, null, "POS", List.of(line));
        assertBadQuote(AT, 0L, "POS", List.of(line));
        assertBadQuote(AT, 1101L, null, List.of(line));
        assertBadQuote(AT, 1101L, " ", List.of(line));
        assertBadQuote(AT, 1101L, "POS", null);
        List<BasketLine> tooMany = new ArrayList<>();
        for (int index = 1; index <= 501; index++) {
            tooMany.add(line(LINE_A, index, 1L, BigDecimal.ONE, 100));
        }
        assertBadQuote(AT, 1101L, "POS", tooMany);
        QuoteRequest request = new QuoteRequest(AT, 1101L, "POS", List.of(line), null);
        assertThat(request.rules()).isEmpty();
    }

    @Test
    void scopeAndRuleWindowUseExplicitAndSemantics() {
        BasketLine line = new BasketLine(LINE_A, 1, 1L, 2L, 3L, BigDecimal.ONE, 100);
        RuleScope all = new RuleScope(null, null, null, null, null);
        assertThat(all.matches(line, 1101L, "POS")).isTrue();
        RuleScope allSix = new RuleScope(null, null, null, null, null, null);
        assertThat(allSix.businessDays()).isEmpty();
        RuleScope monday = new RuleScope(Set.of(1L), Set.of(2L), Set.of(3L), Set.of(1101L),
            Set.of("POS"), Set.of(1));
        assertThat(monday.matches(line, 1101L, "POS", AT)).isTrue();
        assertThat(monday.matches(line, 1101L, "POS", null)).isFalse();
        assertThat(new RuleScope(Set.of(9L), Set.of(), Set.of(), Set.of(), Set.of())
            .matches(line, 1101L, "POS")).isFalse();
        assertThat(new RuleScope(Set.of(1L), Set.of(9L), Set.of(), Set.of(), Set.of())
            .matches(line, 1101L, "POS")).isFalse();
        assertThat(new RuleScope(Set.of(1L), Set.of(2L), Set.of(9L), Set.of(), Set.of())
            .matches(line, 1101L, "POS")).isFalse();
        assertThat(new RuleScope(Set.of(1L), Set.of(2L), Set.of(3L), Set.of(9999L), Set.of())
            .matches(line, 1101L, "POS")).isFalse();
        assertThat(new RuleScope(Set.of(1L), Set.of(2L), Set.of(3L), Set.of(1101L), Set.of("WEB"))
            .matches(line, 1101L, "POS")).isFalse();

        RuleVersion valid = rule(RuleType.AMOUNT_OFF, StackMode.STACKABLE, null,
            new RuleBenefit(10L, null, null, null, null, null, null));
        assertThat(valid.activeAt(null)).isFalse();
        assertThat(valid.activeAt(AT.minusSeconds(1))).isFalse();
        assertThat(valid.activeAt(AT)).isTrue();
        assertThat(valid.activeAt(AT.plusHours(1))).isFalse();
        assertThatThrownBy(() -> new RuleVersion(null, RuleType.AMOUNT_OFF, 1, StackMode.STACKABLE,
            null, AT, AT.plusHours(1), all, valid.benefit())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new RuleVersion("bad", RuleType.AMOUNT_OFF, 1, StackMode.STACKABLE,
            null, AT, AT.plusHours(1), all, valid.benefit())).isInstanceOf(ServiceException.class);
        assertBadRule(null, StackMode.STACKABLE, AT, AT.plusHours(1), all, valid.benefit());
        assertBadRule(RuleType.AMOUNT_OFF, null, AT, AT.plusHours(1), all, valid.benefit());
        assertBadRule(RuleType.AMOUNT_OFF, StackMode.STACKABLE, null, AT.plusHours(1), all, valid.benefit());
        assertBadRule(RuleType.AMOUNT_OFF, StackMode.STACKABLE, AT, AT.plusHours(1), null, valid.benefit());
        assertBadRule(RuleType.AMOUNT_OFF, StackMode.STACKABLE, AT, AT.plusHours(1), all, null);
        assertBadRule(RuleType.AMOUNT_OFF, StackMode.STACKABLE, AT, AT, all, valid.benefit());
        assertThatThrownBy(() -> new RuleVersion(RULE, RuleType.AMOUNT_OFF, 1, StackMode.BEST_OF_GROUP,
            " ", AT, null, all, valid.benefit())).isInstanceOf(ServiceException.class);
    }

    @Test
    void engineRejectsDuplicatesAndAllMalformedRuleParameters() {
        PromotionEngine engine = new PromotionEngine();
        BasketLine first = line(LINE_A, 1, 1L, new BigDecimal("3"), 100);
        assertThatThrownBy(() -> engine.quote(request(List.of(first,
            line(LINE_B, 1, 2L, BigDecimal.ONE, 100)), List.of()))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> engine.quote(request(List.of(first,
            line(LINE_A, 2, 2L, BigDecimal.ONE, 100)), List.of()))).isInstanceOf(ServiceException.class);

        assertBadEngine(engine, first, RuleType.NTH_ITEM_DISCOUNT,
            new RuleBenefit(null, BigDecimal.ONE, null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.NTH_ITEM_DISCOUNT,
            new RuleBenefit(null, BigDecimal.ONE, 1, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.PERCENT_OFF,
            new RuleBenefit(null, null, null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.PERCENT_OFF,
            new RuleBenefit(null, new BigDecimal("-0.1"), null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.PERCENT_OFF,
            new RuleBenefit(null, new BigDecimal("1.1"), null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.PERCENT_OFF,
            new RuleBenefit(null, new BigDecimal("0.123456789"), null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.AMOUNT_OFF,
            new RuleBenefit(null, null, null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.AMOUNT_OFF,
            new RuleBenefit(-1L, null, null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.THRESHOLD_AMOUNT_OFF,
            new RuleBenefit(10L, null, null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.THRESHOLD_QUANTITY_OFF,
            new RuleBenefit(10L, null, null, null, null, null, List.of()));
        assertBadEngine(engine, first, RuleType.THRESHOLD_QUANTITY_OFF,
            new RuleBenefit(10L, null, null, null, BigDecimal.ZERO, null, List.of()));
        assertBadEngine(engine, first, RuleType.BUNDLE_PRICE,
            new RuleBenefit(null, null, null, null, null, 100L, List.of()));
        assertBadEngine(engine, first, RuleType.BUNDLE_PRICE,
            new RuleBenefit(null, null, null, null, null, 100L,
                List.of(new BundleComponent(null, BigDecimal.ONE))));
        assertBadEngine(engine, first, RuleType.BUNDLE_PRICE,
            new RuleBenefit(null, null, null, null, null, 100L,
                List.of(new BundleComponent(1L, null))));
        assertBadEngine(engine, first, RuleType.BUNDLE_PRICE,
            new RuleBenefit(null, null, null, null, null, 100L,
                List.of(new BundleComponent(1L, BigDecimal.ZERO))));

        QuoteResult missingComponent = engine.quote(request(List.of(first), List.of(rule(RuleType.BUNDLE_PRICE,
            StackMode.STACKABLE, null, new RuleBenefit(null, null, null, null, null, 100L,
                List.of(new BundleComponent(9L, BigDecimal.ONE)))))));
        assertThat(missingComponent.discountAmountMinor()).isZero();
        QuoteResult notEnough = engine.quote(request(List.of(first), List.of(rule(RuleType.BUNDLE_PRICE,
            StackMode.STACKABLE, null, new RuleBenefit(null, null, null, null, null, 100L,
                List.of(new BundleComponent(1L, new BigDecimal("4"))))))));
        assertThat(notEnough.discountAmountMinor()).isZero();
    }

    @Test
    void allocatorAndStateMachineCoverAllFailureBranches() {
        assertThatThrownBy(() -> LargestRemainderAllocator.allocate(1, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> LargestRemainderAllocator.allocate(1, List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> LargestRemainderAllocator.allocate(0,
            List.of(new Weight(LINE_A, 1, 1L, 0)))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> LargestRemainderAllocator.allocate(1,
            List.of(new Weight(LINE_A, 1, 1L, 2), new Weight(LINE_B, 2, 2L, -1))))
            .isInstanceOf(ServiceException.class);
        assertThat(LargestRemainderAllocator.allocate(0,
            List.of(new Weight(LINE_A, 1, 1L, 1)))).containsEntry(LINE_A, 0L);

        assertThat(PromotionStates.requireTransition("DRAFT", "VALIDATED")).isEqualTo("VALIDATED");
        assertThat(PromotionStates.requireTransition("DRAFT", "REJECTED")).isEqualTo("REJECTED");
        assertThat(PromotionStates.requireTransition("VALIDATED", "APPROVED")).isEqualTo("APPROVED");
        assertThat(PromotionStates.requireTransition("APPROVED", "PUBLISHED")).isEqualTo("PUBLISHED");
        assertThat(PromotionStates.requireTransition("PUBLISHED", "PAUSED")).isEqualTo("PAUSED");
        assertThat(PromotionStates.requireTransition("PUBLISHED", "RETIRED")).isEqualTo("RETIRED");
        assertThat(PromotionStates.requireTransition("PAUSED", "PUBLISHED")).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> PromotionStates.requireTransition(null, "VALIDATED"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PromotionStates.requireTransition("DRAFT", null))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PromotionStates.requireTransition("UNKNOWN", "VALIDATED"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PromotionStates.requireTransition("DRAFT", "PUBLISHED"))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void packageCodecRejectsIdentityExpiryNullsAndSignatureFailures() throws Exception {
        Instant generated = Instant.parse("2026-08-17T00:00:00Z");
        Instant expires = generated.plusSeconds(60);
        List<PromotionPackageCodec.Record> records = List.of(
            new PromotionPackageCodec.Record("01K5R000000000000000000001", "rule|\\\r\n"));
        var encoded = PromotionPackageCodec.encode("tenant-a", 1101L, 1, 0, generated, expires, records);
        assertThat(encoded.recordCount()).isOne();
        assertBadPackage(null, 1101L, 1, 0, generated, expires, records);
        assertBadPackage(" ", 1101L, 1, 0, generated, expires, records);
        assertBadPackage("tenant-a", null, 1, 0, generated, expires, records);
        assertBadPackage("tenant-a", 0L, 1, 0, generated, expires, records);
        assertBadPackage("tenant-a", 1101L, 0, 0, generated, expires, records);
        assertBadPackage("tenant-a", 1101L, 1, -1, generated, expires, records);
        assertBadPackage("tenant-a", 1101L, 1, 1, generated, expires, records);
        assertBadPackage("tenant-a", 1101L, 1, 0, null, expires, records);
        assertBadPackage("tenant-a", 1101L, 1, 0, generated, null, records);
        assertBadPackage("tenant-a", 1101L, 1, 0, generated, generated, records);
        assertThat(PromotionPackageCodec.encode("tenant-a", 1101L, 1, 0, generated, expires, null)
            .recordCount()).isZero();
        assertThatThrownBy(() -> PromotionPackageCodec.encode("tenant-a", 1101L, 1, 0, generated, expires,
            List.of(new PromotionPackageCodec.Record(null, "rule")))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new PromotionPackageCodec.Record(RULE, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new PromotionPackageCodec.Record(RULE, " ")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PromotionPackageCodec.encode("tenant-a", 1101L, 1, 0, generated, expires,
            List.of(new PromotionPackageCodec.Record(RULE, "one"),
                new PromotionPackageCodec.Record(RULE, "two")))).isInstanceOf(ServiceException.class);

        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(ed.getPrivate());
        signer.update(encoded.payload());
        byte[] signature = signer.sign();
        assertThat(PromotionPackageCodec.verify(null, encoded.sha256(), signature, ed.getPublic())).isFalse();
        assertThat(PromotionPackageCodec.verify(encoded, null, signature, ed.getPublic())).isFalse();
        assertThat(PromotionPackageCodec.verify(encoded, encoded.sha256(), null, ed.getPublic())).isFalse();
        assertThat(PromotionPackageCodec.verify(encoded, encoded.sha256(), signature, null)).isFalse();
        signature[0] ^= 1;
        assertThat(PromotionPackageCodec.verify(encoded, encoded.sha256(), signature, ed.getPublic())).isFalse();
        KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        assertThat(PromotionPackageCodec.verify(encoded, encoded.sha256(), signature, rsa.getPublic())).isFalse();
    }

    private static void assertBadLine(String id, int no, Long sku, BigDecimal quantity, long price) {
        assertThatThrownBy(() -> new BasketLine(id, no, sku, null, null, quantity, price))
            .isInstanceOf(ServiceException.class);
    }

    private static void assertBadQuote(OffsetDateTime at, Long store, String channel, List<BasketLine> lines) {
        assertThatThrownBy(() -> new QuoteRequest(at, store, channel, lines, List.of()))
            .isInstanceOf(ServiceException.class);
    }

    private static void assertBadRule(RuleType type, StackMode mode, OffsetDateTime from, OffsetDateTime to,
                                      RuleScope scope, RuleBenefit benefit) {
        assertThatThrownBy(() -> new RuleVersion(RULE, type, 1, mode, null, from, to, scope, benefit))
            .isInstanceOf(ServiceException.class);
    }

    private static void assertBadEngine(PromotionEngine engine, BasketLine line, RuleType type,
                                        RuleBenefit benefit) {
        assertThatThrownBy(() -> engine.quote(request(List.of(line),
            List.of(rule(type, StackMode.STACKABLE, null, benefit))))).isInstanceOf(ServiceException.class);
    }

    private static void assertBadPackage(String tenant, Long store, long version, long previous,
                                         Instant generated, Instant expires,
                                         List<PromotionPackageCodec.Record> records) {
        assertThatThrownBy(() -> PromotionPackageCodec.encode(tenant, store, version, previous,
            generated, expires, records)).isInstanceOf(ServiceException.class);
    }

    private static BasketLine line(String id, int no, Long sku, BigDecimal quantity, long price) {
        return new BasketLine(id, no, sku, null, null, quantity, price);
    }

    private static QuoteRequest request(List<BasketLine> lines, List<RuleVersion> rules) {
        return new QuoteRequest(AT, 1101L, "POS", lines, rules);
    }

    private static RuleVersion rule(RuleType type, StackMode mode, String group, RuleBenefit benefit) {
        return new RuleVersion(RULE, type, 1, mode, group, AT, AT.plusHours(1),
            new RuleScope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of()), benefit);
    }
}
