package com.jingshanghui.pos.sync.infrastructure.persistence;

import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivationRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.CredentialRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.DeviceAuthRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.StoredCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalView;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.CredentialVersionChange;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.DeviceCapabilityChange;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.StatusChange;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.TerminalRegistryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 终端 XML Mapper 到领域持久化端口的薄适配层。 */
@Repository
@RequiredArgsConstructor
public class MyBatisTerminalRegistryAdapter implements TerminalRegistryPort {
    private final TerminalRegistryMapper mapper;
    @Override public ActivationRecord findActivationByCommand(String t,String key) { return mapper.findActivationByCommand(t,key); }
    @Override public ActivationRecord lockActivationById(String id) { return mapper.lockActivationById(id); }
    @Override public int insertActivation(ActivationWrite v) { return mapper.insertActivation(v); }
    @Override public int cancelActivation(String t,String id,Long actor,LocalDateTime at) { return mapper.cancelActivation(new TerminalPersistenceParams.CancelActivation(t,id,actor,at)); }
    @Override public int consumeActivation(String id,String device,long version,LocalDateTime at) { return mapper.consumeActivation(new TerminalPersistenceParams.ConsumeActivation(id,device,version,at)); }
    @Override public int insertDevice(DeviceWrite v) { return mapper.insertDevice(v); }
    @Override public TerminalView findDevice(String t,String id) { return mapper.findDevice(t,id); }
    @Override public TerminalView lockDevice(String t,String id) { return mapper.lockDevice(t,id); }
    @Override public DeviceAuthRecord lockDeviceForAuthentication(String id) { return mapper.lockDeviceForAuthentication(id); }
    @Override public List<TerminalView> listDevices(String t,Long store,int offset,int limit) { return mapper.listDevices(t,store,offset,limit); }
    @Override public long countDevices(String t,Long store) { return mapper.countDevices(t,store); }
    @Override public int changeStatus(StatusChange v) { return mapper.changeStatus(new TerminalPersistenceParams.StatusUpdate(v.tenantId(),v.deviceId(),v.fromStatus(),v.toStatus(),v.reason(),v.expectedVersion(),v.at())); }
    @Override public int updateCredentialVersion(CredentialVersionChange v) { return mapper.updateCredentialVersion(new TerminalPersistenceParams.CredentialVersionUpdate(v.tenantId(),v.deviceId(),v.fromVersion(),v.toVersion(),v.at())); }
    @Override public int insertCredential(CredentialWrite v) { return mapper.insertCredential(v); }
    @Override public CredentialRecord findActiveCredential(String t,String id) { return mapper.findActiveCredential(t,id); }
    @Override public int invalidateActiveCredential(String t,String id,String status,LocalDateTime at) { return mapper.invalidateActiveCredential(t,id,status,at); }
    @Override public long nextCapabilitySequence(String t,String id) { return mapper.nextCapabilitySequence(t,id); }
    @Override public String findCapabilityDigest(String t,String id,String hash) { return mapper.findCapabilityDigest(t,id,hash); }
    @Override public int insertCapability(CapabilityWrite v) { return mapper.insertCapability(v); }
    @Override public int updateDeviceCapability(DeviceCapabilityChange v) { return mapper.updateDeviceCapability(new TerminalPersistenceParams.CapabilityUpdate(v.tenantId(),v.deviceId(),v.appVersion(),v.schemaVersion(),v.capabilitySha256(),v.clockSkewSeconds(),v.at())); }
    @Override public StoredCommand findCommand(String t,String type,String key) { return mapper.findCommand(t,type,key); }
    @Override public int insertCommand(CommandWrite v) { return mapper.insertCommand(v); }
    @Override public int insertAudit(AuditWrite v) { return mapper.insertAudit(v); }
}
