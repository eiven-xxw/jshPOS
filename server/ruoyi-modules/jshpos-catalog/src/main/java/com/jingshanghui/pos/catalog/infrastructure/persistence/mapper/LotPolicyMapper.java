package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 批次策略版本的 XML 持久化边界；所有查询显式携带可信 tenant_id。 */
public interface LotPolicyMapper {
    PolicyView findById(@Param("tenantId") String tenantId, @Param("policyVersionId") String policyVersionId);
    PolicyView findEffective(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                             @Param("skuId") Long skuId, @Param("effectiveAt") LocalDateTime effectiveAt);
    List<PolicyView> listEffective(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                   @Param("effectiveAt") LocalDateTime effectiveAt);
    int insertPublished(PolicyWrite write);

    /** 发布策略写入参数；tenantId 只能来自 TrustedTenantContext。 */
    record PolicyWrite(String tenantId, String policyVersionId, Long storeId, Long skuId, boolean enabled,
                       String expiryBasis, Integer shelfLifeDays, int nearExpiryDays, String industry,
                       Long templateVersionId, LocalDateTime effectiveFrom, String contentSha256,
                       Long publishedBy, LocalDateTime publishedAt) {
    }
}
