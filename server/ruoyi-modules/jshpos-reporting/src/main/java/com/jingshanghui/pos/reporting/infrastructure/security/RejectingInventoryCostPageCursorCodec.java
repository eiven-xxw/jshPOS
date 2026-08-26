package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.reporting.application.port.InventoryCostPageCursorCodec;
import org.dromara.common.core.exception.ServiceException;

/** 游标签名密钥未配置时，版本化库存成本分页与可恢复导出失败关闭。 */
public final class RejectingInventoryCostPageCursorCodec implements InventoryCostPageCursorCodec {
    @Override public String encode(CursorEnvelope envelope) { throw unavailable(); }
    @Override public CursorEnvelope decodeAndVerify(String token, String tenantId, String filterSha256,
                                                     String projectionVersion) { throw unavailable(); }
    private ServiceException unavailable() {
        return new ServiceException("RPT-R2R2-023: 库存成本游标签名能力未配置", 503);
    }
}
