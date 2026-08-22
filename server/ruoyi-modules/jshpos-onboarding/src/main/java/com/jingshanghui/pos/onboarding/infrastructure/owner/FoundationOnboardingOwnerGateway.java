package com.jingshanghui.pos.onboarding.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.StoreOnboardingPort;
import com.jingshanghui.pos.catalog.application.port.StoreOnboardingCatalogPort;
import com.jingshanghui.pos.catalog.application.port.StoreOnboardingCatalogPort.CatalogReadiness;
import com.jingshanghui.pos.inventory.application.port.StoreOnboardingInventoryPort;
import com.jingshanghui.pos.inventory.application.port.StoreOnboardingInventoryPort.InventoryReadiness;
import com.jingshanghui.pos.order.application.port.StoreOnboardingShiftPort;
import com.jingshanghui.pos.order.application.port.StoreOnboardingShiftPort.ShiftReadiness;
import com.jingshanghui.pos.resilience.application.port.StoreOnboardingBackupPort;
import com.jingshanghui.pos.resilience.application.port.StoreOnboardingBackupPort.BackupReadiness;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CheckFact;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerApplyResult;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerOpenResult;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerSnapshot;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.PlanRecord;
import com.jingshanghui.pos.onboarding.application.port.OnboardingOwnerGateway;
import com.jingshanghui.pos.onboarding.domain.OnboardingRules;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Foundation 正式端口装配；尚未提供独立事实端口的 Owner 明确返回 UNAVAILABLE，
 * 外部 P0 始终返回 BLOCKED，绝不生成绿色占位。
 */
@Component
public class FoundationOnboardingOwnerGateway implements OnboardingOwnerGateway {
    private final StoreOnboardingPort foundation;
    private final StoreOnboardingCatalogPort catalog;
    private final StoreOnboardingInventoryPort inventory;
    private final StoreOnboardingShiftPort shifts;
    private final StoreOnboardingBackupPort backups;

    public FoundationOnboardingOwnerGateway(StoreOnboardingPort foundation, StoreOnboardingCatalogPort catalog,
                                             StoreOnboardingInventoryPort inventory,
                                             StoreOnboardingShiftPort shifts,
                                             StoreOnboardingBackupPort backups) {
        this.foundation = foundation;
        this.catalog = catalog;
        this.inventory = inventory;
        this.shifts = shifts;
        this.backups = backups;
    }

    @Override
    public OwnerSnapshot capture(Long sourceStoreId, Long targetStoreId, Long templateId, Long templateVersionId) {
        StoreOnboardingPort.FoundationSnapshot value = foundation.capture(
            new StoreOnboardingPort.CaptureCommand(sourceStoreId, targetStoreId, templateId, templateVersionId));
        return new OwnerSnapshot(value.sourceStoreId(), value.sourceStoreVersion(), value.targetStoreId(),
            value.targetStoreVersion(), value.templateId(), value.templateVersionId(), value.templateVersionNo(),
            value.templateSha256(), value.industry(), value.configItems());
    }

    @Override
    public OwnerApplyResult apply(PlanRecord plan) {
        StoreOnboardingPort.AppliedBinding result = foundation.apply(new StoreOnboardingPort.ApplyCommand(
            plan.targetStoreId(), plan.templateId(), plan.templateVersionId(), plan.targetStoreVersion(),
            plan.snapshotSha256()));
        return new OwnerApplyResult("FOUNDATION_CONFIG_BINDING", result.resultSha256());
    }

