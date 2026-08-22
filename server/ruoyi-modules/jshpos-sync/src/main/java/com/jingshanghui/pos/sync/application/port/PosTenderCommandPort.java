package com.jingshanghui.pos.sync.application.port;

import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;

/** PAY-004 同步扩展端口；Sync 只路由可信设备事件，不拥有支付计划。 */
public interface PosTenderCommandPort {

    /**
     * 路由一个已通过 Sync Inbox 验证的 POS 支付计划事件。
     *
     * @param context 服务端可信设备、租户、门店上下文
     * @param event 原 eventId、payload 与摘要不可变的同步事件
     */
    void apply(DeviceContext context, EventEnvelope event);
}
