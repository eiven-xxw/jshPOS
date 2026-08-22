CREATE TABLE cat_migration_product (
    batch_id VARCHAR(26) NOT NULL COMMENT '迁移批次ULID，来源Migration Owner',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识，禁止来自文件内容',
    row_id VARCHAR(26) NOT NULL COMMENT '迁移规范化行ULID',
    row_sha256 CHAR(64) NOT NULL COMMENT '冻结规范化商品行SHA-256',
    sku_id BIGINT NOT NULL COMMENT 'Catalog Owner创建的SKU主键',
    base_unit_id BIGINT NOT NULL COMMENT '成交与库存基础单位主键',
    sku_code VARCHAR(64) NOT NULL COMMENT '租户内SKU业务编码',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '绑定创建UTC时间',
    PRIMARY KEY (tenant_id,batch_id,row_id),
    UNIQUE KEY uk_cat_migration_batch_sku (tenant_id,batch_id,sku_code),
    UNIQUE KEY uk_cat_migration_sku (tenant_id,sku_id),
    CONSTRAINT fk_cat_migration_sku FOREIGN KEY (tenant_id,sku_id) REFERENCES cat_sku(tenant_id,sku_id),
    CONSTRAINT fk_cat_migration_unit FOREIGN KEY (tenant_id,base_unit_id) REFERENCES cat_unit(tenant_id,unit_id),
    CONSTRAINT ck_cat_migration_hash CHECK (row_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB COMMENT='Catalog Owner开业迁移行与商品身份幂等绑定';

DELIMITER $$
CREATE TRIGGER trg_cat_migration_product_no_update BEFORE UPDATE ON cat_migration_product
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cat_migration_product is append-only'; END$$
CREATE TRIGGER trg_cat_migration_product_no_delete BEFORE DELETE ON cat_migration_product
FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cat_migration_product is append-only'; END$$
DELIMITER ;
