package com.jingshanghui.pos.reporting.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.Map;
import java.util.Set;

/** 报表导出、差异和投影状态机；非法迁移失败关闭。 */
public final class ReportStates {
    private static final Map<String, Set<String>> EXPORT_TRANSITIONS = Map.of(
        "REQUESTED", Set.of("APPROVED", "REJECTED", "GENERATING"),
        "APPROVED", Set.of("GENERATING", "REJECTED"),
        "GENERATING", Set.of("READY", "FAILED"),
        "READY", Set.of("EXPIRED"),
        "FAILED", Set.of("GENERATING", "EXPIRED"),
        "REJECTED", Set.of("EXPIRED"),
        "EXPIRED", Set.of()
    );
    private static final Map<String, Set<String>> DIFFERENCE_TRANSITIONS = Map.of(
        "OPEN", Set.of("ACKNOWLEDGED", "RESOLVED", "IGNORED"),
        "ACKNOWLEDGED", Set.of("RESOLVED", "IGNORED"),
        "RESOLVED", Set.of("OPEN"),
        "IGNORED", Set.of("OPEN")
    );

    private ReportStates() {
    }

    public static String transitionExport(String from, String to, boolean approvalRequired) {
        if ("REQUESTED".equals(from) && "GENERATING".equals(to) && approvalRequired) {
            throw conflict("RPT-G5D-020", "高风险导出必须先审批");
        }
        if (!EXPORT_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw conflict("RPT-G5D-021", "非法导出状态迁移");
        }
        return to;
    }

    public static String transitionDifference(String from, String to) {
        if (!DIFFERENCE_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw conflict("RPT-G5D-022", "非法差异状态迁移");
        }
        return to;
    }

    public static String projectionStatus(long contiguousSequence, long maximumSeenSequence) {
        if (contiguousSequence < 0 || maximumSeenSequence < contiguousSequence) {
            throw conflict("RPT-G5D-023", "检查点序号非法");
        }
        return contiguousSequence == maximumSeenSequence ? "CURRENT" : "INCOMPLETE";
    }

    private static ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
}
