package com.jingshanghui.pos.order.application.port;

/** Order/Shift Owner 为开店检查返回现金班次清场事实。 */
public interface StoreOnboardingShiftPort {
    ShiftReadiness readiness(Long storeId);

    record ShiftReadiness(Long storeId, int openOrClosingCount, String factSha256) {
    }
}
