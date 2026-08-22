package com.jingshanghui.pos.catalog.application.port;

import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Inventory 与数据包读取当前批次策略的 Catalog 正式只读端口。 */
public interface LotPolicyReadPort {
    /** 当前无策略时返回空，仅用于 Owner 能力路由，不降级已启用商品的失败关闭。 */
    Optional<PolicyView> findEffective(Long storeId, Long skuId, Instant effectiveAt);

    PolicyView requireEffective(Long storeId, Long skuId, Instant effectiveAt);

    /** 数据包读取门店当前所有 SKU 的唯一生效策略；结果按 skuId 排序。 */
    List<PolicyView> listEffective(Long storeId, Instant effectiveAt);
}
