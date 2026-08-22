package com.jingshanghui.pos.onboarding.application.port;

import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CheckFact;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerApplyResult;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerOpenResult;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.OwnerSnapshot;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.PlanRecord;

import java.util.List;

/** Onboarding Owner 与各事实 Owner 之间唯一的应用端口。 */
public interface OnboardingOwnerGateway {
    OwnerSnapshot capture(Long sourceStoreId, Long targetStoreId, Long templateId, Long templateVersionId);

    OwnerApplyResult apply(PlanRecord plan);

    List<CheckFact> checks(PlanRecord plan, int runNo);

    OwnerOpenResult open(PlanRecord plan, String reason);
}
