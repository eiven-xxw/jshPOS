package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort.IndustryBinding;
import com.jingshanghui.pos.operations.application.model.DailyCloseModels.OwnerSnapshot;
import com.jingshanghui.pos.operations.domain.DailyCloseStates.CheckStatus;
import com.jingshanghui.pos.order.application.port.DailyCloseOrderReadPort;
import com.jingshanghui.pos.order.application.port.DailyCloseOrderReadPort.DailyOrderFacts;
import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort;
import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort.DailyPaymentFacts;
import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort;
import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort.DailyReportingFacts;
import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort;
import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort.DailySyncFacts;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDailyCloseOwnerGatewayTest {
    @Mock StoreIndustryReadPort stores;
    @Mock DailyCloseOrderReadPort orders;
    @Mock DailyClosePaymentReadPort payments;
    @Mock DailyCloseSyncReadPort sync;
    @Mock DailyCloseReportingReadPort reporting;
    DefaultDailyCloseOwnerGateway gateway;

    @BeforeEach void setUp(){ gateway=new DefaultDailyCloseOwnerGateway(stores,orders,payments,sync,reporting); }

    @Test
    void freezesOwnerCheckpointsAndKeepsExternalProviderBlocked() {
        LocalDate date=LocalDate.of(2026,8,23);
        when(stores.requireCurrentIndustry(10L)).thenReturn(binding("Asia/Shanghai",LocalTime.of(3,0)));
        when(orders.read(10L,date)).thenReturn(order(0,0));
        when(payments.read(eq(10L),any(LocalDateTime.class),any(LocalDateTime.class))).thenReturn(new DailyPaymentFacts(0,0,0,0,0,0,1,"CNY"));
        when(sync.read(10L)).thenReturn(new DailySyncFacts(0,0,0,0,12,12));
        when(reporting.read(10L,date)).thenReturn(report(0,0,0,5));

        OwnerSnapshot result=gateway.capture(10L,date);

        assertThat(result.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(result.checkpoints()).extracting("ownerCode").containsExactly("FOUNDATION","SHIFT_ORDER","PAYMENT_REFUND","SYNC","REPORTING");
        assertThat(result.checks()).filteredOn(value -> value.external()).singleElement()
            .satisfies(value -> assertThat(value.status()).isEqualTo(CheckStatus.BLOCKED));
        assertThat(result.checks()).filteredOn(value -> value.required()).allMatch(value -> value.status()==CheckStatus.PASS);
        assertThat(result.canonicalContent()).containsKeys("foundation","order","payment","sync","reporting");
    }

    @Test
    void failsRequiredChecksForOpenShiftUnknownAndProjectionMismatch() {
        LocalDate date=LocalDate.of(2026,11,1);
        when(stores.requireCurrentIndustry(10L)).thenReturn(binding("America/New_York",LocalTime.of(2,30)));
        when(orders.read(10L,date)).thenReturn(order(1,1));
        when(payments.read(eq(10L),any(),any())).thenReturn(new DailyPaymentFacts(0,0,0,0,1,1,2,"CNY"));
        when(sync.read(10L)).thenReturn(new DailySyncFacts(1,1,1,1,20,10));
        when(reporting.read(10L,date)).thenReturn(report(0,1,2,1));
        OwnerSnapshot result=gateway.capture(10L,date);
        assertThat(result.checks()).filteredOn(value -> value.required() && value.status()==CheckStatus.FAIL).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void freezesProviderNeutralPaymentAndComposesTotalRefundWithoutNetwork() {
        LocalDate date=LocalDate.of(2026,8,23);
        when(stores.requireCurrentIndustry(10L)).thenReturn(binding("Asia/Shanghai",LocalTime.of(3,0)));
        when(orders.read(10L,date)).thenReturn(new DailyOrderFacts(2,0,1,200,20,0,180,5,180,5,0,0,0,3,"CNY"));
        when(payments.read(eq(10L),any(),any())).thenReturn(new DailyPaymentFacts(1,100,1,7,0,0,4,"CNY"));
        when(sync.read(10L)).thenReturn(new DailySyncFacts(0,0,0,0,12,12));
        when(reporting.read(10L,date)).thenReturn(new DailyReportingFacts(2,0,2,200,20,0,180,12,180,5,0,0,0,5,12,
            "sales-v1","inventory-v1","CNY"));

        OwnerSnapshot result=gateway.capture(10L,date);

        assertThat(result.amounts().refundMinor()).isEqualTo(12);
        assertThat(result.amounts().returnCount()).isEqualTo(2);
        assertThat(result.amounts().electronicReceivedMinor()).isEqualTo(100);
        assertThat(result.amounts().electronicRefundedMinor()).isEqualTo(7);
        assertThat(result.checks()).filteredOn(value -> value.required()).allMatch(value -> value.status()==CheckStatus.PASS);
    }

    @Test
    void rejectsInvalidTimezoneCurrencyMissingOwnerAndBrokenMoney() {
        LocalDate date=LocalDate.of(2026,8,23);
        when(stores.requireCurrentIndustry(10L)).thenReturn(binding("bad/zone",LocalTime.MIDNIGHT));
        assertThatThrownBy(() -> gateway.capture(10L,date)).isInstanceOf(ServiceException.class).hasMessageContaining("IANA");

        when(stores.requireCurrentIndustry(10L)).thenReturn(binding("Asia/Shanghai",LocalTime.MIDNIGHT));
        when(orders.read(10L,date)).thenReturn(null);
        assertThatThrownBy(() -> gateway.capture(10L,date)).isInstanceOf(ServiceException.class).hasMessageContaining("SHIFT_ORDER");

        when(orders.read(10L,date)).thenReturn(new DailyOrderFacts(0,0,0,100,0,0,99,0,0,0,0,0,0,1,"CNY"));
        when(payments.read(eq(10L),any(),any())).thenReturn(new DailyPaymentFacts(0,0,0,0,0,0,1,"USD"));
        when(sync.read(10L)).thenReturn(new DailySyncFacts(0,0,0,0,0,0));
        when(reporting.read(10L,date)).thenReturn(report(0,0,0,5));
        assertThatThrownBy(() -> gateway.capture(10L,date)).isInstanceOf(ServiceException.class).hasMessageContaining("金额不守恒");

        when(orders.read(10L,date)).thenReturn(order(0,0));
        assertThatThrownBy(() -> gateway.capture(10L,date)).isInstanceOf(ServiceException.class).hasMessageContaining("只支持CNY");
    }

    private IndustryBinding binding(String zone,LocalTime start){return new IndustryBinding(10L,20L,30L,1,"CONVENIENCE","a".repeat(64),zone,start);}
    private DailyOrderFacts order(long count,long open){return new DailyOrderFacts(count,0,0,100,10,0,90,0,90,0,0,open,0,3,"CNY");}
    private DailyReportingFacts report(long count,long incomplete,long difference,long ownerCount){return new DailyReportingFacts(count,0,0,count==0?100:90,10,0,count==0?90:80,0,90,0,0,incomplete,difference,ownerCount,12,"sales-v1","inventory-v1","CNY");}
}
