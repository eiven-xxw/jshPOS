package com.jingshanghui.pos.operations.infrastructure.persistence;

import com.jingshanghui.pos.operations.application.model.DailyCloseModels.*;
import com.jingshanghui.pos.operations.application.port.DailyClosePersistencePort;
import com.jingshanghui.pos.operations.infrastructure.persistence.mapper.DailyCloseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** Operations 应用端口到 MyBatis XML 的适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisDailyClosePersistenceAdapter implements DailyClosePersistencePort {
    private final DailyCloseMapper mapper;
    @Override public CloseRecord find(String t, String id) { return mapper.find(t,id); }
    @Override public CloseRecord lock(String t, String id) { return mapper.lock(t,id); }
    @Override public CloseRecord findByCreateKey(String t, String k) { return mapper.findByCreateKey(t,k); }
    @Override public List<CloseRecord> list(String t, Long s, LocalDate d, int l) { return mapper.list(t,s,d,l); }
    @Override public int nextVersion(String t, Long s, LocalDate d) { return mapper.nextVersion(t,s,d); }
    @Override public void insertClose(CloseWrite v) { mapper.insertClose(v); }
    @Override public int changeState(StateChange v) { return mapper.changeState(v); }
    @Override public int nextPreflightRun(String t, String id) { return mapper.nextPreflightRun(t,id); }
    @Override public void insertSnapshot(SnapshotWrite v) { mapper.insertSnapshot(v); }
    @Override public void insertCheckpoint(CheckpointWrite v) { mapper.insertCheckpoint(v); }
    @Override public void insertPreflight(PreflightWrite v) { mapper.insertPreflight(v); }
    @Override public void insertDifference(DifferenceWrite v) { mapper.insertDifference(v); }
    @Override public void insertApproval(ApprovalWrite v) { mapper.insertApproval(v); }
    @Override public void insertSignature(SignatureWrite v) { mapper.insertSignature(v); }
    @Override public CommandRecord findCommand(String t, String o, String k) { return mapper.findCommand(t,o,k); }
    @Override public void insertCommand(CommandWrite v) { mapper.insertCommand(v); }
    @Override public void appendState(StateEventWrite v) { mapper.appendState(v); }
    @Override public void appendAudit(AuditWrite v) { mapper.appendAudit(v); }
    @Override public void appendOutbox(OutboxWrite v) { mapper.appendOutbox(v); }
    @Override public List<SnapshotRecord> listSnapshots(String t,String id){return mapper.listSnapshots(t,id);}
    @Override public List<CheckpointRecord> listCheckpoints(String t,String id){return mapper.listCheckpoints(t,id);}
    @Override public List<PreflightRecord> listPreflights(String t,String id){return mapper.listPreflights(t,id);}
    @Override public List<DifferenceRecord> listDifferences(String t,String id){return mapper.listDifferences(t,id);}
    @Override public List<ApprovalRecord> listApprovals(String t,String id){return mapper.listApprovals(t,id);}
    @Override public List<SignatureRecord> listSignatures(String t,String id){return mapper.listSignatures(t,id);}
}
