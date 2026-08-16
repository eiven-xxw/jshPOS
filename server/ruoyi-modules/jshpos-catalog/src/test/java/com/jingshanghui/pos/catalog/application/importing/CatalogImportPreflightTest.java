package com.jingshanghui.pos.catalog.application.importing;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogImportPreflightTest {

    private final CatalogImportPreflight preflight = new CatalogImportPreflight();

    @Test
    void validatesAcceptedAndRejectedBatchesDeterministically() {
        CatalogImportPreflight.Result accepted = preflight.validate(List.of(valid(1), valid(2)));
        assertThat(accepted.accepted()).isTrue();
        assertThat(accepted.rowCount()).isEqualTo(2);
        assertThat(accepted.errorCount()).isZero();
        assertThat(accepted.payloadSha256()).matches("[a-f0-9]{64}");
        assertThat(preflight.validate(List.of(valid(1), valid(2))).payloadSha256())
            .isEqualTo(accepted.payloadSha256());

        CatalogImportRow duplicate = new CatalogImportRow(3, "SPU-3", "SKU-1", "bad", "CAT", "BRAND",
            "BAD_TYPE", "PCS", "1.0000001", 0L, 1L, List.of("BC1", "BC1", "bad barcode"));
        CatalogImportPreflight.Result rejected = preflight.validate(Arrays.asList(valid(1), duplicate, null));
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.errorCount()).isGreaterThanOrEqualTo(6);
        assertThat(rejected.errors()).extracting(CatalogImportPreflight.RowError::field)
            .contains("SKU_CODE", "TYPE", "QUANTITY", "RATIO", "BARCODE", "NULL_ROW");
        assertThatThrownBy(() -> rejected.errors().add(new CatalogImportPreflight.RowError(1, "X", "Y")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyAndOversizedBatchAndCapsErrorDetail() {
        CatalogImportPreflight.Result empty = preflight.validate(List.of());
        assertThat(empty.errorCount()).isEqualTo(1);
        assertThat(empty.errors().get(0).field()).isEqualTo("EMPTY_BATCH");

        assertThatThrownBy(() -> preflight.validate(rows(CatalogImportPreflight.MAX_ROWS + 1, false)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("100000");

        CatalogImportPreflight.Result capped = preflight.validate(rows(10_001, true));
        assertThat(capped.errorCount()).isEqualTo(CatalogImportPreflight.MAX_REPORTED_ERRORS);
    }

    @Test
    void exercisesTenThousandAndHundredThousandSyntheticCapacity() throws Exception {
        Instant start10k = Instant.now();
        CatalogImportPreflight.Result tenThousand = preflight.validate(rows(10_000, false));
        long duration10k = Duration.between(start10k, Instant.now()).toMillis();
        Instant start100k = Instant.now();
        CatalogImportPreflight.Result hundredThousand = preflight.validate(rows(100_000, false));
        long duration100k = Duration.between(start100k, Instant.now()).toMillis();
        assertThat(tenThousand.accepted()).isTrue();
        assertThat(hundredThousand.accepted()).isTrue();
        assertThat(hundredThousand.rowCount()).isEqualTo(100_000);
        String json = String.format(Locale.ROOT,
            "{\"schemaVersion\":\"1.0\",\"syntheticOnly\":true,\"seed\":20260816," +
                "\"runs\":[{\"rows\":10000,\"durationMs\":%d,\"accepted\":true}," +
                "{\"rows\":100000,\"durationMs\":%d,\"accepted\":true}]}%n",
            duration10k, duration100k);
        Path output = Path.of("target", "gate1-capacity.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private CatalogImportRow valid(int index) {
        return new CatalogImportRow(index, "SPU-" + index, "SKU-" + index, "合成商品 " + index,
            "CAT", "BRAND", "STANDARD", "PCS", "1", 1L, 1L, List.of("BC" + index));
    }

    private Iterable<CatalogImportRow> rows(int count, boolean invalid) {
        return () -> new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @Override
            public CatalogImportRow next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                index++;
                if (invalid) {
                    return new CatalogImportRow(index, "SPU-" + index, "SKU-" + index, " ", "CAT", "BRAND",
                        "STANDARD", "PCS", "1", 1L, 1L, List.of("BC" + index));
                }
                return valid(index);
            }
        };
    }
}
