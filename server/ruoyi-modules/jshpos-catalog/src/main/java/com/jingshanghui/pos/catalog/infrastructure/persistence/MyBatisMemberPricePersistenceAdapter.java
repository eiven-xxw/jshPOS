package com.jingshanghui.pos.catalog.infrastructure.persistence;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.VersionView;
import com.jingshanghui.pos.catalog.application.port.MemberPricePersistencePort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.MemberPricePersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 会员价 XML Mapper 到 Pricing Owner 端口的薄适配层。 */
@Repository
@RequiredArgsConstructor
public class MyBatisMemberPricePersistenceAdapter implements MemberPricePersistencePort {
    private final MemberPricePersistenceMapper mapper;
    @Override public int insertVersion(VersionWrite v){return mapper.insertVersion(v);}
    @Override public int insertItems(List<ItemWrite> v){return mapper.insertItems(v);}
    @Override public VersionView findVersion(String t,String v){return mapper.findVersion(t,v);}
    @Override public VersionView lockVersion(String t,String v){return mapper.lockVersion(t,v);}
    @Override public int transition(Transition v){return mapper.transition(v);}
    @Override public int countPublishingConflicts(String t,String v,Long s,LocalDateTime f,LocalDateTime to){
        return mapper.countPublishingConflicts(t,v,s,f,to);
    }
    @Override public MemberPriceCandidate findCandidate(CandidateLookup v){return mapper.findCandidate(v);}
    @Override public StoredCommand findCommand(String t,String c,String k){return mapper.findCommand(t,c,k);}
    @Override public int insertCommand(CommandWrite v){return mapper.insertCommand(v);}
    @Override public int insertOutbox(OutboxWrite v){return mapper.insertOutbox(v);}
}
