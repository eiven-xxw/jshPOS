package com.jingshanghui.pos.inventory.application.model;

import java.time.Instant;

/** 进入库存应用层的具名命令；均不允许客户端携带 tenant_id、SKU 或数量。 */
public final class InventoryCommands {

    private InventoryCommands() {
    }

    public record ApplySale(String eventId, String orderId, String warehouseId, String correlationId) {
    }

    public record ApplyReturn(String eventId, String refundId, String warehouseId, String correlationId) {
    }

    public record PublishPolicy(String policyVersionId, Long storeId, String warehouseId,
                                String negativeStockMode, Instant effectiveFrom, String correlationId) {
    }

    public record RebuildBalance(String warehouseId, Long skuId, String correlationId) {
    }
}
