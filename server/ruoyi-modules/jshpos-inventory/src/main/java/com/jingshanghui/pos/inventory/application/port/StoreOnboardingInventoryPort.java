package com.jingshanghui.pos.inventory.application.port;

/** Inventory Owner 为开店检查提供的只读库存策略事实。 */
public interface StoreOnboardingInventoryPort {
    InventoryReadiness readiness(Long storeId);

    record InventoryReadiness(Long storeId, int activePolicyCount, String factSha256) {
    }
}
