package com.jingshanghui.pos.catalog.application.price;

import com.jingshanghui.pos.catalog.application.price.PriceResolution.Candidate;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceResolutionTest {

    private static final Instant T0 = Instant.parse("2026-08-16T00:00:00Z");

    @Test
    void storePriceWinsAndFutureBoundaryIsHalfOpen() {
        Candidate base = candidate(1, 1, 1, "TENANT_BASE", null, 100, T0, null, true);
        Candidate storeOld = candidate(2, 2, 1, "STORE", 9L, 90, T0, T0.plusSeconds(3600), true);
        Candidate storeNew = candidate(3, 3, 2, "STORE", 9L, 80, T0.plusSeconds(3600), null, true);
        assertThat(PriceResolution.resolve(List.of(base, storeNew, storeOld), 9L, T0).amountMinor()).isEqualTo(90);
        assertThat(PriceResolution.resolve(List.of(base, storeOld, storeNew), 9L, T0.plusSeconds(3600)).amountMinor())
            .isEqualTo(80);
        assertThat(PriceResolution.resolve(List.of(base, storeOld), 8L, T0.plusSeconds(1)).amountMinor()).isEqualTo(100);
    }

    @Test
    void ignoresUnpublishedExpiredAndWrongStoreAndSelectsLatestTie() {
        Candidate unpublished = candidate(1, 1, 1, "STORE", 9L, 1, T0, null, false);
        Candidate wrongStore = candidate(2, 2, 1, "STORE", 8L, 2, T0, null, true);
        Candidate expired = candidate(3, 3, 1, "TENANT_BASE", null, 3, T0.minusSeconds(10), T0, true);
        Candidate olderId = candidate(4, 4, 1, "TENANT_BASE", null, 4, T0, null, true);
        Candidate newerId = candidate(5, 5, 1, "TENANT_BASE", null, 5, T0, null, true);
        assertThat(PriceResolution.resolve(List.of(unpublished, wrongStore, expired, olderId, newerId), 9L, T0)
            .priceItemId()).isEqualTo(5L);
    }

    @Test
    void rejectsInvalidCandidatesAndMissingResolution() {
        assertThatThrownBy(() -> PriceResolution.resolve(List.of(), 1L, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PriceResolution.resolve(List.of(), 1L, T0)).isInstanceOf(ServiceException.class);
        assertBad(() -> candidate(1, 1, 0, "TENANT_BASE", null, 1, T0, null, true));
        assertBad(() -> candidate(1, 1, 1, "BAD", null, 1, T0, null, true));
        assertBad(() -> candidate(1, 1, 1, "STORE", null, 1, T0, null, true));
        assertBad(() -> candidate(1, 1, 1, "TENANT_BASE", 1L, 1, T0, null, true));
        assertBad(() -> candidate(1, 1, 1, "TENANT_BASE", null, 1, null, null, true));
        assertBad(() -> candidate(1, 1, 1, "TENANT_BASE", null, 1, T0, T0, true));
        assertBad(() -> new Candidate(1L, 1L, 1, "TENANT_BASE", null, -1L, "CNY", T0, null, true));
        assertBad(() -> new Candidate(1L, 1L, 1, "TENANT_BASE", null, 1L, "USD", T0, null, true));
    }

    private Candidate candidate(long book, long item, int version, String scope, Long store, long amount,
                                Instant from, Instant to, boolean published) {
        return new Candidate(book, item, version, scope, store, amount, "CNY", from, to, published);
    }

    private void assertBad(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceException.class);
    }
}
