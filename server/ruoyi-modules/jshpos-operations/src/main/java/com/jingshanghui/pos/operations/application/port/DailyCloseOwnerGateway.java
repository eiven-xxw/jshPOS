package com.jingshanghui.pos.operations.application.port;

import com.jingshanghui.pos.operations.application.model.DailyCloseModels.OwnerSnapshot;

import java.time.LocalDate;

/** 日结应用层读取所有权威 Owner 的唯一编排端口。 */
public interface DailyCloseOwnerGateway {
    OwnerSnapshot capture(Long storeId, LocalDate businessDate);
}
