package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.sync.application.model.SyncModels.AckCommand;
import com.jingshanghui.pos.sync.application.model.SyncModels.CursorRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.PullPageRecord;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PosSyncServiceCursorTest {

    private static final String DEVICE = "01K2A000000000000000000011";
    private static final String CURSOR = "01K2A000000000000000000091";
    private static final String HASH = "a".repeat(64);
    private final DeviceContext context = new DeviceContext("TENANT_A", DEVICE, 1101L, DEVICE, 101L, "1.0");

    @Test
    void pullRefusesAnOfferedButUnacknowledgedCursor() {
        SyncMapper mapper = mock(SyncMapper.class);
        when(mapper.findPullPage("TENANT_A", DEVICE, CURSOR)).thenReturn(page("OFFERED", 0L, 10L));

        assertThatThrownBy(() -> service(mapper).pull(DEVICE, "sync.control", CURSOR, 100))
            .isInstanceOf(ServiceException.class).hasMessageContaining("SYNC_CURSOR_INVALID");
        verify(mapper, never()).findChanges("TENANT_A", "sync.control", 10L, 100);
    }

    @Test
    void acknowledgeRefusesCursorRegressionBeforeWriting() {
        SyncMapper mapper = mock(SyncMapper.class);
        when(mapper.findPullPage("TENANT_A", DEVICE, CURSOR)).thenReturn(page("OFFERED", 0L, 9L));
        when(mapper.lockCursor("TENANT_A", DEVICE, "sync.control"))
            .thenReturn(new CursorRecord("sync.control", 10L, "01K2A000000000000000000090", HASH));
        AckCommand command = new AckCommand("sync.control", CURSOR, List.of(), HASH);

        assertThatThrownBy(() -> service(mapper).acknowledge(DEVICE, command))
            .isInstanceOf(ServiceException.class).hasMessageContaining("SYNC_CURSOR_REGRESSION");
        verify(mapper, never()).upsertCursor("TENANT_A", DEVICE, "sync.control", 9L, CURSOR, HASH);
    }

    private PosSyncService service(SyncMapper mapper) {
        SyncDeviceContextService contexts = mock(SyncDeviceContextService.class);
        when(contexts.require(DEVICE, "1.0")).thenReturn(context);
        return new PosSyncService(contexts, mock(SyncInboxReceiver.class), mock(SyncFactProcessor.class),
            mock(SyncFailureRecorder.class), mapper, new ObjectMapper(), mock(SyncIdGenerator.class),
            Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"), ZoneOffset.UTC));
    }

    private PullPageRecord page(String status, long from, long to) {
        return new PullPageRecord(CURSOR, "TENANT_A", DEVICE, "sync.control", from, to, "[]", HASH, status);
    }
}
