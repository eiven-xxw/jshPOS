package com.jingshanghui.pos.migration.infrastructure.file;

import cn.idev.excel.FastExcel;
import com.jingshanghui.pos.migration.domain.MigrationRules;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** CSV/XLSX 原文件的请求内安全检查与表格解析器；解析后不持久化原始字节。 */
@Component
public class MigrationFileInspector {
    private static final long MAX_UNCOMPRESSED = 256L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2_000;
    private static final int MAX_RATIO = 200;

    public InspectedTable inspect(String filename, String charsetName, byte[] content, String declaredSha256) {
        String safeName = safeFilename(filename);
        if (content == null || content.length == 0 || content.length > MigrationRules.MAX_FILE_BYTES) {
            throw new ServiceException("DMT-FILE-001: 文件为空或超过 64 MiB", 400);
        }
        String actualSha = MigrationRules.digest(content);
        if (!actualSha.equals(MigrationRules.sha256(declaredSha256, "declaredSha256"))) {
            throw new ServiceException("DMT-FILE-002: 文件摘要与声明不一致", 409);
        }
        List<List<String>> rows;
        if (safeName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            rows = parseCsv(content, charset(charsetName));
        } else if (safeName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            inspectXlsxZip(content);
            rows = parseXlsx(content);
        } else {
            throw new ServiceException("DMT-FILE-003: 只允许 CSV 或无宏 XLSX", 400);
        }
        if (rows.size() < 2 || rows.size() - 1 > MigrationRules.MAX_ROWS) {
            throw new ServiceException("DMT-FILE-004: 文件必须包含表头及 1..100000 行数据", 400);
        }
        List<String> headers = normalizedHeaders(rows.get(0));
        List<Map<String, String>> mapped = new ArrayList<>(rows.size() - 1);
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> raw = rows.get(rowIndex);
            if (raw.size() > headers.size()) throw new ServiceException("DMT-FILE-005: 数据列超过表头", 400);
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            boolean nonEmpty = false;
            for (int column = 0; column < headers.size(); column++) {
                String value = column < raw.size() && raw.get(column) != null ? raw.get(column).strip() : "";
                if (value.length() > MigrationRules.MAX_CELL_CHARS) {
                    throw new ServiceException("DMT-FILE-006: 单元格超过 4096 字符", 400);
                }
                MigrationRules.rejectFormula(value, "row=" + (rowIndex + 1) + ",column=" + headers.get(column));
                nonEmpty |= !value.isEmpty();
                values.put(headers.get(column), value);
            }
            if (nonEmpty) mapped.add(Map.copyOf(values));
        }
        if (mapped.isEmpty()) throw new ServiceException("DMT-FILE-004: 文件没有有效数据行", 400);
        return new InspectedTable(safeName, actualSha, headers, List.copyOf(mapped));
    }

    private String safeFilename(String value) {
        String name = value == null ? "" : value.strip();
        String lower = name.toLowerCase(Locale.ROOT);
        int extension = lower.lastIndexOf('.');
        String stem = extension < 0 ? lower : lower.substring(0, extension);
        if (name.isEmpty() || name.length() > 180 || name.contains("..") || name.contains("/")
            || name.contains("\\") || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")
            || stem.endsWith(".csv") || stem.endsWith(".xlsx")) {
            throw new ServiceException("DMT-FILE-007: 文件名不安全", 400);
        }
        return name;
    }

    private Charset charset(String name) {
        try {
            Charset charset = Charset.forName(name == null || name.isBlank() ? "UTF-8" : name);
            if (!Set.of("UTF-8", "GB18030").contains(charset.name().toUpperCase(Locale.ROOT))) {
                throw new ServiceException("DMT-FILE-008: CSV 只允许 UTF-8 或 GB18030", 400);
            }
            return charset;
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("DMT-FILE-008: CSV 字符集无效", 400);
        }
    }

    private List<List<String>> parseCsv(byte[] content, Charset charset) {
        String text;
        try {
            text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new ServiceException("DMT-FILE-010: CSV 包含非法编码", 400);
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (quoted) {
                if (current == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"'); i++;
                } else if (current == '"') quoted = false;
                else cell.append(current);
            } else if (current == '"' && cell.isEmpty()) quoted = true;
            else if (current == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (current == '\n' || current == '\r') {
                if (current == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); rows.add(List.copyOf(row)); row.clear();
                if (rows.size() > MigrationRules.MAX_ROWS + 1) {
                    throw new ServiceException("DMT-FILE-004: CSV 行数超过上限", 400);
                }
            } else cell.append(current);
        }
        if (quoted) throw new ServiceException("DMT-FILE-011: CSV 引号未闭合", 400);
        if (!cell.isEmpty() || !row.isEmpty()) { row.add(cell.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> parseXlsx(byte[] content) {
        try {
            List<Map<Integer, String>> data = (List<Map<Integer, String>>) (List<?>) FastExcel
                .read(new ByteArrayInputStream(content)).headRowNumber(0).autoCloseStream(true)
                .sheet().doReadSync();
            List<List<String>> rows = new ArrayList<>(data.size());
            for (Map<Integer, String> row : data) {
                int max = row.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
                List<String> cells = new ArrayList<>(max + 1);
                for (int index = 0; index <= max; index++) cells.add(row.getOrDefault(index, ""));
                rows.add(List.copyOf(cells));
            }
            return rows;
        } catch (RuntimeException exception) {
            throw new ServiceException("DMT-FILE-012: XLSX 解析失败或包含不支持内容", 400);
        }
    }

    private void inspectXlsxZip(byte[] content) {
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipArchiveInputStream input = new ZipArchiveInputStream(new ByteArrayInputStream(content),
            StandardCharsets.UTF_8.name(), true, true)) {
            ZipArchiveEntry entry;
            while ((entry = input.getNextZipEntry()) != null) {
                entries++;
                String name = entry.getName();
                if (entries > MAX_ZIP_ENTRIES || name.startsWith("/") || name.contains("..")
                    || name.endsWith("vbaProject.bin") || name.contains("externalLinks")
                    || entry.isUnixSymlink() || entry.getGeneralPurposeBit().usesEncryption()) {
                    throw new ServiceException("DMT-FILE-013: XLSX 压缩内容不安全", 400);
                }
                long entrySize = 0;
                long compressedBefore = input.getCompressedCount();
                String xmlTail = "";
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    entrySize += read; total += read;
                    if (entrySize > MAX_UNCOMPRESSED || total > MAX_UNCOMPRESSED) {
                        throw new ServiceException("DMT-FILE-014: XLSX 解压体积超过上限", 400);
                    }
                    if (name.endsWith(".xml") && read > 0) {
                        String chunk = xmlTail + new String(buffer, 0, read, StandardCharsets.ISO_8859_1)
                            .replace("\0", "");
                        if (unsafeXml(chunk)) {
                            throw new ServiceException("DMT-FILE-016: XLSX 禁止公式或外部链接", 400);
                        }
                        xmlTail = chunk.substring(Math.max(0, chunk.length() - 64));
                    }
                }
                long compressedByStream = Math.max(0, input.getCompressedCount() - compressedBefore);
                long compressed = entry.getCompressedSize() > 0 ? entry.getCompressedSize() : compressedByStream;
                if (compressed > 0 && entrySize / Math.max(1, compressed) > MAX_RATIO) {
                    throw new ServiceException("DMT-FILE-015: XLSX 压缩比异常", 400);
                }
            }
        } catch (IOException exception) {
            throw new ServiceException("DMT-FILE-017: XLSX 压缩包损坏", 400);
        }
    }

    private boolean unsafeXml(String value) {
        return java.util.regex.Pattern.compile("<f(?:\\s|>)").matcher(value).find()
            || value.contains("DDEAUTO") || value.contains("_xlnm.External");
    }

    private List<String> normalizedHeaders(List<String> raw) {
        if (raw.isEmpty() || raw.size() > MigrationRules.MAX_COLUMNS) {
            throw new ServiceException("DMT-MAPPING-001: 表头为空或超过 200 列", 400);
        }
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>(raw.size());
        for (String value : raw) {
            String header = value == null ? "" : value.strip();
            if (!header.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$") || !seen.add(header)) {
                throw new ServiceException("DMT-MAPPING-002: 表头非法或重复", 400);
            }
            result.add(header);
        }
        return List.copyOf(result);
    }

    /**
     * 安全逻辑文件名、摘要、冻结表头和规范单元格。
     * @param safeFilename 已拒绝路径与重复扩展的逻辑文件名
     * @param sha256 原文件 SHA-256
     * @param headers 规范且唯一的字段名
     * @param rows 不包含空行的字符串单元格映射
     */
    public record InspectedTable(String safeFilename, String sha256,
                                 List<String> headers, List<Map<String, String>> rows) {
    }
}
