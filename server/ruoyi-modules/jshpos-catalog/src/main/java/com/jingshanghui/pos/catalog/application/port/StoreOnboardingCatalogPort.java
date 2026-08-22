package com.jingshanghui.pos.catalog.application.port;

/** Catalog Owner 为开店检查提供的只读事实端口。 */
public interface StoreOnboardingCatalogPort {
    CatalogReadiness readiness(Long storeId);

    /** activeSkuCount 必须与 pricedSkuCount 相等，且门店存在已发布数据包。 */
    record CatalogReadiness(Long storeId, int activeSkuCount, int pricedSkuCount, Long packageVersion,
                            String packageSha256, String factSha256) {
    }
}
