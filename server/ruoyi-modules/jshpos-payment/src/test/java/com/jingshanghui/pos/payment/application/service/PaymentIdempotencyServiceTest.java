package com.jingshanghui.pos.payment.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentViews.IdempotencyView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentResult;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 支付命令幂等哈希冲突和不可变结果序列化回归。 */
class PaymentIdempotencyServiceTest {

    @Test
    void sameKeyDifferentHashIsRejected() {
        PaymentMapper mapper = mock(PaymentMapper.class);
        when(mapper.findIdempotency("TENANT_A", "CREATE_PAYMENT_INTENT", "intent:tenant-a:0001"))
            .thenReturn(new IdempotencyView("CREATE_PAYMENT_INTENT", "a".repeat(64),
                "01K2A000000000000000000001", "{}"));
        PaymentIdempotencyService service = new PaymentIdempotencyService(mapper, mock(UlidGenerator.class),
            new ObjectMapper());
        assertThatThrownBy(() -> service.find("TENANT_A", "CREATE_PAYMENT_INTENT", "intent:tenant-a:0001",
            "b".repeat(64), PaymentResult.class)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("PAY-IDEM-002");
    }

    @Test
    void storedResultCanBeReadAndNewResultIsSerializedWithStableAggregate() {
        PaymentMapper mapper = mock(PaymentMapper.class);
        UlidGenerator ulids = mock(UlidGenerator.class);
        when(ulids.next()).thenReturn("01K2A000000000000000000099");
        PaymentResult value = new PaymentResult("01K2A000000000000000000001", "CREATED", 1_299, "CNY", 1, false);
        ObjectMapper json = new ObjectMapper();
        when(mapper.findIdempotency("TENANT_A", "CREATE_PAYMENT_INTENT", "intent:tenant-a:0001"))
            .thenReturn(new IdempotencyView("CREATE_PAYMENT_INTENT", "a".repeat(64), value.paymentId(),
                "{\"paymentId\":\"01K2A000000000000000000001\",\"status\":\"CREATED\",\"amountMinor\":1299,\"currency\":\"CNY\",\"recordVersion\":1,\"duplicate\":false}"));
        PaymentIdempotencyService service = new PaymentIdempotencyService(mapper, ulids, json);
        assertThat(service.find("TENANT_A", "CREATE_PAYMENT_INTENT", "intent:tenant-a:0001", "a".repeat(64),
            PaymentResult.class)).isEqualTo(value);
        LocalDateTime at = LocalDateTime.parse("2026-08-16T08:00:00");
        service.save("TENANT_A", "CREATE_PAYMENT_INTENT", "01K2A000000000000000000002",
            "intent:tenant-a:0002", "b".repeat(64), value.paymentId(), value, at);
        verify(mapper).insertIdempotency("01K2A000000000000000000099", "TENANT_A", "CREATE_PAYMENT_INTENT",
            "01K2A000000000000000000002", "intent:tenant-a:0002", "b".repeat(64), value.paymentId(),
            json.valueToTree(value).toString(), at);
    }
}
