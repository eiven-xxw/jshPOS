package com.jingshanghui.pos.order.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.PromotedLine;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.SubmitPromotedCashOrder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Java 与 Dart 必须逐向量生成同一订单快照摘要。 */
class PromotedOrderSnapshotGoldenVectorTest {

    @Test
    void matchesEverySharedSettlementOrderVector() throws Exception {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("contracts"))) path = path.getParent();
        path = path == null ? Path.of("missing") : path.resolve(Path.of("contracts", "t2", "gate5b",
            "test-vectors", "settlement-order-vectors-v1.json"));
        JsonNode root = new ObjectMapper().readTree(path.toFile());
        for (JsonNode scenario : root.path("scenarios")) {
            JsonNode command = scenario.path("command");
            JsonNode binding = scenario.path("binding");
            List<PromotedLine> lines = new ArrayList<>();
            for (JsonNode line : scenario.path("lines")) {
                Map<String, Long> allocations = new LinkedHashMap<>();
                line.path("sourceAllocations").fields().forEachRemaining(entry ->
                    allocations.put(entry.getKey(), entry.getValue().asLong()));
                lines.add(new PromotedLine(line.path("lineId").asText(), line.path("lineNo").asInt(),
                    line.path("skuId").asLong(), line.path("skuCode").asText(), null,
                    line.path("productName").asText(), line.path("unitId").asLong(),
                    line.path("unitCode").asText(), line.path("quantity").asText(),
                    line.path("unitPriceMinor").asLong(),
                    line.path("unitPriceMinor").asLong() * Long.parseLong(line.path("quantity").asText()),
                    line.path("discountAmountMinor").asLong(), line.path("surchargeAmountMinor").asLong(),
                    scenario.path("expected").path("receivableAmountMinor").asLong(),
                    line.path("priceSource").asText(), allocations));
            }
            SubmitPromotedCashOrder value = new SubmitPromotedCashOrder(command.path("commandId").asText(),
                command.path("idempotencyKey").asText(), command.path("orderId").asText(),
                command.path("localOrderNo").asText(), binding.path("storeId").asLong(),
                binding.path("terminalId").asText(), command.path("shiftId").asText(),
                binding.path("cashierId").asText(), LocalDate.parse(command.path("businessDate").asText()),
                binding.path("storeTimezone").asText(), command.path("catalogVersion").asLong(),
                command.path("priceVersion").asLong(), command.path("industryTemplateVersion").asText(),
                command.path("promotionSnapshotId").asText(), command.path("promotionSnapshotSha256").asText(),
                command.path("quoteFingerprint").asText(), command.path("settlementFingerprint").asText(),
                command.path("packageVersion").asLong(), scenario.path("expected").path("orderSnapshotSha256").asText(),
                List.of(), scenario.path("expected").path("grossAmountMinor").asLong(),
                scenario.path("expected").path("discountAmountMinor").asLong(),
                scenario.path("expected").path("surchargeAmountMinor").asLong(),
                scenario.path("expected").path("receivableAmountMinor").asLong(),
                command.path("tenderedAmountMinor").asLong(), lines, Instant.parse(command.path("occurredAt").asText()));
            assertThat(PromotedOrderSnapshotCodec.encode(value, binding.path("cashierId").asLong()).sha256())
                .as(scenario.path("id").asText())
                .isEqualTo(scenario.path("expected").path("orderSnapshotSha256").asText());
        }
    }
}
