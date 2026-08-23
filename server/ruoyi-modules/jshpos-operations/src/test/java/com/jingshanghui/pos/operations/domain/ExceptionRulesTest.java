package com.jingshanghui.pos.operations.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExceptionRulesTest {
    @Test void acceptsFrozenOwnersSeverityLeaseAndIdentifiers(){
        assertThat(ExceptionRules.store(1L)).isEqualTo(1L);assertThat(ExceptionRules.date(LocalDate.of(2026,8,23))).isNotNull();
        for(String owner:new String[]{"SYNC","DATA_PACKAGE","PAYMENT_REFUND","INVENTORY","COSTING","REPORTING","DAILY_CLOSE"})assertThat(ExceptionRules.owner(owner)).isEqualTo(owner);
        for(String level:new String[]{"P0","P1","P2","P3"})assertThat(ExceptionRules.severity(level)).isEqualTo(level);
        assertThat(ExceptionRules.safe("event:001","CODE")).isEqualTo("event:001");assertThat(ExceptionRules.hash("a".repeat(64))).hasSize(64);
        assertThat(ExceptionRules.leaseMinutes(5)).isEqualTo(5);assertThat(ExceptionRules.leaseMinutes(120)).isEqualTo(120);
        assertThat(ExceptionRules.limit(0)).isEqualTo(1);assertThat(ExceptionRules.limit(999)).isEqualTo(100);
        assertThat(ExceptionRules.reason("  已完成Owner结果独立复核  ")).isEqualTo("已完成Owner结果独立复核");ExceptionRules.different(1L,2L,"复核");
    }
    @Test void rejectsUntrustedOrAmbiguousValues(){bad(()->ExceptionRules.store(null));bad(()->ExceptionRules.store(0L));bad(()->ExceptionRules.date(null));
        bad(()->ExceptionRules.owner("ORDER"));bad(()->ExceptionRules.severity("HIGH"));bad(()->ExceptionRules.safe("bad value","CODE"));
        bad(()->ExceptionRules.safe(null,"CODE"));bad(()->ExceptionRules.hash("A".repeat(64)));bad(()->ExceptionRules.leaseMinutes(4));
        bad(()->ExceptionRules.leaseMinutes(121));bad(()->ExceptionRules.reason("太短"));bad(()->ExceptionRules.reason("x".repeat(257)));
        bad(()->ExceptionRules.different(1L,1L,"复核"));}
    private void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable c){assertThatThrownBy(c).isInstanceOf(ServiceException.class);}
}
