package com.jingshanghui.pos.promotion.application.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteResult;

import java.time.LocalDateTime;

/** 促销应用层只读视图。 */
public final class PromotionViews {
    private PromotionViews() {
    }

    /**
     * 规则版本视图。
     *
     * @param ruleId 规则ULID
     * @param ruleVersionId 规则版本ULID
     * @param ruleCode 规则编码
     * @param ruleName 规则名称
     * @param versionNo 版本号
     * @param state 状态
     * @param creatorUserId 创建人
     * @param approvedBy 审批人
     * @param version 乐观锁版本
     * @param contentSha256 内容摘要
     */
    public record RuleVersionView(String ruleId, String ruleVersionId, String ruleCode, String ruleName,
                                  int versionNo, String state, Long creatorUserId, Long approvedBy,
                                  int version, String contentSha256) {
    }

    /**
     * 询价视图。
     *
     * @param quoteId 询价ULID
     * @param requestSha256 请求摘要
     * @param resultSha256 结果摘要
     * @param engineVersion 引擎版本
     * @param packageVersion 包版本
     * @param result 计算结果
     */
    public record QuoteView(String quoteId, String requestSha256, String resultSha256, String engineVersion,
                            long packageVersion, QuoteResult result) {
    }

    /**
     * 门店规则包元数据。
     *
     * @param packageId 包ULID
     * @param storeId 门店
     * @param packageVersion 包版本
     * @param previousVersion 前一版本
     * @param payloadSha256 载荷摘要
     * @param signingKeyId KMS/HSM签名密钥版本
     * @param objectKey 对象键
     * @param generatedAt 生成时间
     * @param expiresAt 过期时间
     */
    public record PackageView(String packageId, Long storeId, long packageVersion, long previousVersion,
                              String payloadSha256, String signingKeyId, @JsonIgnore String objectKey, LocalDateTime generatedAt,
                              LocalDateTime expiresAt) {
    }

    /**
     * 供受权 POS 下载并自行验签的原始规则包。
     * @param payload canonical 载荷 @param payloadSha256 载荷摘要
     * @param signingKeyId 可信签名密钥版本 @param signature Ed25519签名
     */
    public record PackageArtifact(byte[] payload, String payloadSha256, String signingKeyId, byte[] signature) {
        public PackageArtifact { payload = payload.clone(); signature = signature.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }
}
