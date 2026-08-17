package com.jingshanghui.pos.sync.infrastructure.persistence.mapper;

import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivationRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.CredentialRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.DeviceAuthRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.StoredCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalView;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.ActivationWrite;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.AuditWrite;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.CapabilityWrite;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.CommandWrite;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.CredentialWrite;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort.DeviceWrite;
import com.jingshanghui.pos.sync.infrastructure.persistence.TerminalPersistenceParams.CancelActivation;
import com.jingshanghui.pos.sync.infrastructure.persistence.TerminalPersistenceParams.CapabilityUpdate;
import com.jingshanghui.pos.sync.infrastructure.persistence.TerminalPersistenceParams.ConsumeActivation;
import com.jingshanghui.pos.sync.infrastructure.persistence.TerminalPersistenceParams.CredentialVersionUpdate;
import com.jingshanghui.pos.sync.infrastructure.persistence.TerminalPersistenceParams.StatusUpdate;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Gate 6A 终端复杂事实 Mapper；所有 SQL 必须位于配套 XML。 */
public interface TerminalRegistryMapper {
    ActivationRecord findActivationByCommand(@Param("tenantId") String tenantId,
                                             @Param("idempotencyKey") String idempotencyKey);
    ActivationRecord lockActivationById(@Param("activationId") String activationId);
    int insertActivation(ActivationWrite value);
    int cancelActivation(CancelActivation value);
    int consumeActivation(ConsumeActivation value);
    int insertDevice(DeviceWrite value);
    TerminalView findDevice(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId);
    TerminalView lockDevice(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId);
    DeviceAuthRecord lockDeviceForAuthentication(@Param("deviceId") String deviceId);
    List<TerminalView> listDevices(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                   @Param("offset") int offset, @Param("limit") int limit);
    long countDevices(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
    int changeStatus(StatusUpdate value);
    int updateCredentialVersion(CredentialVersionUpdate value);
    int insertCredential(CredentialWrite value);
    CredentialRecord findActiveCredential(@Param("tenantId") String tenantId,
                                          @Param("deviceId") String deviceId);
    int invalidateActiveCredential(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                                   @Param("targetStatus") String targetStatus, @Param("at") LocalDateTime at);
    long nextCapabilitySequence(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId);
    String findCapabilityDigest(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                                @Param("capabilitySha256") String capabilitySha256);
    int insertCapability(CapabilityWrite value);
    int updateDeviceCapability(CapabilityUpdate value);
    StoredCommand findCommand(@Param("tenantId") String tenantId, @Param("commandType") String commandType,
                              @Param("idempotencyKey") String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertAudit(AuditWrite value);
}
