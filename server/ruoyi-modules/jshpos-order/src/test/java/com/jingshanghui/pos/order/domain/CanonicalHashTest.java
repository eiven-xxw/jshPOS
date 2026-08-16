package com.jingshanghui.pos.order.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalHashTest {

    @Test
    void producesStableLowercaseSha256() {
        assertThat(CanonicalHash.sha256("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void matchesTheFlutterGate2CashCommandGoldenVector() {
        List<Object> values = List.of(
            "01K2A000000000000000000031", "A-T1-000001", 1101L,
            "01K2A000000000000000000011", "01K2A000000000000000000021", 101L,
            "2026-08-16", "Asia/Shanghai", 1L, 1L, "CONVENIENCE.1", 1299L, 1299L, 2000L,
            "01K2A000000000000000000041", 1, 701L, "A-SKU-001", "001234", "Synthetic Water",
            301L, "PCS", "1", 1299L, 1299L, 1299L, "TENANT_BASE"
        );
        assertThat(CanonicalHash.sha256(CanonicalHash.lengthPrefixed(values)))
            .isEqualTo("60337986451e5a511783f4d77eaac27598fef47f997336a4bbb599c25fd68e5a");
    }
}
