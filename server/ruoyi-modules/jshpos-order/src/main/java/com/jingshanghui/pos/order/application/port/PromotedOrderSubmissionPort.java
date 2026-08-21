package com.jingshanghui.pos.order.application.port;

import com.jingshanghui.pos.order.application.model.OrderViews.CashOrderResult;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.SubmitPromotedCashOrder;

/** Sync Owner 消费正式成交事件时调用的 Order 幂等提交端口。 */
public interface PromotedOrderSubmissionPort {
    CashOrderResult submit(SubmitPromotedCashOrder command);
}
