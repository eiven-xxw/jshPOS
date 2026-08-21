package com.jingshanghui.pos.catalog.application.port;

import com.jingshanghui.pos.catalog.application.price.PriceResolution.ResolvedPrice;

import java.time.Instant;

/** Order Owner 校验成交价时使用的 Catalog 稳定只读端口。 */
public interface OrderPriceResolutionPort {
    ResolvedPrice resolve(Long skuId, Long unitId, Long storeId, Instant at);
}
