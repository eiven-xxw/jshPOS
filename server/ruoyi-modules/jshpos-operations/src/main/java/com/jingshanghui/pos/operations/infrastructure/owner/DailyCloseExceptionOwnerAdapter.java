package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.operations.application.model.DailyCloseModels.DifferenceRecord;
import com.jingshanghui.pos.operations.application.service.DailyCloseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Operations 内部日结差异窄适配；更正只能走既有日结版本流程。 */
@Component @RequiredArgsConstructor
public class DailyCloseExceptionOwnerAdapter implements OperationsExceptionOwnerPort {
    private final DailyCloseService closes;
    @Override public String ownerCode(){return "DAILY_CLOSE";}
    @Override public List<OwnerObservation> scan(Long storeId,LocalDate date,int limit){List<OwnerObservation> values=new ArrayList<>();
        closes.list(storeId,date,limit).forEach(close->closes.detail(close.closeId()).differences().forEach(d->values.add(observation(close.closeId(),d))));
        return values.stream().limit(limit).toList();}
    @Override public OwnerRepairResult repair(OwnerRepairCommand c){return new OwnerRepairResult("WAITING_OWNER",c.sourceFactId(),null,
        "日结差异必须通过既有更正版本和再次签署处理；旧CLOSED事实未被修改");}
    private OwnerObservation observation(String closeId,DifferenceRecord d){String hash=CanonicalJson.from(Map.of("closeId",closeId,
        "differenceId",d.differenceId(),"type",d.type(),"expected",d.expectedSha256(),"actual",d.actualSha256())).sha256();
        return new OwnerObservation("DAILY_CLOSE_"+d.type(),d.differenceId(),d.differenceId(),0,hash,"close-"+closeId+"-"+d.type(),
            "P1","close-"+d.differenceId(),d.detectedAt(),"日结差异="+d.type(),"CREATE_CORRECTION_VERSION");}
}
