package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.reporting.application.port.SalesPageCursorCodec;
import org.dromara.common.core.exception.ServiceException;

/** 游标签名密钥未配置时，版本化销售分页入口失败关闭。 */
public final class RejectingSalesPageCursorCodec implements SalesPageCursorCodec {
    @Override public String encode(CursorEnvelope envelope) { throw unavailable(); }
    @Override public CursorEnvelope decodeAndVerify(String token, String tenantId, String filterSha256,
                                                     String projectionVersion) { throw unavailable(); }
    private ServiceException unavailable() {
        return new ServiceException("RPT-R2R2-010: 销售分页游标签名能力未配置", 503);
    }
}
