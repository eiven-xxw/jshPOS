package com.jingshanghui.pos.service.application.port;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

/** 受控附件流式暂存与对象存储端口；Service 数据库永远不保存正文或永久公开 URL。 */
public interface ServiceAttachmentStoragePort {
    /**
     * 已校验的临时附件句柄；关闭后必须回收临时文件，应用层不能取得本地路径。
     */
    interface StagedAttachment extends AutoCloseable {
        long sizeBytes();
        String sha256();
        InputStream openStream() throws IOException;
        @Override void close();
    }

    /**
     * @param objectKey 服务端生成且带租户命名空间的对象键
     * @param content 已限流、校验并可关闭的暂存附件
     * @param mediaType 经过白名单校验的媒体类型
     * @param sha256 附件正文 SHA-256
     */
    record StoreObject(String objectKey, StagedAttachment content, String mediaType, String sha256) { }

    /**
     * 以固定缓冲流式写入临时文件并计算摘要；声明量、实际量或上限不一致必须失败关闭。
     */
    StagedAttachment stage(InputStream source, long declaredSize, long maximumSize);
    void store(StoreObject object);
    String temporaryDownload(String objectKey, Duration ttl);
    void delete(String objectKey);
}
