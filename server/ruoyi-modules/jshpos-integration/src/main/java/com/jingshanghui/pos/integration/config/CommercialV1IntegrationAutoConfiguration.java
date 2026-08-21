package com.jingshanghui.pos.integration.config;

import com.jingshanghui.pos.integration.application.CommercialV1AssemblyVerifier;
import com.jingshanghui.pos.integration.application.ExternalBoundaryRegistry;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Bean;

/**
 * 商业 V1 模块化单体组合根。
 *
 * <p>组合根只承担启动期装配校验；所有领域事实仍由各 Owner 自己写入。</p>
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
    "com.jingshanghui.pos.foundation.config.FoundationAutoConfiguration",
    "com.jingshanghui.pos.catalog.config.CatalogAutoConfiguration",
    "com.jingshanghui.pos.order.config.OrderAutoConfiguration",
    "com.jingshanghui.pos.sync.config.SyncAutoConfiguration",
    "com.jingshanghui.pos.payment.config.PaymentAutoConfiguration",
    "com.jingshanghui.pos.inventory.config.InventoryAutoConfiguration",
    "com.jingshanghui.pos.procurement.config.ProcurementAutoConfiguration",
    "com.jingshanghui.pos.costing.config.CostingAutoConfiguration",
    "com.jingshanghui.pos.transfer.config.TransferAutoConfiguration",
    "com.jingshanghui.pos.promotion.config.PromotionAutoConfiguration",
    "com.jingshanghui.pos.returns.config.ReturnsAutoConfiguration",
    "com.jingshanghui.pos.member.config.MemberAutoConfiguration",
    "com.jingshanghui.pos.reporting.config.ReportingAutoConfiguration",
    "com.jingshanghui.pos.resilience.config.ResilienceAutoConfiguration",
    "com.jingshanghui.pos.release.config.ReleaseAutoConfiguration"
})
public class CommercialV1IntegrationAutoConfiguration {
    @Bean
    public ExternalBoundaryRegistry externalBoundaryRegistry() {
        return new ExternalBoundaryRegistry();
    }

    @Bean
    public CommercialV1AssemblyVerifier commercialV1AssemblyVerifier(
        ConfigurableListableBeanFactory beanFactory,
        ExternalBoundaryRegistry externalBoundaries) {
        return new CommercialV1AssemblyVerifier(beanFactory, externalBoundaries);
    }
}
