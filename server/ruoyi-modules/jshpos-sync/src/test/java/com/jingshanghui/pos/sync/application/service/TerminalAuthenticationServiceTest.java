package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.sync.application.model.TerminalModels.AuthenticateTerminalCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.CredentialRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.DeviceAuthRecord;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.infrastructure.security.HmacTerminalSecretProtector;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminalAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private static final String DEVICE = "01K2A000000000000000000151";
    private final TerminalRegistryPort port = mock(TerminalRegistryPort.class);
    private final SyncIdGenerator ids = mock(SyncIdGenerator.class);
    private final HmacTerminalSecretProtector protector =
        new HmacTerminalSecretProtector("synthetic-test-pepper-32-characters-minimum");
    private final TerminalAuthenticationService service = new TerminalAuthenticationService(port, protector, ids,
        Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void derivesTenantAndStoreOnlyAfterCredentialVerification() {
        String raw = "synthetic-device-credential-123456789012";
        when(port.lockDeviceForAuthentication(DEVICE)).thenReturn(device("b".repeat(64), "c".repeat(64)));
        when(port.findActiveCredential("TENANT_A", DEVICE)).thenReturn(new CredentialRecord(
            "01K2A000000000000000000152", "TENANT_A", DEVICE, 1,
            protector.digest("credential:" + DEVICE + ":1", raw), "b".repeat(64), "c".repeat(64),
            "ACTIVE", LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC)));

        var result = service.authenticate(command(raw, "b".repeat(64), "c".repeat(64)));

        assertThat(result.tenantId()).isEqualTo("TENANT_A");
        assertThat(result.storeId()).isEqualTo(1101L);
        assertThat(result.credentialVersion()).isEqualTo(1);
    }

    @Test
    void blocksClonedCredentialAndAppendsSecurityAudit() {
        when(ids.next()).thenReturn("01K2A000000000000000000153", "01K2A000000000000000000154");
        when(port.lockDeviceForAuthentication(DEVICE)).thenReturn(device("b".repeat(64), "c".repeat(64)));
        when(port.findActiveCredential("TENANT_A", DEVICE)).thenReturn(new CredentialRecord(
            "01K2A000000000000000000152", "TENANT_A", DEVICE, 1, "d".repeat(64),
            "b".repeat(64), "c".repeat(64), "ACTIVE",
            LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC)));

        assertThatThrownBy(() -> service.authenticate(command("wrong-credential", "e".repeat(64), "c".repeat(64))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_CREDENTIAL_CLONED");
        verify(port).changeStatus(new TerminalRegistryPort.StatusChange("TENANT_A", DEVICE, "ACTIVE", "BLOCKED",
            "检测到凭据克隆或硬件身份变化", 9L, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        verify(port).insertAudit(any());
    }

    private DeviceAuthRecord device(String fingerprint, String publicKey) {
        return new DeviceAuthRecord("TENANT_A", DEVICE, 1001L, 1101L, DEVICE, 201L, "ACTIVE",
            fingerprint, publicKey, "1.0.0", "1.0", "1.0", "1", 1, 9);
    }

    private AuthenticateTerminalCommand command(String raw, String fingerprint, String publicKey) {
        return new AuthenticateTerminalCommand(DEVICE, raw, fingerprint, publicKey,
            "1.0.0", "1.0", "1", NOW);
    }
}
