package com.jingshanghui.pos.catalog.application.port;

/**
 * Catalog 发布批次能力关闭版本前调用的 Inventory 受控守卫。
 *
 * <p>Catalog 不读取库存私有表；Inventory 只返回能否关闭，不泄露余额事实。</p>
 */
public interface LotPolicyTransitionGuardPort {
    /** 存在尚未耗尽的批次事实时必须失败关闭，防止切回无批次路径造成账实分叉。 */
    void requireCanDisable(Long storeId, Long skuId);
}
