package com.jingshanghui.pos.reporting.application.port;

import java.io.IOException;
import java.io.OutputStream;

/** 安全导出制品存储端口；对象键和断点命名空间只能由 Reporting 应用服务生成。 */
public interface ReportArtifactStore {
    void put(String objectKey, byte[] content);

    /**
     * 在受控命名空间内分块写入并原子发布制品。
     *
     * <p>实现必须把请求摘要、已确认字节偏移和游标作为同一恢复检查点保存；异常后重试只能从
     * 已确认偏移继续，未确认尾部必须截断，最终对象只有完整内容可见。</p>
     *
     * @param namespace 不含摘要和扩展名的 reporting/{tenant}/{exportId}
     * @param requestSha256 已持久化导出请求摘要
     * @param writer 分块内容写入器
     * @return 已原子发布的对象键、摘要和大小
     */
    StoredArtifact writeResumable(String namespace, String requestSha256, ResumableWriter writer);

    byte[] get(String objectKey);
    void delete(String objectKey);

    /** @param objectKey 最终对象键 @param sha256 完整制品摘要 @param sizeBytes 完整字节数 */
    record StoredArtifact(String objectKey, String sha256, long sizeBytes) {
    }

    /** 分块写入会话；resumeCursor 为上次成功确认的 Owner 游标。 */
    @FunctionalInterface
    interface ResumableWriter {
        void write(OutputStream output, String resumeCursor, Checkpoint checkpoint) throws IOException;
    }

    /** 写入器必须在每批内容完全 flush 后保存下一批游标。 */
    @FunctionalInterface
    interface Checkpoint {
        void saveCheckpoint(String resumeCursor) throws IOException;
    }
}
