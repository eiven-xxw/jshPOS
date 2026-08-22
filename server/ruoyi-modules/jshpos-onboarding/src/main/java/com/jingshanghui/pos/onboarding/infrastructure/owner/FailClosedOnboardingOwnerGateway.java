package com.jingshanghui.pos.onboarding.infrastructure.owner;

import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CheckFact;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerApplyResult;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerOpenResult;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerSnapshot;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.PlanRecord;
import com.jingshanghui.pos.onboarding.application.port.OnboardingOwnerGateway;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckStatus;
import org.dromara.common.core.exception.ServiceException;

import java.util.List;

/** 缺少正式 Owner 装配时禁止创建、应用或开店，不能用默认成功掩盖装配缺失。 */
public final class FailClosedOnboardingOwnerGateway implements OnboardingOwnerGateway {
    @Override public OwnerSnapshot capture(Long sourceStoreId, Long targetStoreId, Long templateId, Long versionId) {
        throw unavailable();
    }
    @Override public OwnerApplyResult apply(PlanRecord plan) { throw unavailable(); }
    @Override public OwnerOpenResult open(PlanRecord plan, String reason) { throw unavailable(); }

    @Override
    public List<CheckFact> checks(PlanRecord plan, int runNo) {
        return List.of(new CheckFact("STORE_ORG", "FOUNDATION", true, false, "UNAVAILABLE",
            "0".repeat(64), CheckStatus.UNAVAILABLE, "正式 Owner 装配不可用"));
    }

    private static ServiceException unavailable() {
        return new ServiceException("ONB-OWNER-001: 正式 Owner 网关未装配，操作失败关闭", 503);
    }
}
