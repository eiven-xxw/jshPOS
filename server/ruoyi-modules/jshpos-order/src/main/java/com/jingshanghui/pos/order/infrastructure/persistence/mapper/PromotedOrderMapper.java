package com.jingshanghui.pos.order.infrastructure.persistence.mapper;

import com.jingshanghui.pos.order.application.port.PromotedOrderRepository.BindingWrite;
import com.jingshanghui.pos.order.application.port.PromotedOrderRepository.LineWrite;
import com.jingshanghui.pos.order.application.port.PromotedOrderRepository.OrderWrite;

/** ORD-003 复杂不可变事实 Mapper；SQL 只允许在对应 XML 中维护。 */
public interface PromotedOrderMapper {

    int insertOrder(OrderWrite value);

    int insertLine(LineWrite value);

    int insertPromotionBinding(BindingWrite value);
}
