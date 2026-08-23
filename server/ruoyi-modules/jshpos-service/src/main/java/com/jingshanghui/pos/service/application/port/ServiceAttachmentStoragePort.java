package com.jingshanghui.pos.service.application.port;

import java.time.Duration;

/** 受控附件正文存储端口；Service 数据库永远不保存正文或永久公开 URL。 */
public interface ServiceAttachmentStoragePort {
    /**
     * @param objectKey 服务端生成且带租户命名空间的对象键
     * @param content 附件正文字节
     * @param mediaType 经过白名单校验的媒体类型
     * @param sha256 附件正文 SHA-256
     */
    record StoreObject(String objectKey, byte[] content, String mediaType, String sha256) { }
    void store(StoreObject object);
    String temporaryDownload(String objectKey, Duration ttl);
    void delete(String objectKey);
}
