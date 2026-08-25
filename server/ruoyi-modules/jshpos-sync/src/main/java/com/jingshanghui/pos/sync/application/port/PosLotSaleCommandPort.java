package com.jingshanghui.pos.sync.application.port;

import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;

/**
 * POS 批次销售冻结事实进入 Inventory Owner 的窄端口。
 *
 * <p>Sync 只负责可信设备上下文、Inbox 与 ACK；库存总账、FEFO 和批次分配仍由
 * Inventory Owner 决定，客户端批次快照只能用于一致性校验。</p>
 */
public interface PosLotSaleCommandPort {
    void apply(DeviceContext context, EventEnvelope event);
}
