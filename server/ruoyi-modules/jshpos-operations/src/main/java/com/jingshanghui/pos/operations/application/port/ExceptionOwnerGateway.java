package com.jingshanghui.pos.operations.application.port;

import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerObservation;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairCommand;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairResult;

import java.time.LocalDate;
import java.util.List;

/** Operations 编排来源扫描与具名修复的唯一跨 Owner 网关。 */
public interface ExceptionOwnerGateway {
    List<OwnedObservation> scan(Long storeId, LocalDate businessDate, int limit);
    OwnerRepairResult repair(String ownerCode, OwnerRepairCommand command);

    /**
     * 保留 Owner 身份的权威观察。
     *
     * @param ownerCode 来源 Owner 稳定代码
     * @param observation Owner 提供的去敏可验真观察
     */
    record OwnedObservation(String ownerCode, OwnerObservation observation) { }
}
