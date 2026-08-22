package com.jingshanghui.pos.onboarding.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 门店开通 REST 输入；tenantId、状态、检查结论和 Owner 事实均禁止由客户端提交。 */
public final class OnboardingRequests {
    private OnboardingRequests() {
    }

    /** @param sourceStoreId 空表示仅应用行业模板，不复制来源门店选择。 */
    public record Create(@Positive Long sourceStoreId,
                         @NotNull @Positive Long targetStoreId,
                         @NotNull @Positive Long templateId,
                         @NotNull @Positive Long templateVersionId) {
    }

    /** 需要独立审计原因的审批、开店或取消请求。 */
    public record Reason(@NotBlank @Size(min = 2, max = 200) String reason) {
    }
}
