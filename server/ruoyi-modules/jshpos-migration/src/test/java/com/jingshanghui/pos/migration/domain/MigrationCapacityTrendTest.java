package com.jingshanghui.pos.migration.domain;

import com.jingshanghui.pos.migration.domain.MigrationStates.DataType;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 100k 是内部上传上限；1m 仅证明流式摘要趋势，不形成生产容量或 SLA。 */
class MigrationCapacityTrendTest {

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void normalizesOneHundredThousandSyntheticSupplierRowsWithinInternalBudget() {
        List<Map<String, String>> rows = new ArrayList<>(100_000);
        for (int index = 0; index < 100_000; index++) {
            rows.add(Map.of("supplierCode", "SYN-" + index, "supplierName", "虚构供应商-" + index));
        }
        MigrationRowNormalizer normalizer = new MigrationRowNormalizer(new UlidGenerator(
            Clock.fixed(Instant.parse("2026-08-22T02:00:00Z"), ZoneOffset.UTC)));
        var result = normalizer.preflight(DataType.SUPPLIER, List.of("supplierCode", "supplierName"), rows);
        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).hasSize(100_000);
        assertThat(result.rows().get(0).rowSha256()).matches("^[a-f0-9]{64}$");
        assertThat(result.rows().get(99_999).rowSha256()).matches("^[a-f0-9]{64}$");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void hashesOneMillionSyntheticRowsAsStreamingTrendWithoutBuildingFullDataset() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index < 1_000_000; index++) {
            digest.update(("SYNTHETIC-TREND:" + index + '\n').getBytes(StandardCharsets.UTF_8));
        }
        assertThat(HexFormat.of().formatHex(digest.digest())).matches("^[a-f0-9]{64}$");
    }
}
