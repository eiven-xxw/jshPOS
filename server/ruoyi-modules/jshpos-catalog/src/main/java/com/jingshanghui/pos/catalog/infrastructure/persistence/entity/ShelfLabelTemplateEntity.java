package com.jingshanghui.pos.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

/** ShelfLabel Owner 的版本化纯文本价签模板持久化实体。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("lbl_template")
public class ShelfLabelTemplateEntity extends TenantEntity {

    /** 模板主键。 */
    @TableId
    private Long templateId;
    /** 租户内模板编码。 */
    private String templateCode;
    /** 模板名称。 */
    private String templateName;
    /** 业务版本号。 */
    private Integer versionNo;
    /** TENANT 或 STORE 作用域。 */
    private String scopeType;
    /** 门店模板的门店主键。 */
    private Long storeId;
    /** 仅含批准占位符的纯文本模板。 */
    private String bodyTemplate;
    /** 创建模板命令的稳定幂等键。 */
    private String createIdempotencyKey;
    /** 创建模板命令 SHA-256。 */
    private String createRequestSha256;
    /** DRAFT、PUBLISHED 或 RETIRED。 */
    private String state;
    /** 发布内容 SHA-256。 */
    private String contentSha256;
    /** 发布时间，UTC。 */
    private LocalDateTime publishedAt;
    /** 创建时间，UTC。 */
    private LocalDateTime createdAt;
    /** 乐观锁版本。 */
    @Version
    private Integer version;
}
