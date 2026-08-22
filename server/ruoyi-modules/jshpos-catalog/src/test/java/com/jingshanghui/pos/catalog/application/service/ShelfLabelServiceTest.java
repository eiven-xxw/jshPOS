package com.jingshanghui.pos.catalog.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.CreateTemplateCommand;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.PriceBookEvent;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskItemView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TaskView;
import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.TemplateView;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelPrintPort;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.GeneratedItem;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.GeneratedTask;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredCommand;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredTask;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.StoredTemplateCommand;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelSourcePort;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelSourcePort.PriceSource;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelStorePort;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelStorePort.StoreSnapshot;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShelfLabelServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private ShelfLabelRepository repository;
    private ShelfLabelSourcePort sources;
    private ShelfLabelStorePort stores;
    private ShelfLabelPrintPort printer;
    private TrustedTenantContext tenant;
    private ShelfLabelService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShelfLabelRepository.class);
        sources = mock(ShelfLabelSourcePort.class);
        stores = mock(ShelfLabelStorePort.class);
        printer = mock(ShelfLabelPrintPort.class);
        tenant = mock(TrustedTenantContext.class);
        when(tenant.requireTenantId()).thenReturn("TENANT_A");
        when(tenant.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 10L, "alice"));
        service = new ShelfLabelService(repository, sources, stores, printer, tenant,
            mock(DomainAuditService.class), Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void createsIdempotentSafeTemplateDraft() {
        var command = new CreateTemplateCommand("SHELF-A", "便利店价签", 1, "TENANT", null,
            "{{productName}} {{newPrice}}", "lbl-template-001", "corr-template-001");
        when(repository.findTemplate(eq("TENANT_A"), anyLong())).thenAnswer(invocation ->
            new TemplateView(invocation.getArgument(1), "SHELF-A", "便利店价签", 1, "TENANT", null,
                "{{productName}} {{newPrice}}", "DRAFT", null, null, 0));

        TemplateView created = service.createTemplate(command);

        assertThat(created.templateCode()).isEqualTo("SHELF-A");
        verify(repository).insertTemplate(any());
        ArgumentCaptor<com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.TemplateDraft> captor =
            ArgumentCaptor.forClass(com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.TemplateDraft.class);
        verify(repository).insertTemplate(captor.capture());
        assertThat(captor.getValue().requestSha256()).matches("[a-f0-9]{64}");

        when(repository.findTemplateCommand("TENANT_A", "lbl-template-001"))
            .thenReturn(new StoredTemplateCommand(created.templateId(), captor.getValue().requestSha256()));
        assertThat(service.createTemplate(command).templateId()).isEqualTo(created.templateId());
    }

    @Test
    void publishesTemplateIdempotentlyAndFreezesContentHash() {
        TemplateView draft = new TemplateView(71L, "DEFAULT", "默认价签", 1, "TENANT", null,
            "{{productName}} {{newPrice}}", "DRAFT", null, null, 0);
        TemplateView published = new TemplateView(71L, "DEFAULT", "默认价签", 1, "TENANT", null,
            draft.bodyTemplate(), "PUBLISHED", com.jingshanghui.pos.catalog.domain.ShelfLabelRules.sha256(draft.bodyTemplate()), NOW, 1);
        when(repository.findTemplate("TENANT_A", 71L)).thenReturn(draft, published, published);
        when(repository.publishTemplate(eq("TENANT_A"), eq(71L), eq(0), any(), eq(NOW))).thenReturn(1);

        TemplateView result = service.publishTemplate(71L, 0, "lbl-template-publish-001", "corr-template-publish-001");

        assertThat(result.state()).isEqualTo("PUBLISHED");
        verify(repository).appendEvent(any());
        ArgumentCaptor<com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LabelEvent> event =
            ArgumentCaptor.forClass(com.jingshanghui.pos.catalog.application.port.ShelfLabelRepository.LabelEvent.class);
        verify(repository).appendEvent(event.capture());
        when(repository.findCommand("TENANT_A", "lbl-template-publish-001"))
            .thenReturn(new StoredCommand(event.getValue().eventType(), event.getValue().commandSha256(), null, null));
        assertThat(service.publishTemplate(71L, 0, "lbl-template-publish-001", "corr-template-publish-001").state())
            .isEqualTo("PUBLISHED");
    }

    @Test
    void generatesPerStoreTasksAndFreezesFirstAndChangedPrices() {
        PriceBookEvent event = new PriceBookEvent("PRICE_BOOK_PUBLISHED", 900L, 2, "TENANT_BASE", null,
            "a".repeat(64), NOW);
        when(stores.listAccessibleActiveStores()).thenReturn(List.of(
            new StoreSnapshot(11L, "A11", "虚构便利店"), new StoreSnapshot(12L, "A12", "虚构社区超市")));
        when(sources.listPriceSources("TENANT_A", 900L)).thenReturn(List.of(
            new PriceSource(901L, 701L, "SKU-701", "合成牛奶", 301L, "瓶", "0012345678905",
                990L, "CNY", NOW.plusSeconds(3600), null)));
        when(sources.resolveAmount(eq("TENANT_A"), eq(701L), eq(301L), eq(11L), any(), eq(900L))).thenReturn(890L);

        List<Long> tasks = service.handle(event);

        assertThat(tasks).hasSize(2).doesNotHaveDuplicates();
        ArgumentCaptor<GeneratedTask> taskCaptor = ArgumentCaptor.forClass(GeneratedTask.class);
        verify(repository, org.mockito.Mockito.times(2)).insertTask(taskCaptor.capture());
        assertThat(taskCaptor.getAllValues()).extracting(GeneratedTask::storeId).containsExactly(11L, 12L);
        ArgumentCaptor<GeneratedItem> itemCaptor = ArgumentCaptor.forClass(GeneratedItem.class);
        verify(repository, org.mockito.Mockito.times(2)).insertItem(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues().get(0).oldPriceMinor()).isEqualTo(890L);
        assertThat(itemCaptor.getAllValues().get(0).newPriceMinor()).isEqualTo(990L);
        assertThat(itemCaptor.getAllValues()).allMatch(item -> item.snapshotSha256().matches("[a-f0-9]{64}"));
    }

    @Test
    void refreshesPriorTaskProjectionWhenNewerPriceSupersedesOpenItem() {
        PriceBookEvent event = new PriceBookEvent("PRICE_BOOK_PUBLISHED", 900L, 3, "STORE", 11L,
            "a".repeat(64), NOW);
        when(stores.requireAccessibleStore(11L)).thenReturn(new StoreSnapshot(11L, "A11", "虚构便利店"));
        when(sources.listPriceSources("TENANT_A", 900L)).thenReturn(List.of(
            new PriceSource(901L, 701L, "SKU", "商品", 301L, "件", null, 120L, "CNY", NOW, null)));
        when(repository.findOpenTaskIds("TENANT_A", 11L, 701L, 301L)).thenReturn(List.of(31L));

        List<Long> generated = service.handle(event);

        assertThat(generated).hasSize(1);
        verify(repository).refreshTaskProjection("TENANT_A", 31L, NOW);
    }

    @Test
    void replaysSameSourceAndRejectsChangedContent() {
        PriceBookEvent event = new PriceBookEvent("PRICE_BOOK_PUBLISHED", 900L, 2, "STORE", 11L,
            "a".repeat(64), NOW);
        when(stores.requireAccessibleStore(11L)).thenReturn(new StoreSnapshot(11L, "A11", "虚构便利店"));
        when(sources.listPriceSources("TENANT_A", 900L)).thenReturn(List.of(
            new PriceSource(901L, 701L, "SKU", "商品", 301L, "件", null, 100L, "CNY", NOW, null)));
        String hash = com.jingshanghui.pos.catalog.domain.ShelfLabelRules.sha256(
            "PRICE_BOOK_PUBLISHED|900|2|" + "a".repeat(64) + "|11");
        when(repository.findTaskBySource("TENANT_A", "price-book.published.v1:900:2:11"))
            .thenReturn(new StoredTask(999L, hash));
        assertThat(service.handle(event)).containsExactly(999L);
        verify(repository, never()).insertTask(any());

        when(repository.findTaskBySource("TENANT_A", "price-book.published.v1:900:2:11"))
            .thenReturn(new StoredTask(999L, "b".repeat(64)));
        assertThatThrownBy(() -> service.handle(event)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("IDEMPOTENCY");
    }

    @Test
    void previewsPlainTextThenConfirmsReplacementWithoutPrinterClaim() {
        TaskItemView pending = item("PENDING", 0, null);
        TaskItemView ready = item("PREVIEW_READY", 1, null);
        TemplateView template = new TemplateView(71L, "DEFAULT", "默认价签", 3, "TENANT", null,
            "{{productName}}\n{{barcode}}\n原价 {{oldPrice}} 新价 {{newPrice}}\n{{storeName}} {{taskStatus}}",
            "PUBLISHED", "c".repeat(64), NOW, 1);
        when(repository.findTaskItem("TENANT_A", 81L)).thenReturn(pending, ready, ready);
        when(repository.findTemplate("TENANT_A", 71L)).thenReturn(template);
        when(repository.transitionItem(eq("TENANT_A"), eq(81L), eq("PENDING"), eq(0),
            eq("PREVIEW_READY"), eq(null), any())).thenReturn(1);

        var preview = service.preview(81L, 71L, "lbl-preview-001", "corr-preview-001");

        assertThat(preview.renderedText()).contains("合成牛奶", "0012345678905", "8.90", "9.90", "虚构便利店");
        assertThat(preview.previewSha256()).matches("[a-f0-9]{64}");
        verify(repository).appendEvent(any());
        verify(printer, never()).dispatch(any(), any());
    }

    @Test
    void dispatchAlwaysFailsClosedAndCreatesAuditableException() {
        TaskView task = new TaskView(41L, "price-book.published.v1:1:1:11", "PRICE_BOOK_PUBLISHED",
            1L, 1, 11L, "虚构便利店", NOW, "PREVIEW_READY", 1, 1, 0, NOW, 2);
        when(repository.findTask("TENANT_A", 41L)).thenReturn(task,
            new TaskView(41L, task.sourceEventKey(), task.sourceEventType(), 1L, 1, 11L, task.storeName(),
                NOW, "DISPATCH_BLOCKED", 1, 1, 1, NOW, 3));
        when(printer.dispatch(41L, "d".repeat(64))).thenReturn(
            new ShelfLabelPrintPort.DispatchResult(false, "PRINTER_UNAVAILABLE", "真实打印未解阻"));
        when(repository.markTaskDispatchBlocked("TENANT_A", 41L, 2, NOW)).thenReturn(1);

        TaskView result = service.dispatch(41L, "d".repeat(64), 2,
            "lbl-dispatch-001", "corr-dispatch-001");

        assertThat(result.state()).isEqualTo("DISPATCH_BLOCKED");
        verify(repository).appendException(any());
        verify(repository).appendEvent(any());
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentCommand() {
        TaskItemView ready = item("PREVIEW_READY", 1, null);
        when(repository.findTaskItem("TENANT_A", 81L)).thenReturn(ready);
        when(repository.findCommand("TENANT_A", "lbl-confirm-001"))
            .thenReturn(new StoredCommand("SHELF_LABEL_REPLACEMENT_CONFIRMED", "e".repeat(64), 41L, 81L));
        assertThatThrownBy(() -> service.confirmReplacement(81L, 1, "已换签",
            "lbl-confirm-001", "corr-confirm-001")).isInstanceOf(ServiceException.class)
            .hasMessageContaining("IDEMPOTENCY");
    }

    private TaskItemView item(String state, int version, String exception) {
        when(stores.requireAccessibleStore(11L)).thenReturn(new StoreSnapshot(11L, "A11", "虚构便利店"));
        return new TaskItemView(81L, 41L, 11L, "虚构便利店", 701L, "SKU-701", "合成牛奶",
            301L, "瓶", "0012345678905", 890L, 990L, "CNY", 2, NOW.plusSeconds(3600),
            state, exception, version);
    }
}
