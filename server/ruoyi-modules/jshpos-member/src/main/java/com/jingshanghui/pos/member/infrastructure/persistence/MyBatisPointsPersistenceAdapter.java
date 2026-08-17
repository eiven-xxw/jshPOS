package com.jingshanghui.pos.member.infrastructure.persistence;

import com.jingshanghui.pos.member.application.model.PointsViews.*;
import com.jingshanghui.pos.member.application.port.PointsPersistencePort;
import com.jingshanghui.pos.member.infrastructure.persistence.mapper.PointsPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 积分 XML Mapper 到领域端口的薄适配层。 */
@Repository
@RequiredArgsConstructor
public class MyBatisPointsPersistenceAdapter implements PointsPersistencePort {
    private final PointsPersistenceMapper mapper;
    @Override public AccountView lockAccount(String t,String m){return mapper.lockAccount(t,m);}
    @Override public AccountView findAccount(String t,String m){return mapper.findAccount(t,m);}
    @Override public int insertAccount(AccountWrite v){return mapper.insertAccount(v);}
    @Override public int updateAccount(AccountWrite v){return mapper.updateAccount(v);}
    @Override public int replaceAccountProjection(AccountWrite v){return mapper.replaceAccountProjection(v);}
    @Override public int insertLedger(LedgerWrite v){return mapper.insertLedger(v);}
    @Override public LedgerView findLedger(String t,String id){return mapper.findLedger(t,id);}
    @Override public LedgerView findLedgerByCommand(String t,String id){return mapper.findLedgerByCommand(t,id);}
    @Override public List<LedgerView> listLedgers(String t,String m){return mapper.listLedgers(t,m);}
    @Override public int insertLot(LotWrite v){return mapper.insertLot(v);}
    @Override public LotRow lockLot(String t,String id){return mapper.lockLot(t,id);}
    @Override public List<LotRow> listFefoAvailableLots(String t,String m,LocalDateTime at){return mapper.listFefoAvailableLots(t,m,at);}
    @Override public int updateLot(LotUpdate v){return mapper.updateLot(v);}
    @Override public int insertAllocation(AllocationWrite v){return mapper.insertAllocation(v);}
    @Override public List<FrozenAllocationRow> listFrozenAllocations(String t,String id){return mapper.listFrozenAllocations(t,id);}
    @Override public List<SpendAllocationRow> listSpendAllocations(String t,String id){return mapper.listSpendAllocations(t,id);}
    @Override public BigDecimal sumReversedAmount(String t,String id,String type){return mapper.sumReversedAmount(t,id,type);}
    @Override public int insertLevel(LevelWrite v){return mapper.insertLevel(v);}
    @Override public LevelView findCurrentLevel(String t,String m){return mapper.findCurrentLevel(t,m);}
}
