package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerObservation;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairCommand;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExceptionOwnerGatewayTest {
    @Test
    void sortsLimitsAndPreservesOwnerIdentity() {
        var gateway = new DefaultExceptionOwnerGateway(List.of(owner("SYNC", 2), owner("REPORTING", 1)));
        var result = gateway.scan(10L, LocalDate.of(2026, 8, 23), 1);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).ownerCode()).isEqualTo("REPORTING");
    }

    @Test
    void missingOwnerRepairPortFailsClosedAsUnavailable() {
        var gateway = new DefaultExceptionOwnerGateway(List.of());
        OwnerRepairResult result = gateway.repair("PAYMENT_REFUND", new OwnerRepairCommand(
            "repair-1", 10L, "PAYMENT_UNKNOWN", "fact-1", "event-1", 1, "a".repeat(64),
            "OBSERVE_ORIGINAL", "b".repeat(64), "idempotency-1", "trace-1"));
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.resultReference()).isEqualTo("OWNER_PORT_NOT_CONFIGURED");
    }

    private OperationsExceptionOwnerPort owner(String code, int minute) {
        return new OperationsExceptionOwnerPort() {
            public String ownerCode() { return code; }
            public List<OwnerObservation> scan(Long storeId, LocalDate businessDate, int limit) {
                return List.of(new OwnerObservation("TYPE", "fact-" + code, "event-" + code, minute,
                    "a".repeat(64), "dedup-" + code, "P1", "trace-" + code,
                    LocalDateTime.of(2026, 8, 23, 0, minute), "去敏摘要", "OBSERVE_ORIGINAL"));
            }
            public OwnerRepairResult repair(OwnerRepairCommand command) {
                return new OwnerRepairResult("WAITING_OWNER", "original-command", "b".repeat(64), "等待Owner");
            }
        };
    }
}
