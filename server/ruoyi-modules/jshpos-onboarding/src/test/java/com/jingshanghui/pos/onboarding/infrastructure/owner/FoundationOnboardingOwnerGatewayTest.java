package com.jingshanghui.pos.onboarding.infrastructure.owner;

import com.jingshanghui.pos.catalog.application.port.StoreOnboardingCatalogPort;
import com.jingshanghui.pos.catalog.application.port.StoreOnboardingCatalogPort.CatalogReadiness;
import com.jingshanghui.pos.foundation.application.port.StoreOnboardingPort;
import com.jingshanghui.pos.foundation.application.port.StoreOnboardingPort.*;
import com.jingshanghui.pos.inventory.application.port.StoreOnboardingInventoryPort;
import com.jingshanghui.pos.inventory.application.port.StoreOnboardingInventoryPort.InventoryReadiness;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.*;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckStatus;
import com.jingshanghui.pos.order.application.port.StoreOnboardingShiftPort;
import com.jingshanghui.pos.order.application.port.StoreOnboardingShiftPort.ShiftReadiness;
import com.jingshanghui.pos.resilience.application.port.StoreOnboardingBackupPort;
import com.jingshanghui.pos.resilience.application.port.StoreOnboardingBackupPort.BackupReadiness;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FoundationOnboardingOwnerGatewayTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void combinesAuthoritativeInternalFactsAndKeepsExternalP0Blocked() {
        StoreOnboardingPort foundation = mock(StoreOnboardingPort.class);
        StoreOnboardingCatalogPort catalog = mock(StoreOnboardingCatalogPort.class);
        StoreOnboardingInventoryPort inventory = mock(StoreOnboardingInventoryPort.class);
        StoreOnboardingShiftPort shifts = mock(StoreOnboardingShiftPort.class);
        StoreOnboardingBackupPort backups = mock(StoreOnboardingBackupPort.class);
        when(foundation.readiness(20L)).thenReturn(new FoundationReadiness(20L, 2, HASH));
        when(catalog.readiness(20L)).thenReturn(new CatalogReadiness(20L, 10, 10, 4L, HASH, HASH));
        when(inventory.readiness(20L)).thenReturn(new InventoryReadiness(20L, 1, HASH));
        when(shifts.readiness(20L)).thenReturn(new ShiftReadiness(20L, 0, HASH));
        when(backups.readiness()).thenReturn(new BackupReadiness("01K3M000000000000000000090",
            "01K3M000000000000000000091", Instant.parse("2026-08-23T00:00:00Z"), 100, 500, HASH));
        FoundationOnboardingOwnerGateway gateway = new FoundationOnboardingOwnerGateway(
            foundation, catalog, inventory, shifts, backups);

        var facts = gateway.checks(plan(), 1);

        assertThat(facts).hasSize(14);
        assertThat(facts.stream().filter(f -> !f.external())).allMatch(f -> f.status() == CheckStatus.PASS);
        assertThat(facts.stream().filter(CheckFact::external)).allMatch(f -> f.status() == CheckStatus.BLOCKED);
        assertThat(facts).extracting(CheckFact::code).contains("DMT_RECONCILED", "BACKUP_RECOVERY");
    }

    @Test
    void exposesFailuresAndUnavailableBackupWithoutGreenPlaceholder() {
        StoreOnboardingPort foundation = mock(StoreOnboardingPort.class);
        StoreOnboardingCatalogPort catalog = mock(StoreOnboardingCatalogPort.class);
        StoreOnboardingInventoryPort inventory = mock(StoreOnboardingInventoryPort.class);
        StoreOnboardingShiftPort shifts = mock(StoreOnboardingShiftPort.class);
        StoreOnboardingBackupPort backups = mock(StoreOnboardingBackupPort.class);
        when(foundation.readiness(20L)).thenReturn(new FoundationReadiness(20L, 0, HASH));
        when(catalog.readiness(20L)).thenReturn(new CatalogReadiness(20L, 10, 8, null, null, HASH));
        when(inventory.readiness(20L)).thenReturn(new InventoryReadiness(20L, 0, HASH));
        when(shifts.readiness(20L)).thenReturn(new ShiftReadiness(20L, 1, HASH));
        when(backups.readiness()).thenReturn(null);
        var gateway = new FoundationOnboardingOwnerGateway(foundation, catalog, inventory, shifts, backups);

        var facts = gateway.checks(plan(), 2);

        assertThat(facts.stream().filter(f -> !f.external() && f.status() != CheckStatus.PASS).count()).isEqualTo(6);
        assertThat(facts).anyMatch(f -> f.code().equals("BACKUP_RECOVERY") && f.status() == CheckStatus.UNAVAILABLE);
        assertThat(facts).anyMatch(f -> f.code().equals("DATA_PACKAGE") && f.status() == CheckStatus.FAIL);
    }

    @Test
    void delegatesCaptureApplyAndOpenThroughFormalFoundationPort() {
        StoreOnboardingPort foundation = mock(StoreOnboardingPort.class);
        var gateway = new FoundationOnboardingOwnerGateway(foundation, mock(StoreOnboardingCatalogPort.class),
            mock(StoreOnboardingInventoryPort.class), mock(StoreOnboardingShiftPort.class),
            mock(StoreOnboardingBackupPort.class));
        when(foundation.capture(any())).thenReturn(new FoundationSnapshot(10L, 1, 20L, 2, 30L, 40L, 3,
            HASH, "CONVENIENCE", Map.of("ui.layout", "compact")));
        when(foundation.apply(any())).thenReturn(new AppliedBinding(1L, 20L, 40L, 1, HASH));
        when(foundation.open(any())).thenReturn(new OpenedStore(20L, "ACTIVE", 3));

        assertThat(gateway.capture(10L, 20L, 30L, 40L).industry()).isEqualTo("CONVENIENCE");
        assertThat(gateway.apply(plan()).resultSha256()).isEqualTo(HASH);
        assertThat(gateway.open(plan(), "全部检查通过").status()).isEqualTo("ACTIVE");
    }

    private static PlanRecord plan() {
        return new PlanRecord("01K3M000000000000000000001", "TENANT_A", 10L, 20L, 30L, 40L,
            1, 2, 3, HASH, "CONVENIENCE", HASH, "APPLIED", "onb-create-001", HASH,
            101L, 0, 1, LocalDateTime.MIN, LocalDateTime.MIN);
    }
}
