package com.jingshanghui.pos.sync.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncHashTest {

    @Test
    void hashesPayloadPageAndSecurityEvidenceDeterministically() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", "01K2A000000000000000000031");
        payload.put("amountMinor", 1299);
        assertThat(SyncHash.payload(new ObjectMapper(), payload))
            .isEqualTo("e92345d37a6b7e78d0fd140b0a8b19c618cb095424353439965ca14026be5ec1");
        assertThat(SyncHash.page(List.of("A", "B"), List.of("1", "2")))
            .isEqualTo(SyncHash.page(List.of("A", "B"), List.of("1", "2")))
            .isNotEqualTo(SyncHash.page(List.of("B", "A"), List.of("2", "1")));
        assertThat(SyncHash.evidence("old", "new", "event")).hasSize(64);
    }

    @Test
    void rejectsMismatchedPageVectors() {
        assertThatThrownBy(() -> SyncHash.page(List.of("A"), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
