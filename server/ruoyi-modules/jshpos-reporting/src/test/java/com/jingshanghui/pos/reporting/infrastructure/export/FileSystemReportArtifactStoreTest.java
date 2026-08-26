package com.jingshanghui.pos.reporting.infrastructure.export;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test void resumesFromConfirmedCursorAndTruncatesUnconfirmedTail() {
        var store=new FileSystemReportArtifactStore(root);
        String namespace="reporting/tenant_alpha/01ARZ3NDEKTSV4RRFFQ69G5FAV";
        String requestSha256="a".repeat(64);
        assertThatThrownBy(() -> store.writeResumable(namespace,requestSha256,(output,cursor,checkpoint) -> {
            assertThat(cursor).isNull();
            output.write("header\none\n".getBytes(StandardCharsets.UTF_8));
            checkpoint.saveCheckpoint("cursor-one");
            output.write("unconfirmed\n".getBytes(StandardCharsets.UTF_8));
            throw new IOException("synthetic crash after chunk ack loss");
        })).isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-011");

        AtomicReference<String> observedCursor=new AtomicReference<>();
        var artifact=store.writeResumable(namespace,requestSha256,(output,cursor,checkpoint) -> {
            observedCursor.set(cursor);
            output.write("two\n".getBytes(StandardCharsets.UTF_8));
            checkpoint.saveCheckpoint("cursor-two");
        });
        assertThat(observedCursor).hasValue("cursor-one");
        assertThat(new String(store.get(artifact.objectKey()),StandardCharsets.UTF_8))
            .isEqualTo("header\none\ntwo\n");
        assertThat(artifact.sizeBytes()).isEqualTo("header\none\ntwo\n".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test void sameResumeIdentityWithDifferentRequestHashFailsClosed() {
        var store=new FileSystemReportArtifactStore(root);
        String namespace="reporting/tenant_alpha/01ARZ3NDEKTSV4RRFFQ69G5FAW";
        assertThatThrownBy(() -> store.writeResumable(namespace,"a".repeat(64),(output,cursor,checkpoint) -> {
            output.write("confirmed\n".getBytes(StandardCharsets.UTF_8));
            checkpoint.saveCheckpoint("cursor-one");
            throw new IOException("keep checkpoint");
        })).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> store.writeResumable(namespace,"b".repeat(64),(output,cursor,checkpoint) -> { }))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-009");
    }

    @Test void rejectsInvalidResumeIdentityAndCorruptCheckpoint() throws Exception {
        var store=new FileSystemReportArtifactStore(root);
        assertThatThrownBy(() -> store.writeResumable("../tenant","a".repeat(64),(output,cursor,checkpoint) -> { }))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-009");
        assertThatThrownBy(() -> store.writeResumable(
            "reporting/tenant_alpha/01ARZ3NDEKTSV4RRFFQ69G5FAX","not-a-sha",(output,cursor,checkpoint) -> { }))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-009");

        String namespace="reporting/tenant_alpha/01ARZ3NDEKTSV4RRFFQ69G5FAY";
        Path directory=root.resolve(namespace);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(".stream.part"),"partial",StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".stream.checkpoint"),
            "a".repeat(64)+System.lineSeparator()+"not-a-number"+System.lineSeparator()+"cursor-one",
            StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.writeResumable(namespace,"a".repeat(64),(output,cursor,checkpoint) -> { }))
            .isInstanceOf(ServiceException.class).hasMessageContaining("RPT-R2R2-012");
    }

    @Test void discardsUnconfirmedPartialWhenCheckpointIsMissing() throws Exception {
        var store=new FileSystemReportArtifactStore(root);
        String namespace="reporting/tenant_alpha/01ARZ3NDEKTSV4RRFFQ69G5FAZ";
        Path directory=root.resolve(namespace);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(".stream.part"),"unconfirmed",StandardCharsets.UTF_8);

        var artifact=store.writeResumable(namespace,"a".repeat(64),(output,cursor,checkpoint) -> {
            assertThat(cursor).isNull();
            output.write("confirmed".getBytes(StandardCharsets.UTF_8));
            checkpoint.saveCheckpoint(null);
        });
        assertThat(new String(store.get(artifact.objectKey()),StandardCharsets.UTF_8)).isEqualTo("confirmed");
    }
}
