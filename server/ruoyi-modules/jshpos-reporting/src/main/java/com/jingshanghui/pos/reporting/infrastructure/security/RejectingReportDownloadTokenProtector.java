package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.reporting.application.port.ReportDownloadTokenProtector;
import org.dromara.common.core.exception.ServiceException;

/** 外部 HMAC 密钥缺失时失败关闭下载能力。 */
public final class RejectingReportDownloadTokenProtector implements ReportDownloadTokenProtector {
    @Override public TokenIssue issue() { throw unavailable(); }
    @Override public String hash(String plaintextToken) { throw unavailable(); }
    private ServiceException unavailable() {
        return new ServiceException("RPT-SEC-004: 下载令牌密钥未配置", 503);
    }
}
