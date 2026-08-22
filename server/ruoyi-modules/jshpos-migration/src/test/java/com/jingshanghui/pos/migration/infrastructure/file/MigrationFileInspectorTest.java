package com.jingshanghui.pos.migration.infrastructure.file;

import cn.idev.excel.FastExcel;
import com.jingshanghui.pos.migration.domain.MigrationRules;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationFileInspectorTest {
    private final MigrationFileInspector inspector=new MigrationFileInspector();

    @Test
    void parsesQuotedUtf8AndGb18030CsvWhilePreservingLeadingZeros() {
        byte[] utf8="skuCode,name,barcode\r\n001,\"可乐,大瓶\",000123\r\n".getBytes(StandardCharsets.UTF_8);
        var table=inspector.inspect("catalog.csv","UTF-8",utf8,MigrationRules.digest(utf8));
        assertThat(table.headers()).containsExactly("skuCode","name","barcode");
        assertThat(table.rows().get(0)).containsEntry("skuCode","001").containsEntry("barcode","000123")
            .containsEntry("name","可乐,大瓶");
        byte[] gb="code,name\n01,测试\n".getBytes(Charset.forName("GB18030"));
        assertThat(inspector.inspect("supplier.csv","GB18030",gb,MigrationRules.digest(gb)).rows()).hasSize(1);
    }

    @Test
    void parsesSafeXlsxAndRejectsUnsafeFileInputs() {
        byte[] xlsx=xlsx(List.of(List.of("code","name"),List.of("01","测试")));
        var table=inspector.inspect("supplier.xlsx","XLSX",xlsx,MigrationRules.digest(xlsx));
        assertThat(table.rows()).hasSize(1);
        assertThatThrownBy(() -> inspector.inspect("../bad.csv","UTF-8",new byte[]{1},MigrationRules.digest(new byte[]{1})))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-007");
        assertThatThrownBy(() -> inspector.inspect("bad.exe","UTF-8",new byte[]{1},MigrationRules.digest(new byte[]{1})))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-003");
        assertThatThrownBy(() -> inspector.inspect("bad.xlsx","XLSX","not zip".getBytes(),
            MigrationRules.digest("not zip"))).isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsShaEncodingHeadersFormulasAndMalformedCsv() {
        byte[] csv="a,a\n1,2\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspector.inspect("x.csv","UTF-8",csv,"0".repeat(64)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-002");
        assertThatThrownBy(() -> inspector.inspect("x.csv","UTF-16",csv,MigrationRules.digest(csv)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-008");
        assertThatThrownBy(() -> inspector.inspect("x.csv","UTF-8",csv,MigrationRules.digest(csv)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-MAPPING-002");
        byte[] formula="code,name\n1,=cmd\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspector.inspect("x.csv","UTF-8",formula,MigrationRules.digest(formula)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-009");
        byte[] quotes="code,name\n1,\"bad\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> inspector.inspect("x.csv","UTF-8",quotes,MigrationRules.digest(quotes)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-011");
    }

    @Test
    void rejectsEmptyMalformedOversizedAndUnsafeCsvShapes() {
        assertThatThrownBy(() -> inspector.inspect("x.csv", "UTF-8", null, "0".repeat(64)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-001");
        assertThatThrownBy(() -> inspectCsv("x.csv", "code\n"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-004");
        assertThatThrownBy(() -> inspectCsv("x.csv", "code\n\n"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-004");
        assertThatThrownBy(() -> inspectCsv("x.csv", "a,b\n1,2,3\n"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-005");
        assertThatThrownBy(() -> inspectCsv("x.csv", "code\n" + "x".repeat(4_097) + "\n"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-FILE-006");
        assertThatThrownBy(() -> inspectCsv("x.csv", "1bad\nvalue\n"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-MAPPING-002");

        String tooManyHeaders = String.join(",", java.util.stream.IntStream.range(0, 201)
            .mapToObj(index -> "c" + index).toList()) + "\n";
        assertThatThrownBy(() -> inspectCsv("x.csv", tooManyHeaders + "value\n"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DMT-MAPPING-001");

        byte[] invalidUtf8 = new byte[]{'c', 'o', 'd', 'e', '\n', (byte) 0xC3, (byte) 0x28, '\n'};
        assertThatThrownBy(() -> inspector.inspect("x.csv", "UTF-8", invalidUtf8,
            MigrationRules.digest(invalidUtf8))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-FILE-010");
        assertThatThrownBy(() -> inspector.inspect("x.csv", "not-a-charset", new byte[]{1},
            MigrationRules.digest(new byte[]{1}))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-FILE-008");
    }

    @Test
    void handlesBomEscapedQuotesBlankCellsAndDefaultCharset() {
        byte[] csv = ("\uFEFFcode,name,remark\r\n001,\"可乐\"\"大瓶\",\r\n")
            .getBytes(StandardCharsets.UTF_8);
        var table = inspector.inspect("catalog.CSV", null, csv, MigrationRules.digest(csv));
        assertThat(table.rows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("code", "001");
            assertThat(row).containsEntry("name", "可乐\"大瓶");
            assertThat(row).containsEntry("remark", "");
        });

        assertThat(inspectCsv("plain.csv", "code,name\n1,plain").rows()).hasSize(1);
        assertThat(inspectCsv("quote.csv", "code,name\n1,\"at-end\"").rows().get(0))
            .containsEntry("name", "at-end");
        assertThat(inspectCsv("literal-quote.csv", "code,name\r1,a\"b\r").rows().get(0))
            .containsEntry("name", "a\"b");
        byte[] blankCharset = "code\n1\n".getBytes(StandardCharsets.UTF_8);
        assertThat(inspector.inspect("blank-charset.csv", " ", blankCharset,
            MigrationRules.digest(blankCharset)).rows()).hasSize(1);
    }

    @Test
    void rejectsEveryUnsafeFilenameForm() {
        List<String> unsafe = new ArrayList<>(List.of("", "   ", "../x.csv", "/x.csv", "a\\b.csv",
            "C:x.csv", "a\0b.csv", "items.csv.xlsx", "items.xlsx.csv", "x".repeat(181) + ".csv"));
        unsafe.add(null);
        for (String filename : unsafe) {
            assertThatThrownBy(() -> inspector.inspect(filename, "UTF-8", new byte[]{1},
                MigrationRules.digest(new byte[]{1}))).isInstanceOf(ServiceException.class)
                .hasMessageContaining("DMT-FILE-007");
        }
    }

    @Test
    void rejectsUnsafeXlsxEntriesAndFormulaContentBeforeParsing() {
        assertUnsafeXlsx("../evil.xml", "<root/>", "DMT-FILE-013");
        assertUnsafeXlsx("xl/vbaProject.bin", "macro", "DMT-FILE-013");
        assertUnsafeXlsx("xl/externalLinks/externalLink1.xml", "<root/>", "DMT-FILE-013");
        assertUnsafeXlsx("xl/worksheets/sheet1.xml", "<worksheet><f>1+1</f></worksheet>",
            "DMT-FILE-016");
        assertUnsafeXlsx("xl/worksheets/sheet1.xml", "<worksheet>DDEAUTO</worksheet>",
            "DMT-FILE-016");
        assertUnsafeXlsx("xl/workbook.xml", "<definedName>_xlnm.External</definedName>",
            "DMT-FILE-016");
    }

    @Test
    void rejectsXlsxWithTooManyEntries() {
        byte[] archive = zipEntries(2_001);
        assertThatThrownBy(() -> inspector.inspect("many.xlsx", "XLSX", archive,
            MigrationRules.digest(archive))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-FILE-013");
    }

    @Test
    void rejectsHighlyCompressedXlsxPayloadEvenWhenEntrySizeUsesDataDescriptor() {
        byte[] archive = zip("xl/media/repeated.bin", new byte[1024 * 1024]);
        assertThatThrownBy(() -> inspector.inspect("ratio.xlsx", "XLSX", archive,
            MigrationRules.digest(archive))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-FILE-015");
    }

    private MigrationFileInspector.InspectedTable inspectCsv(String filename, String text) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        return inspector.inspect(filename, "UTF-8", content, MigrationRules.digest(content));
    }

    private void assertUnsafeXlsx(String entryName, String body, String errorCode) {
        byte[] archive = zip(entryName, body.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> inspector.inspect("unsafe.xlsx", "XLSX", archive,
            MigrationRules.digest(archive))).isInstanceOf(ServiceException.class)
            .hasMessageContaining(errorCode);
    }

    private byte[] zip(String entryName, byte[] body) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(body);
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] zipEntries(int count) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (int index = 0; index < count; index++) {
                    zip.putNextEntry(new ZipEntry("safe/entry-" + index + ".bin"));
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] xlsx(List<List<String>> rows) {
        try {
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            FastExcel.write(output).sheet("data").doWrite(rows);
            return output.toByteArray();
        } catch (RuntimeException exception) { throw exception; }
    }
}
