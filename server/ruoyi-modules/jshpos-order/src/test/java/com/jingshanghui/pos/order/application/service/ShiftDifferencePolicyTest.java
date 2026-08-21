package com.jingshanghui.pos.order.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.port.PublishedConfigReadPort;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 交班阈值只通过 Foundation 只读端口获取，缺失或损坏时保持失败关闭。 */
class ShiftDifferencePolicyTest {

    private final PublishedConfigReadPort configs = mock(PublishedConfigReadPort.class);
    private final ShiftDifferencePolicy policy = new ShiftDifferencePolicy(configs, new ObjectMapper());

    @Test
    void usesZeroWhenNoPublishedPolicyExists() {
        when(configs.find(ShiftDifferencePolicy.TEMPLATE_CODE, 1101L)).thenReturn(Optional.empty());
        assertThat(policy.approvalThresholdMinor(1101L)).isZero();
    }

    @Test
    void readsExactNonNegativeMinorAmount() {
        when(configs.find(ShiftDifferencePolicy.TEMPLATE_CODE, 1101L)).thenReturn(Optional.of(
            new PublishedConfigReadPort.PublishedConfig("TENANT_A", 1L, 2L, 1,
                "{\"cashDifferenceApprovalMinor\":500}", "a".repeat(64))));
        assertThat(policy.approvalThresholdMinor(1101L)).isEqualTo(500L);
    }

    @Test
    void rejectsNegativeOrNonIntegralThreshold() {
        when(configs.find(ShiftDifferencePolicy.TEMPLATE_CODE, 1101L)).thenReturn(Optional.of(
            new PublishedConfigReadPort.PublishedConfig("TENANT_A", 1L, 2L, 1,
                "{\"cashDifferenceApprovalMinor\":-1}", "a".repeat(64))));
        assertThatThrownBy(() -> policy.approvalThresholdMinor(1101L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("SHIFT_POLICY_INVALID");
    }
}
