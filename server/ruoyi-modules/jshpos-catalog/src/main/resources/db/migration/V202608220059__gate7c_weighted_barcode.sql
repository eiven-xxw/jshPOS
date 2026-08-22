-- T2-PRD-005：版本化秤码/金额码模板及只追加发布历史。
CREATE TABLE cat_weighted_barcode_template (
    template_id BIGINT NOT NULL COMMENT '模板主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    template_code VARCHAR(64) NOT NULL COMMENT '稳定模板编码',
    version_no INT NOT NULL COMMENT '模板业务版本',
    scope_type VARCHAR(16) NOT NULL COMMENT '适用范围：TENANT/STORE',
    store_id BIGINT NULL COMMENT '门店范围标识',
    barcode_kind VARCHAR(16) NOT NULL COMMENT '码类型：WEIGHT/AMOUNT',
    symbology VARCHAR(16) NOT NULL DEFAULT 'EAN13' COMMENT '条码制式',
    prefix_value VARCHAR(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '保留前导零的匹配前缀',
    total_length SMALLINT NOT NULL DEFAULT 13 COMMENT '原始条码总长度',
    sku_start_pos SMALLINT NOT NULL COMMENT 'SKU 码段起始位置，1 基',
    sku_length SMALLINT NOT NULL COMMENT 'SKU 码段长度',
    value_start_pos SMALLINT NOT NULL COMMENT '计量值起始位置，1 基',
    value_length SMALLINT NOT NULL COMMENT '计量值长度',
    value_scale SMALLINT NOT NULL COMMENT '计量值十进制小数位',
    priority_no INT NOT NULL DEFAULT 0 COMMENT '同前缀选择优先级',
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/RETIRED',
    effective_from DATETIME(6) NOT NULL COMMENT 'UTC 生效时间',
    effective_to DATETIME(6) NULL COMMENT 'UTC 失效时间，左闭右开',
    content_sha256 CHAR(64) NULL COMMENT '发布内容 SHA-256',
    published_at DATETIME(6) NULL COMMENT '发布时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_dept BIGINT NULL COMMENT '创建部门',
    create_by BIGINT NULL COMMENT '创建人',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    update_by BIGINT NULL COMMENT '更新人',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (template_id),
    UNIQUE KEY uk_cat_wbt_tenant_id (tenant_id, template_id),
    UNIQUE KEY uk_cat_wbt_code_version (tenant_id, template_code, version_no),
    KEY idx_cat_wbt_resolve (tenant_id, state, scope_type, store_id, effective_from, effective_to, prefix_value),
    CONSTRAINT fk_cat_wbt_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_cat_wbt_version CHECK (version_no > 0),
    CONSTRAINT ck_cat_wbt_scope CHECK ((scope_type = 'TENANT' AND store_id IS NULL) OR (scope_type = 'STORE' AND store_id IS NOT NULL)),
    CONSTRAINT ck_cat_wbt_kind CHECK (barcode_kind IN ('WEIGHT', 'AMOUNT')),
    CONSTRAINT ck_cat_wbt_symbology CHECK (symbology = 'EAN13'),
    CONSTRAINT ck_cat_wbt_prefix CHECK (prefix_value REGEXP '^[0-9]{2,5}$'),
    CONSTRAINT ck_cat_wbt_shape CHECK (
        total_length = 13 AND sku_start_pos >= 1 AND sku_length BETWEEN 1 AND 8
        AND value_start_pos >= 1 AND value_length BETWEEN 1 AND 8
        AND sku_start_pos + sku_length - 1 <= 12
        AND value_start_pos + value_length - 1 <= 12
        AND sku_start_pos > CHAR_LENGTH(prefix_value)
        AND value_start_pos > CHAR_LENGTH(prefix_value)
        AND (sku_start_pos + sku_length <= value_start_pos OR value_start_pos + value_length <= sku_start_pos)
        AND value_scale BETWEEN 0 AND 6
        AND (barcode_kind <> 'AMOUNT' OR value_scale = 2)
    ),
    CONSTRAINT ck_cat_wbt_state CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_cat_wbt_window CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ck_cat_wbt_hash CHECK (content_sha256 IS NULL OR content_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_cat_wbt_publish_shape CHECK (
        state = 'DRAFT' OR (content_sha256 IS NOT NULL AND published_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化秤码与金额码模板';

CREATE TABLE cat_weighted_barcode_history (
    history_id BIGINT NOT NULL COMMENT '历史事实主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '可信租户标识',
    template_id BIGINT NOT NULL COMMENT '模板主键',
    event_type VARCHAR(32) NOT NULL COMMENT 'PUBLISHED/RETIRED',
    template_version INT NOT NULL COMMENT '事件时模板乐观锁版本',
    content_sha256 CHAR(64) NOT NULL COMMENT '模板内容摘要',
    payload_json JSON NOT NULL COMMENT '冻结模板载荷',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件发生时间',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '落库时间',
    PRIMARY KEY (history_id),
    UNIQUE KEY uk_cat_wbh_tenant_id (tenant_id, history_id),
    UNIQUE KEY uk_cat_wbh_event (tenant_id, template_id, event_type, template_version),
    KEY idx_cat_wbh_template (tenant_id, template_id, occurred_at),
    CONSTRAINT fk_cat_wbh_template FOREIGN KEY (tenant_id, template_id)
        REFERENCES cat_weighted_barcode_template (tenant_id, template_id),
    CONSTRAINT ck_cat_wbh_event CHECK (event_type IN ('PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_cat_wbh_hash CHECK (content_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='秤码模板只追加状态历史';

DELIMITER $$
CREATE TRIGGER trg_cat_wbt_published_immutable
BEFORE UPDATE ON cat_weighted_barcode_template
FOR EACH ROW
BEGIN
    IF (OLD.state = 'PUBLISHED' AND NEW.state NOT IN ('PUBLISHED', 'RETIRED'))
       OR (OLD.state = 'RETIRED' AND NEW.state <> 'RETIRED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'weighted barcode state transition is invalid';
    END IF;
    IF OLD.state IN ('PUBLISHED', 'RETIRED') AND (
        NEW.tenant_id <> OLD.tenant_id OR NEW.template_code <> OLD.template_code OR
        NEW.version_no <> OLD.version_no OR NEW.scope_type <> OLD.scope_type OR
        NOT (NEW.store_id <=> OLD.store_id) OR NEW.barcode_kind <> OLD.barcode_kind OR
        NEW.symbology <> OLD.symbology OR NEW.prefix_value <> OLD.prefix_value OR
        NEW.total_length <> OLD.total_length OR NEW.sku_start_pos <> OLD.sku_start_pos OR
        NEW.sku_length <> OLD.sku_length OR NEW.value_start_pos <> OLD.value_start_pos OR
        NEW.value_length <> OLD.value_length OR NEW.value_scale <> OLD.value_scale OR
        NEW.priority_no <> OLD.priority_no OR NEW.effective_from <> OLD.effective_from OR
        NOT (NEW.effective_to <=> OLD.effective_to) OR NEW.content_sha256 <> OLD.content_sha256
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'published weighted barcode template is immutable';
    END IF;
END$$

CREATE TRIGGER trg_cat_wbh_no_update
BEFORE UPDATE ON cat_weighted_barcode_history
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'weighted barcode history is append only';
END$$

CREATE TRIGGER trg_cat_wbh_no_delete
BEFORE DELETE ON cat_weighted_barcode_history
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'weighted barcode history is append only';
END$$
DELIMITER ;

-- 权限种子使用商品中心已保留父菜单，并对 ID 冲突失败关闭。
DELIMITER $$
CREATE PROCEDURE jsh_assert_gate7c_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu
        WHERE menu_id BETWEEN 9200111 AND 9200114
          AND NOT (
              (menu_id = 9200111 AND perms = 'catalog:weighted-barcode:query') OR
              (menu_id = 9200112 AND perms = 'catalog:weighted-barcode:manage') OR
              (menu_id = 9200113 AND perms = 'catalog:weighted-barcode:publish') OR
              (menu_id = 9200114 AND perms = 'catalog:weighted-barcode:preview')
          )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Gate 7C sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;

CALL jsh_assert_gate7c_menu_ids();
DROP PROCEDURE jsh_assert_gate7c_menu_ids;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
) VALUES
    (9200111, '秤码模板查询', 9200100, 11, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:weighted-barcode:query', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '查询版本化秤码模板'),
    (9200112, '秤码模板管理', 9200100, 12, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:weighted-barcode:manage', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '创建和停用秤码模板'),
    (9200113, '秤码模板发布', 9200100, 13, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:weighted-barcode:publish', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '发布秤码模板版本'),
    (9200114, '秤码解析预览', 9200100, 14, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:weighted-barcode:preview', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '离线规则发布前解析预览')
ON DUPLICATE KEY UPDATE menu_id = VALUES(menu_id);