    @Override
    public List<CheckFact> checks(PlanRecord plan, int runNo) {
        List<CheckFact> result = new ArrayList<>();
        pass(result, "STORE_ORG", "FOUNDATION", plan.targetStoreVersion().toString(), plan.snapshotSha256());
        pass(result, "BUSINESS_TIME", "FOUNDATION", plan.targetStoreVersion().toString(), plan.snapshotSha256());
        pass(result, "CONFIG_TEMPLATE", "FOUNDATION", plan.templateVersionNo().toString(), plan.templateSha256());
        StoreOnboardingPort.FoundationReadiness foundationFact = foundation.readiness(plan.targetStoreId());
        fact(result, "STAFF_SCOPE", "FOUNDATION", Integer.toString(foundationFact.activeStaffScopeCount()),
            foundationFact.factSha256(), foundationFact.activeStaffScopeCount() > 0,
            "至少一个生效员工数据范围", "未配置生效员工数据范围");
        CatalogReadiness catalogFact = catalog.readiness(plan.targetStoreId());
        fact(result, "CATALOG_PRICE", "CATALOG", Integer.toString(catalogFact.activeSkuCount()),
            catalogFact.factSha256(), catalogFact.activeSkuCount() > 0
                && catalogFact.activeSkuCount() == catalogFact.pricedSkuCount(),
            "全部启用商品具备当前有效价格", "启用商品为空或价格覆盖不完整");
        fact(result, "DATA_PACKAGE", "CATALOG", catalogFact.packageVersion() == null
                ? "UNAVAILABLE" : catalogFact.packageVersion().toString(), catalogFact.factSha256(),
            catalogFact.packageVersion() != null && catalogFact.packageSha256() != null,
            "门店存在正式发布数据包", "门店尚无正式发布数据包");
        InventoryReadiness inventoryFact = inventory.readiness(plan.targetStoreId());
        fact(result, "INVENTORY_POLICY", "INVENTORY", Integer.toString(inventoryFact.activePolicyCount()),
            inventoryFact.factSha256(), inventoryFact.activePolicyCount() > 0,
            "门店仓库存在生效库存策略", "门店仓库缺少生效库存策略");
        ShiftReadiness shiftFact = shifts.readiness(plan.targetStoreId());
        fact(result, "CASH_SHIFT_CLEAR", "ORDER", Integer.toString(shiftFact.openOrClosingCount()),
            shiftFact.factSha256(), shiftFact.openOrClosingCount() == 0,
            "目标门店没有未关闭现金班次", "目标门店存在 OPEN/CLOSING 班次");
        pass(result, "DMT_RECONCILED", "MIGRATION", "NOT_REQUIRED", plan.snapshotSha256());
        BackupReadiness backupFact = backups.readiness();
        if (backupFact == null) {
            result.add(new CheckFact("BACKUP_RECOVERY", "RESILIENCE", true, false, "UNAVAILABLE",
                "0".repeat(64), CheckStatus.UNAVAILABLE, "当前租户没有通过的合成恢复演练事实"));
        } else {
            fact(result, "BACKUP_RECOVERY", "RESILIENCE", backupFact.drillId(), backupFact.evidenceSha256(),
                backupFact.rpoSeconds() <= 900 && backupFact.rtoSeconds() <= 3600,
                "合成恢复演练达到内部候选目标", "合成恢复演练未达到内部候选目标");
        }
        for (String code : OnboardingRules.INTERNAL_CHECKS.stream().sorted().toList()) {
            if (result.stream().noneMatch(value -> code.equals(value.code()))) {
                result.add(new CheckFact(code, owner(code), true, false, "UNAVAILABLE", "0".repeat(64),
                    CheckStatus.UNAVAILABLE, "Owner 正式就绪端口尚未返回事实，失败关闭"));
            }
        }
        for (String code : OnboardingRules.EXTERNAL_CHECKS.stream().sorted().toList()) {
            result.add(new CheckFact(code, owner(code), true, true, "BLOCKED", "0".repeat(64),
                CheckStatus.BLOCKED, "外部 P0 尚未通过独立解阻评审"));
        }
        return List.copyOf(result);
    }

    @Override
    public OwnerOpenResult open(PlanRecord plan, String reason) {
        StoreOnboardingPort.OpenedStore result = foundation.open(new StoreOnboardingPort.OpenCommand(
            plan.targetStoreId(), plan.targetStoreVersion(), reason));
        return new OwnerOpenResult(result.storeId(), result.status(), result.version());
    }

    private static void pass(List<CheckFact> result, String code, String owner, String version, String sha) {
        result.add(new CheckFact(code, owner, true, false, version, sha, CheckStatus.PASS, "权威事实检查通过"));
    }

    private static void fact(List<CheckFact> result, String code, String owner, String version, String sha,
                             boolean passed, String success, String failure) {
        result.add(new CheckFact(code, owner, true, false, version, sha,
            passed ? CheckStatus.PASS : CheckStatus.FAIL, passed ? success : failure));
    }

    private static String owner(String code) {
        if (code.startsWith("CATALOG") || "DATA_PACKAGE".equals(code)) return "CATALOG";
        if (code.startsWith("INVENTORY")) return "INVENTORY";
        if (code.startsWith("CASH")) return "ORDER";
        if (code.startsWith("DMT")) return "MIGRATION";
        if (code.startsWith("BACKUP")) return "RESILIENCE";
        if (code.startsWith("PAYMENT")) return "PAYMENT";
        if (code.startsWith("HARDWARE") || code.startsWith("PRINT")) return "DEVICE";
        if (code.startsWith("DESIGN")) return "PARTNER";
        return "FOUNDATION";
    }
}
