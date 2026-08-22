package com.jingshanghui.pos.foundation.application.port;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Foundation Owner 向门店开通编排暴露的受控端口。
 *
 * <p>端口只返回白名单配置快照并执行具名绑定/开店动作，调用方不能获得 Mapper 或
 * 通用更新能力。</p>
 */
public interface StoreOnboardingPort {
    FoundationSnapshot capture(CaptureCommand command);

    FoundationReadiness readiness(Long targetStoreId);

    AppliedBinding apply(ApplyCommand command);

    OpenedStore open(OpenCommand command);

    /** @param sourceStoreId 空表示仅按行业模板开店。 */
    record CaptureCommand(Long sourceStoreId, Long targetStoreId, Long templateId, Long templateVersionId) {
    }

    /**
     * @param industry 三业态模板代码
     * @param configItems 已过滤的白名单配置
     */
    record FoundationSnapshot(Long sourceStoreId, Integer sourceStoreVersion, Long targetStoreId,
                              Integer targetStoreVersion, Long templateId, Long templateVersionId,
                              Integer templateVersionNo, String templateSha256, String industry,
                              Map<String, Object> configItems) {
        public FoundationSnapshot {
            configItems = configItems == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configItems));
        }
    }

    record ApplyCommand(Long targetStoreId, Long templateId, Long templateVersionId,
                        Integer expectedTargetVersion, String expectedSnapshotSha256) {
    }

    record AppliedBinding(Long bindingId, Long targetStoreId, Long templateVersionId,
                          Integer bindingVersion, String resultSha256) {
    }

    /** Foundation 权威检查事实，不包含员工身份或权限明细。 */
    record FoundationReadiness(Long targetStoreId, int activeStaffScopeCount, String factSha256) {
    }

    record OpenCommand(Long targetStoreId, Integer expectedStoreVersion, String reason) {
    }

    record OpenedStore(Long storeId, String status, Integer version) {
    }
}
