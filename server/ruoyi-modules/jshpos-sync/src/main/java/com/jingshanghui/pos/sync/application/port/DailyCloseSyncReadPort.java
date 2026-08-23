package com.jingshanghui.pos.sync.application.port;

/** Operations 日结读取门店同步积压、冲突与死信的窄端口。 */
public interface DailyCloseSyncReadPort {
    DailySyncFacts read(Long storeId);

    record DailySyncFacts(long pendingCount, long retryCount, long conflictCount,
                          long deadLetterCount, long maximumDeviceSequence,
                          long appliedCount) {
    }
}
