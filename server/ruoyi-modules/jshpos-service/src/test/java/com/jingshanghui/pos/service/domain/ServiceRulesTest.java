package com.jingshanghui.pos.service.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** T2-SVC-001 输入、租约、职责分离和附件安全不变量。 */
class ServiceRulesTest {
    @Test
    void shouldNormalizeSafeScalarValues() {
        assertAll(
            () -> assertEquals("SERVICE_TYPE", ServiceRules.code("service_type", "type")),
            () -> assertEquals("abcdefgh", ServiceRules.idempotencyKey("abcdefgh")),
            () -> assertEquals("处理完成", ServiceRules.text(" 处理完成 ", "note", 20)),
            () -> assertEquals(30, ServiceRules.targetMinutes(30)),
            () -> assertEquals(15, ServiceRules.leaseMinutes(15)),
            () -> assertEquals("receipt.pdf", ServiceRules.safeFileName("receipt.pdf")),
            () -> assertEquals("application/pdf", ServiceRules.mediaType("APPLICATION/PDF")),
            () -> assertEquals(1L, ServiceRules.attachmentSize(1)),
            () -> assertEquals("a".repeat(64), ServiceRules.sha256("a".repeat(64))),
            () -> assertEquals("service/t1/tickets/ticket1/attachments/attachment1",
                ServiceRules.objectKey("t1", "ticket1", "attachment1"))
        );
    }

    @Test
    void shouldRejectMalformedScalarValues() {
        assertAll(
            () -> assertThrows(ServiceException.class, () -> ServiceRules.code("bad code", "type")),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.idempotencyKey("short")),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.text("=1+1", "note", 20)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.text("too-long", "note", 3)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.targetMinutes(0)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.targetMinutes(525601)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.leaseMinutes(null)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.leaseMinutes(1441)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.safeFileName("../secret.txt")),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.safeFileName("bad\u0000.txt")),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.mediaType("application/zip")),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.attachmentSize(0)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.attachmentSize(ServiceRules.MAX_ATTACHMENT_BYTES + 1)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.sha256("not-a-hash")),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.required(" ", "value"))
        );
    }

    @Test
    void shouldEnforceActiveOwnerLease() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 1, 0);
        assertDoesNotThrow(() -> ServiceRules.requireActiveLease(10L, now.plusMinutes(1), 10L, now));
        assertAll(
            () -> assertThrows(ServiceException.class, () -> ServiceRules.requireActiveLease(null, now.plusMinutes(1), 10L, now)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.requireActiveLease(10L, now, 10L, now)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.requireActiveLease(10L, now.plusMinutes(1), 11L, now))
        );
    }

    @Test
    void shouldSeparateResolverAndCloser() {
        assertDoesNotThrow(() -> ServiceRules.requireIndependentReviewer(10L, 11L));
        assertAll(
            () -> assertThrows(ServiceException.class, () -> ServiceRules.requireIndependentReviewer(null, 11L)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.requireIndependentReviewer(10L, null)),
            () -> assertThrows(ServiceException.class, () -> ServiceRules.requireIndependentReviewer(10L, 10L))
        );
    }
}
