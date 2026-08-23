package com.jingshanghui.pos.service.infrastructure.storage;

import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.factory.OssFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.Duration;

/**
 * RuoYi S3 兼容对象存储适配器。对象键由 Service 应用层生成，失败时禁止回落到本地文件或数据库。
 */
@Component
public class RuoYiServiceAttachmentStorageAdapter implements ServiceAttachmentStoragePort {
    @Override
    public void store(StoreObject object) {
        try {
            OssFactory.instance().upload(new ByteArrayInputStream(object.content()), object.objectKey(),
                (long) object.content().length, object.mediaType());
        } catch (RuntimeException exception) {
            throw new ServiceException("SVC-ATT-003: 受控对象存储写入失败", 503);
        }
    }

    @Override
    public String temporaryDownload(String objectKey, Duration ttl) {
        try { return OssFactory.instance().createPresignedGetUrl(objectKey, ttl); }
        catch (RuntimeException exception) { throw new ServiceException("SVC-ATT-003: 受控对象存储签名失败", 503); }
    }

    @Override
    public void delete(String objectKey) {
        try { OssFactory.instance().delete(objectKey); }
        catch (RuntimeException exception) { throw new ServiceException("SVC-ATT-003: 受控对象存储清理失败", 503); }
    }
}
