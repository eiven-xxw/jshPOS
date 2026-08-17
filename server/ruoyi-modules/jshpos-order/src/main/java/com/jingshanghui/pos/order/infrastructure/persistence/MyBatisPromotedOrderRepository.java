package com.jingshanghui.pos.order.infrastructure.persistence;

import com.jingshanghui.pos.order.application.port.PromotedOrderRepository;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.PromotedOrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;

/** ORD-003 XML-only 仓储适配器，禁止向应用层暴露通用更新与删除。 */
@Repository
@RequiredArgsConstructor
public class MyBatisPromotedOrderRepository implements PromotedOrderRepository {

    private final PromotedOrderMapper mapper;

    @Override
    public void insertOrder(OrderWrite value) {
        requireOne(mapper.insertOrder(value));
    }

    @Override
    public void insertLine(LineWrite value) {
        requireOne(mapper.insertLine(value));
    }

    @Override
    public void insertPromotionBinding(BindingWrite value) {
        requireOne(mapper.insertPromotionBinding(value));
    }

    private void requireOne(int count) {
        if (count != 1) {
            throw new ServiceException("ORD-STORE-003: 含促销订单事实写入失败", 409);
        }
    }
}
