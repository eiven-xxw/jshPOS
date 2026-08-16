package com.jingshanghui.pos.costing.application.model;

import java.time.Instant;

/** 成本策略与投影治理命令；成本事实本身不从 REST 接收。 */
public final class CostingCommands {

    private CostingCommands() {
    }

    public record PublishPolicy(String policyVersionId, Long storeId, String warehouseId,
                                Instant effectiveFrom, String correlationId) {
    }

    public record RebuildBalance(String rebuildId, String warehouseId, Long skuId,
                                 String correlationId) {
    }
}
