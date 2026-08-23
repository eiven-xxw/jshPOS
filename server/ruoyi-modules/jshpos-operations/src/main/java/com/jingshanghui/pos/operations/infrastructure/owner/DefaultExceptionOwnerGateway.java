package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairCommand;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairResult;
import com.jingshanghui.pos.operations.application.port.ExceptionOwnerGateway;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** 聚合所有已装配 Owner 窄端口；缺失端口严格失败关闭。 */
@Component
public class DefaultExceptionOwnerGateway implements ExceptionOwnerGateway {
    private final List<OperationsExceptionOwnerPort> owners;

    public DefaultExceptionOwnerGateway(List<OperationsExceptionOwnerPort> owners) {
        this.owners = List.copyOf(owners);
    }

    @Override
    public List<OwnedObservation> scan(Long storeId, LocalDate businessDate, int limit) {
        return owners.stream().flatMap(owner -> owner.scan(storeId, businessDate, limit).stream()
                .map(observation -> new OwnedObservation(owner.ownerCode(), observation)))
            .sorted(Comparator.comparing((OwnedObservation value) -> value.observation().observedAt())
                .thenComparing(OwnedObservation::ownerCode).thenComparing(value -> value.observation().sourceFactId()))
            .limit(limit).toList();
    }

    @Override
    public OwnerRepairResult repair(String ownerCode, OwnerRepairCommand command) {
        return owners.stream().filter(owner -> owner.ownerCode().equals(ownerCode)).findFirst()
            .map(owner -> owner.repair(command))
            .orElse(new OwnerRepairResult("UNAVAILABLE", "OWNER_PORT_NOT_CONFIGURED", null,
                "Owner修复端口未装配；案件保持等待且未伪造成功"));
    }
}
