package com.jingshanghui.pos.integration.application;

import com.jingshanghui.pos.catalog.application.service.CatalogApplicationService;
import com.jingshanghui.pos.costing.application.service.CostingService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.inventory.application.service.InventoryLedgerService;
import com.jingshanghui.pos.member.application.service.MemberProfileService;
import com.jingshanghui.pos.migration.application.service.BusinessMigrationService;
import com.jingshanghui.pos.order.application.service.PromotedCashOrderService;
import com.jingshanghui.pos.operations.application.service.DailyCloseService;
import com.jingshanghui.pos.payment.application.service.PaymentCoreService;
import com.jingshanghui.pos.procurement.application.service.ProcurementService;
import com.jingshanghui.pos.promotion.application.service.PromotionTransactionService;
import com.jingshanghui.pos.release.application.service.ReleaseGovernanceService;
import com.jingshanghui.pos.reporting.application.service.ReportingProjectionService;
import com.jingshanghui.pos.resilience.application.service.BackupRecoveryService;
import com.jingshanghui.pos.returns.application.service.ReturnOrchestrationService;
import com.jingshanghui.pos.sync.application.service.PosSyncService;
import com.jingshanghui.pos.sync.application.service.TerminalRegistryService;
import com.jingshanghui.pos.transfer.application.service.TransferService;

import java.util.List;

/**
 * 商业 V1 模块化单体的生产装配契约。
 *
 * <p>这里只声明每个 Owner 必须提供的正式应用能力，不执行领域计算，也不允许跨 Owner 写表。</p>
 */
public final class CommercialV1AssemblyContract {
    private CommercialV1AssemblyContract() {
    }

    /** 需要在同一运行时唯一装配的 Owner 能力。 */
    public static List<OwnerCapability> requiredCapabilities() {
        return List.of(
            capability("foundation", "组织门店与可信上下文", StoreService.class),
            capability("catalog", "商品价格与数据包", CatalogApplicationService.class),
            capability("order", "现金订单与成交快照", PromotedCashOrderService.class),
            capability("sync", "POS Inbox/Outbox同步", PosSyncService.class),
            capability("terminal", "可信终端登记", TerminalRegistryService.class),
            capability("payment", "Provider无关支付核心", PaymentCoreService.class),
            capability("inventory", "不可变库存流水", InventoryLedgerService.class),
            capability("procurement", "采购收退货", ProcurementService.class),
            capability("costing", "不可变成本流水", CostingService.class),
            capability("transfer", "仓间调拨", TransferService.class),
            capability("promotion", "成交优惠快照", PromotionTransactionService.class),
            capability("returns", "原单退货退款编排", ReturnOrchestrationService.class),
            capability("member", "会员身份与隐私", MemberProfileService.class),
            capability("migration", "开业资料预检、迁移与对账", BusinessMigrationService.class),
            capability("reporting", "经营投影与对账", ReportingProjectionService.class),
            capability("operations", "门店业务日日结与只追加签署", DailyCloseService.class),
            capability("resilience", "备份恢复", BackupRecoveryService.class),
            capability("release", "版本发布治理", ReleaseGovernanceService.class)
        );
    }

    private static OwnerCapability capability(String owner, String description, Class<?> type) {
        return new OwnerCapability(owner, description, type);
    }

    /** Owner 名称、正式能力说明和必须唯一存在的 Spring 类型。 */
    public record OwnerCapability(String owner, String description, Class<?> beanType) {
        public OwnerCapability {
            if (owner == null || owner.isBlank() || description == null || description.isBlank() || beanType == null) {
                throw new IllegalArgumentException("CORE-ASSEMBLY-000: Owner装配契约不完整");
            }
        }
    }
}
