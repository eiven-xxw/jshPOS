package com.jingshanghui.pos.procurement.application.port;

/** 开业迁移创建供应商的 Owner 受控端口；tenant_id 只来自可信上下文。 */
public interface BusinessMigrationSupplierPort {
    SupplierMigrationResult importSupplier(SupplierMigrationCommand command);

    /**
     * 预检后冻结的供应商行。
     * @param supplierId Procurement Owner 使用的稳定供应商 ULID
     * @param code 租户内供应商编码
     * @param name 供应商名称
     * @param rowSha256 冻结迁移行 SHA-256
     * @param correlationId 全链路关联标识
     */
    record SupplierMigrationCommand(String supplierId, String code, String name,
                                    String rowSha256, String correlationId) {
    }

    /**
     * Procurement Owner 返回的稳定供应商结果。
     * @param supplierId 稳定供应商 ULID
     * @param code 冻结供应商编码
     * @param state 供应商状态
     * @param rowSha256 已接收迁移行摘要
     * @param replay 是否返回既有幂等结果
     */
    record SupplierMigrationResult(String supplierId, String code, String state,
                                   String rowSha256, boolean replay) {
    }
}
