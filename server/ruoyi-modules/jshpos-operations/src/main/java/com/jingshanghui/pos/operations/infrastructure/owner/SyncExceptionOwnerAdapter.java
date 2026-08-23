package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort;
import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort.DailySyncFacts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 通过 Sync Owner 窄只读端口形成积压、冲突和死信观察；不直接访问 Sync Mapper。 */
@Component
@RequiredArgsConstructor
public class SyncExceptionOwnerAdapter implements OperationsExceptionOwnerPort {
    private final DailyCloseSyncReadPort sync;
    private final Clock clock;
    @Override public String ownerCode(){return "SYNC";}
    @Override public List<OwnerObservation> scan(Long storeId, LocalDate businessDate, int limit) {
        DailySyncFacts facts=sync.read(storeId); List<OwnerObservation> values=new ArrayList<>();
        if(facts==null)return values;
        add(values,storeId,"SYNC_BACKLOG",facts.pendingCount()+facts.retryCount(),facts,"P2","OBSERVE_ORIGINAL_SYNC_EVENT");
        add(values,storeId,"SYNC_CONFLICT",facts.conflictCount(),facts,"P1","MANUAL_OWNER_REVIEW");
        add(values,storeId,"SYNC_DEAD_LETTER",facts.deadLetterCount(),facts,"P1","RETRY_ORIGINAL_SYNC_EVENT");
        return values.stream().limit(limit).toList();
    }
    @Override public OwnerRepairResult repair(OwnerRepairCommand command) {
        return new OwnerRepairResult("WAITING_OWNER",command.sourceEventId(),null,
            "聚合异常必须由Sync Owner选择原event_id后复用原命令；未生成新业务事件");
    }
    private void add(List<OwnerObservation> values,Long storeId,String type,long count,DailySyncFacts facts,String severity,String action){
        if(count<=0)return; Map<String,Object> content=map("type",type,"storeId",storeId,"count",count,
            "pending",facts.pendingCount(),"retry",facts.retryCount(),"conflict",facts.conflictCount(),
            "deadLetter",facts.deadLetterCount(),"sequence",facts.maximumDeviceSequence());
        String hash=CanonicalJson.from(content).sha256(); values.add(new OwnerObservation(type,"store-"+storeId+"-"+type,
            type+"-"+hash.substring(0,24),facts.maximumDeviceSequence(),hash,"store-"+storeId+"-"+type,
            severity,"sync-"+hash.substring(0,24),LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),
            type+"数量="+count,action));
    }
    private Map<String,Object> map(Object...v){Map<String,Object>m=new LinkedHashMap<>();for(int i=0;i<v.length;i+=2)m.put(String.valueOf(v[i]),v[i+1]);return m;}
}
