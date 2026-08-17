package com.jingshanghui.pos.reporting.infrastructure.export;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class FileSystemReportArtifactStoreTest {
    @TempDir Path root;
    @Test void writesReadsReplacesAndDeletesOnlySafeTenantKey() {
        var store = new FileSystemReportArtifactStore(root);
        String key="reporting/tenant_alpha/01ARZ3NDEKTSV4RRFFQ69G5FAV/"+"a".repeat(64)+".csv";
        store.put(key,"first".getBytes());
        assertThat(store.get(key)).isEqualTo("first".getBytes());
        store.put(key,"second".getBytes());
        assertThat(store.get(key)).isEqualTo("second".getBytes());
        store.delete(key);
        assertThatThrownBy(() -> store.get(key)).isInstanceOf(ServiceException.class);
    }
    @Test void rejectsTraversalBackslashAndMalformedKeys() {
        var store = new FileSystemReportArtifactStore(root);
        assertThatThrownBy(() -> store.put("../x",new byte[0])).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> store.put("reporting\\tenant\\x",new byte[0])).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> store.delete(null)).isInstanceOf(ServiceException.class);
    }
    @Test void rejectingStoreFailsClosed() {
        var store=new RejectingReportArtifactStore();
        assertThatThrownBy(() -> store.put("x",new byte[0])).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> store.get("x")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> store.delete("x")).isInstanceOf(ServiceException.class);
    }
}
