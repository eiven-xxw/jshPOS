package com.jingshanghui.pos.order.application.port;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ORD-004 POS处置同步端口；调用者只能提交意图，租户、门店、终端和员工由服务端可信上下文复核。
 */
public interface OrderDispositionSubmissionPort {

    /** 保存成交前取消墓碑或成交后只追加处置路由，不得越权修改其他Owner事实。 */
    void record(String sourceEventId, String dispositionId, String orderId, Long storeId,
                String terminalId, String shiftId, Long actorUserId, LocalDate businessDate,
                String dispositionType, String fromStatus, String effectiveStatus,
                String reasonCode, String reasonText, String authorizationRef,
                String orderSnapshotSha256, String requestSha256, long aggregateVersion,
                LocalDateTime occurredAt);
}
