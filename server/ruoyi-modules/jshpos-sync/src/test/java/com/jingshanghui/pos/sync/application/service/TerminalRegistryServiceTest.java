package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.StoreView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivateTerminalCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivationRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.IssueActivationCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.StoredCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalView;
import com.jingshanghui.pos.sync.application.port.TerminalRegistryPort;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.TerminalHash;
import com.jingshanghui.pos.sync.domain.TerminalSecretGenerator;
import com.jingshanghui.pos.sync.infrastructure.security.HmacTerminalSecretProtector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TerminalRegistryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
    private final TerminalRegistryPort port = mock(TerminalRegistryPort.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final StoreService stores = mock(StoreService.class);
    private final TerminalSecretGenerator secrets = mock(TerminalSecretGenerator.class);
    private final SyncIdGenerator ids = mock(SyncIdGenerator.class);
    private final HmacTerminalSecretProtector protector =
        new HmacTerminalSecretProtector("synthetic-test-pepper-32-characters-minimum");
    private final TerminalRegistryService service = new TerminalRegistryService(port, context, authorization, stores,
        protector, secrets, ids, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void issuesSecretOnceAndPersistsOnlyItsHmac() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "admin"));
        when(stores.list()).thenReturn(List.of(new StoreView(1101L, 1001L, 1L, "A101", "门店A",
            "Asia/Shanghai", LocalTime.of(6, 0), "ACTIVE", 1)));
        when(ids.next()).thenReturn("01K2A000000000000000000131", "01K2A000000000000000000132",
            "01K2A000000000000000000133");
        when(secrets.next()).thenReturn("synthetic-activation-secret-1234567890");

        var result = service.issue(new IssueActivationCommand(1001L, 1101L, 201L,
            "ANDROID_POS_V1", 600, "terminal-issue-command-0001"));

        assertThat(result.activationSecret()).isEqualTo("synthetic-activation-secret-1234567890");
        ArgumentCaptor<TerminalRegistryPort.ActivationWrite> write =
            ArgumentCaptor.forClass(TerminalRegistryPort.ActivationWrite.class);
        verify(port).insertActivation(write.capture());
        assertThat(write.getValue().secretHmac()).matches("^[a-f0-9]{64}$")
            .doesNotContain(result.activationSecret());
        assertThat(write.getValue().tenantId()).isEqualTo("TENANT_A");
        assertThat(write.getValue().evidenceLevel()).isEqualTo("SYNTHETIC");
    }

    @Test
    void activatesAtomicallyFromServerOwnedBindingAndReturnsCredentialOnce() {
        String activationId = "01K2A000000000000000000141";
        String activationSecret = "synthetic-activation-secret-1234567890";
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(port.lockActivationById(activationId)).thenReturn(new ActivationRecord(activationId, "TENANT_A",
            1001L, 1101L, 201L, "ANDROID_POS_V1", protector.digest("activation:" + activationId, activationSecret),
            "ISSUED", now.plusMinutes(10), null, "terminal-activate-command-01", "a".repeat(64),
            "SYNTHETIC", 101L, now, 1L));
        when(port.consumeActivation(activationId, "01K2A000000000000000000142", 1L, now)).thenReturn(1);
        when(ids.next()).thenReturn("01K2A000000000000000000142", "01K2A000000000000000000143",
            "01K2A000000000000000000144", "01K2A000000000000000000145",
            "01K2A000000000000000000146", "01K2A000000000000000000147");
        when(secrets.next()).thenReturn("synthetic-device-credential-123456789012");
        var command = new ActivateTerminalCommand(activationId, activationSecret, "b".repeat(64), "c".repeat(64),
            "1.0.0", "1.0", "1", Map.of("scanner", true), "terminal-activate-command-01", NOW);

        var result = service.activate(command);

        assertThat(result.tenantId()).isEqualTo("TENANT_A");
        assertThat(result.storeId()).isEqualTo(1101L);
        assertThat(result.deviceCredential()).isEqualTo("synthetic-device-credential-123456789012");
        ArgumentCaptor<TerminalRegistryPort.CredentialWrite> credential =
            ArgumentCaptor.forClass(TerminalRegistryPort.CredentialWrite.class);
        verify(port).insertCredential(credential.capture());
        assertThat(credential.getValue().secretHmac()).matches("^[a-f0-9]{64}$")
            .doesNotContain(result.deviceCredential());
        verify(port).insertDevice(any());
        verify(port).insertCapability(any());
        verify(port).insertCommand(any());
        verify(port).insertAudit(any());
    }

    @Test
    void tenantWideListRequiresAdministratorScope() {
        when(context.requireTenantId()).thenReturn("TENANT_A");

        var result = service.list(null, 1, 50);

        verify(authorization).requireTenantAdministrator();
        assertThat(result.total()).isZero();
    }

    @Test
    void credentialRotationReplayDoesNotReturnSecretAgain() {
        String deviceId = "01K2A000000000000000000148";
        String key = "terminal-rotate-command-0001";
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "admin"));
        when(port.lockDevice("TENANT_A", deviceId)).thenReturn(new TerminalView(deviceId, 1001L, 1101L,
            deviceId, 201L, "ACTIVE", "ANDROID_POS_V1", "1.0.0", "1.0", "1.0", "1",
            "a".repeat(64), 2L, "SYNTHETIC", 3L, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null));
        String requestHash = TerminalHash.digest(new ObjectMapper(), Map.of("deviceId", deviceId,
            "action", "ROTATE_CREDENTIAL"));
        when(port.findCommand("TENANT_A", "ROTATE_CREDENTIAL", key)).thenReturn(new StoredCommand(requestHash,
            deviceId, "2", "{}", "b".repeat(64)));

        var result = service.rotateCredential(deviceId, key);

        assertThat(result.credentialVersion()).isEqualTo(2L);
        assertThat(result.deviceCredential()).isNull();
        assertThat(result.secretShownOnce()).isFalse();
        verify(port, never()).findActiveCredential(any(), any());
    }
}
