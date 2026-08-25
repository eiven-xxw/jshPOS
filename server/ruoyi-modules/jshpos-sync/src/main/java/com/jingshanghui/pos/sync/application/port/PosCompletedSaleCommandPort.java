package com.jingshanghui.pos.sync.application.port;

import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;

/**
 * POS 已完成订单进入 Inventory Owner 的窄端口。
 *
 * <p>Sync 只传递已经过可信终端、Inbox、摘要与顺序校验的原事件；销售仓、
 * 批次跟踪和不可变库存效果仍由 Inventory Owner 依据权威订单与已发布策略决定。</p>
 */
public interface PosCompletedSaleCommandPort {

    /** 使用原 eventId 和 correlationId 路由一次已完成销售，禁止生成替代命令。 */
    void apply(DeviceContext context, EventEnvelope event);
}
