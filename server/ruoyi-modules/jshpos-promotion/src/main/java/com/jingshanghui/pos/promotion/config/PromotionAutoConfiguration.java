package com.jingshanghui.pos.promotion.config;

import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine;
import com.jingshanghui.pos.promotion.domain.TransactionAllocationEngine;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** Gate 5A 独立模块入口，不修改 RuoYi 系统模块。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.promotion")
@MapperScan("com.jingshanghui.pos.promotion.infrastructure.persistence.mapper")
public class PromotionAutoConfiguration {
    /** 注册无状态促销引擎。 */
    @Bean public PromotionEngine promotionEngine() { return new PromotionEngine(); }
    /** 注册无状态人工优惠引擎。 */
    @Bean public ManualAdjustmentEngine manualAdjustmentEngine() { return new ManualAdjustmentEngine(); }
    /** 注册无状态成交分摊与退款恢复引擎。 */
    @Bean public TransactionAllocationEngine transactionAllocationEngine() { return new TransactionAllocationEngine(); }
}
