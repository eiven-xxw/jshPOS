package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort;
import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort.DailyReportingFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reporting Owner 投影缺口与差异观察；异常中心不以报表值覆盖权威事实。 */
@Component @RequiredArgsConstructor
public class ReportingExceptionOwnerAdapter implements OperationsExceptionOwnerPort {
    private final DailyCloseReportingReadPort reporting; private final Clock clock;
    @Override public String ownerCode(){return "REPORTING";}
    @Override public List<OwnerObservation> scan(Long storeId,LocalDate date,int limit){DailyReportingFacts f=reporting.read(storeId,date);
        List<OwnerObservation> v=new ArrayList<>(); add(v,storeId,date,"REPORTING_LINEAGE_GAP",f.incompleteLineageCount(),f.maximumSourceSequence());
        add(v,storeId,date,"REPORTING_DIFFERENCE",f.openDifferenceCount(),f.maximumSourceSequence());return v.stream().limit(limit).toList();}
    @Override public OwnerRepairResult repair(OwnerRepairCommand c){return new OwnerRepairResult("WAITING_OWNER",c.sourceFactId(),null,
        "Reporting Owner必须校验来源检查点后使用影子投影重建；异常中心未直接修改投影");}
    private void add(List<OwnerObservation>v,Long s,LocalDate d,String t,long count,long seq){if(count<=0)return;
        String h=CanonicalJson.from(Map.of("storeId",s,"businessDate",d.toString(),"type",t,"count",count,"sequence",seq)).sha256();
        v.add(new OwnerObservation(t,"store-"+s+"-"+d+"-"+t,t+"-"+h.substring(0,24),seq,h,"store-"+s+"-"+d+"-"+t,
            "P1","rpt-"+h.substring(0,24),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),t+"数量="+count,"REBUILD_SHADOW_PROJECTION"));}
}
