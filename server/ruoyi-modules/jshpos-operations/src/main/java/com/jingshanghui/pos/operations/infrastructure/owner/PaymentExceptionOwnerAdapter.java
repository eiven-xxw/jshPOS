package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort;
import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort.DailyPaymentFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Provider 无关支付/退款 UNKNOWN 来源观察；PAY-002 未解阻时修复只等待可信观察。 */
@Component @RequiredArgsConstructor
public class PaymentExceptionOwnerAdapter implements OperationsExceptionOwnerPort {
    private final DailyClosePaymentReadPort payments; private final StoreIndustryReadPort stores; private final Clock clock;
    @Override public String ownerCode(){return "PAYMENT_REFUND";}
    @Override public List<OwnerObservation> scan(Long storeId,LocalDate businessDate,int limit){
        StoreIndustryReadPort.IndustryBinding store=stores.requireCurrentIndustry(storeId);
        ZonedDateTime start=ZonedDateTime.of(businessDate,store.businessDayStart(),ZoneId.of(store.zoneId()));
        DailyPaymentFacts f=payments.read(storeId,start.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(),
            start.plusDays(1).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        List<OwnerObservation> values=new ArrayList<>();
        add(values,storeId,businessDate,"PAYMENT_UNKNOWN",f.unknownPaymentCount(),f.sourceVersion());
        add(values,storeId,businessDate,"REFUND_UNKNOWN",f.unknownRefundCount(),f.sourceVersion());
        return values.stream().limit(limit).toList();
    }
    @Override public OwnerRepairResult repair(OwnerRepairCommand c){return new OwnerRepairResult("WAITING_OWNER",c.sourceFactId(),null,
        "T2-PAY-002仍BLOCKED；仅允许查询/回调/账单观察原attempt，未创建扣款或退款命令");}
    private void add(List<OwnerObservation> v,Long store,LocalDate date,String type,long count,long sequence){if(count<=0)return;
        String h=CanonicalJson.from(Map.of("storeId",store,"businessDate",date.toString(),"type",type,"count",count,"sequence",sequence)).sha256();
        v.add(new OwnerObservation(type,"store-"+store+"-"+date+"-"+type,type+"-"+h.substring(0,24),sequence,h,
            "store-"+store+"-"+date+"-"+type,"P0","pay-"+h.substring(0,24),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),
            type+"数量="+count,"OBSERVE_ORIGINAL_ATTEMPT"));}
}
