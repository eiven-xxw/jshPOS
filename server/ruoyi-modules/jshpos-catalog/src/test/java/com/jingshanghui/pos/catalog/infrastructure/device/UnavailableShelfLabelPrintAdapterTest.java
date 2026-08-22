package com.jingshanghui.pos.catalog.infrastructure.device;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnavailableShelfLabelPrintAdapterTest {

    @Test
    void neverClaimsRealPrinterSuccess() {
        var result = new UnavailableShelfLabelPrintAdapter().dispatch(1L, "a".repeat(64));
        assertThat(result.accepted()).isFalse();
        assertThat(result.code()).isEqualTo("PRINTER_UNAVAILABLE");
        assertThat(result.message()).contains("BLOCKED", "真实打印");
    }
}
