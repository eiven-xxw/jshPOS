package com.jingshanghui.pos.reporting.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** 导出、差异与检查点状态机固定回归。 */
class ReportStatesTest {
    @Test void acceptsAllowedExportTransitions() {
        assertThat(ReportStates.transitionExport("REQUESTED", "GENERATING", false)).isEqualTo("GENERATING");
        assertThat(ReportStates.transitionExport("REQUESTED", "APPROVED", true)).isEqualTo("APPROVED");
        assertThat(ReportStates.transitionExport("APPROVED", "GENERATING", true)).isEqualTo("GENERATING");
        assertThat(ReportStates.transitionExport("GENERATING", "READY", true)).isEqualTo("READY");
        assertThat(ReportStates.transitionExport("READY", "EXPIRED", true)).isEqualTo("EXPIRED");
        assertThat(ReportStates.transitionExport("FAILED", "GENERATING", false)).isEqualTo("GENERATING");
    }
    @Test void rejectsApprovalBypassAndIllegalTransition() {
        assertThatThrownBy(() -> ReportStates.transitionExport("REQUESTED", "GENERATING", true))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportStates.transitionExport("READY", "APPROVED", false))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportStates.transitionExport("UNKNOWN", "READY", false))
            .isInstanceOf(ServiceException.class);
    }
    @Test void controlsDifferenceAndProjectionStatus() {
        assertThat(ReportStates.transitionDifference("OPEN", "ACKNOWLEDGED")).isEqualTo("ACKNOWLEDGED");
        assertThat(ReportStates.transitionDifference("ACKNOWLEDGED", "RESOLVED")).isEqualTo("RESOLVED");
        assertThat(ReportStates.transitionDifference("RESOLVED", "OPEN")).isEqualTo("OPEN");
        assertThatThrownBy(() -> ReportStates.transitionDifference("OPEN", "OPEN"))
            .isInstanceOf(ServiceException.class);
        assertThat(ReportStates.projectionStatus(2, 2)).isEqualTo("CURRENT");
        assertThat(ReportStates.projectionStatus(1, 3)).isEqualTo("INCOMPLETE");
        assertThatThrownBy(() -> ReportStates.projectionStatus(3, 2)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportStates.projectionStatus(-1, 0)).isInstanceOf(ServiceException.class);
    }
}
