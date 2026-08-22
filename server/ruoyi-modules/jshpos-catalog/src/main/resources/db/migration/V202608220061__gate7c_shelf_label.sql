-- T2-LBL-001：货架价签模板、按门店任务、冻结任务项、异常和只追加事件。
CREATE TABLE lbl_template (
    template_id BIGINT NOT NULL COMMENT '价签模板主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '由可信认证上下文注入的租户标识',
    template_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '租户内稳定模板编码',
    template_name VARCHAR(200) NOT NULL COMMENT '价签模板名称',
    version_no INT NOT NULL COMMENT '模板业务版本号，大于零',
    scope_type VARCHAR(16) NOT NULL COMMENT '模板范围：TENANT或STORE',
    store_id BIGINT NULL COMMENT 'STORE范围的目标门店主键',
    scope_store_key BIGINT GENERATED ALWAYS AS (IFNULL(store_id,0)) STORED COMMENT '用于包含租户级NULL范围的唯一键',
    body_template TEXT NOT NULL COMMENT '仅含批准占位符的纯文本模板，最大2000字符',
    create_idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建模板命令稳定幂等键',
    create_request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建模板命令SHA-256',
    state VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '模板状态：DRAFT/PUBLISHED/RETIRED',
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '发布模板内容SHA-256小写十六进制',
    published_at DATETIME(6) NULL COMMENT '模板发布时间，UTC',
    created_at DATETIME(6) NOT NULL COMMENT '模板创建时间，UTC',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_dept BIGINT NULL COMMENT 'RuoYi审计创建部门',
    create_by BIGINT NULL COMMENT 'RuoYi审计创建人',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '数据库创建时间，UTC',
    update_by BIGINT NULL COMMENT 'RuoYi审计更新人',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '数据库更新时间，UTC',
    PRIMARY KEY (template_id),
    UNIQUE KEY uk_lbl_template_tenant_id (tenant_id, template_id),
    UNIQUE KEY uk_lbl_template_create_idem (tenant_id, create_idempotency_key),
    UNIQUE KEY uk_lbl_template_code_version (tenant_id, template_code, version_no, scope_type, scope_store_key),
    KEY idx_lbl_template_resolve (tenant_id, state, scope_type, store_id, version_no),
    CONSTRAINT fk_lbl_template_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_lbl_template_version CHECK (version_no > 0),
    CONSTRAINT ck_lbl_template_scope CHECK ((scope_type='TENANT' AND store_id IS NULL) OR (scope_type='STORE' AND store_id IS NOT NULL)),
    CONSTRAINT ck_lbl_template_state CHECK (state IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT ck_lbl_template_body CHECK (CHAR_LENGTH(body_template) BETWEEN 1 AND 2000),
    CONSTRAINT ck_lbl_template_create_hash CHECK (create_request_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_lbl_template_hash CHECK (content_sha256 IS NULL OR content_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_lbl_template_publish CHECK (state='DRAFT' OR (content_sha256 IS NOT NULL AND published_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ShelfLabel Owner版本化纯文本价签模板';

CREATE TABLE lbl_label_task (
    task_id BIGINT NOT NULL COMMENT '按门店价签任务主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '由可信认证上下文注入的租户标识',
    source_event_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '价格事件稳定幂等键',
    source_event_type VARCHAR(32) NOT NULL COMMENT '来源类型：PRICE_BOOK_PUBLISHED/PRICE_BOOK_RETIRED',
    source_event_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源事件内容SHA-256',
    source_price_book_id BIGINT NOT NULL COMMENT 'Pricing Owner价格簿主键',
    source_price_version INT NOT NULL COMMENT '来源价格业务版本号',
    store_id BIGINT NOT NULL COMMENT '目标门店主键',
    store_name VARCHAR(200) NOT NULL COMMENT '任务生成时的门店名称快照',
    effective_at DATETIME(6) NOT NULL COMMENT '任务内最早价格生效时间，UTC',
    state VARCHAR(24) NOT NULL COMMENT '软件任务投影状态，不表示真实打印成功',
    created_at DATETIME(6) NOT NULL COMMENT '任务创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '任务投影更新时间，UTC',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_lbl_task_tenant_id (tenant_id, task_id),
    UNIQUE KEY uk_lbl_task_source (tenant_id, source_event_key),
    KEY idx_lbl_task_workbench (tenant_id, store_id, state, effective_at, task_id),
    KEY idx_lbl_task_price (tenant_id, source_price_book_id, source_price_version, store_id),
    CONSTRAINT fk_lbl_task_price_book FOREIGN KEY (tenant_id, source_price_book_id) REFERENCES prc_price_book (tenant_id, price_book_id),
    CONSTRAINT fk_lbl_task_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT ck_lbl_task_source_type CHECK (source_event_type IN ('PRICE_BOOK_PUBLISHED','PRICE_BOOK_RETIRED')),
    CONSTRAINT ck_lbl_task_source_hash CHECK (source_event_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_lbl_task_price_version CHECK (source_price_version > 0),
    CONSTRAINT ck_lbl_task_state CHECK (state IN ('PENDING','PREVIEW_READY','IN_PROGRESS','COMPLETED','EXCEPTION','SUPERSEDED','DISPATCH_BLOCKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按价格事件和门店拆分的货架换签任务当前投影';

CREATE TABLE lbl_label_task_item (
    item_id BIGINT NOT NULL COMMENT '价签任务项主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '由可信认证上下文注入的租户标识',
    task_id BIGINT NOT NULL COMMENT '所属价签任务主键',
    source_price_book_id BIGINT NOT NULL COMMENT '来源价格簿主键',
    source_price_item_id BIGINT NOT NULL COMMENT '来源价格项主键',
    source_price_version INT NOT NULL COMMENT '来源价格业务版本号',
    scope_priority SMALLINT NOT NULL COMMENT '价格范围优先级：租户基础价1，门店价2',
    store_id BIGINT NOT NULL COMMENT '目标门店主键',
    store_name VARCHAR(200) NOT NULL COMMENT '门店名称冻结快照',
    sku_id BIGINT NOT NULL COMMENT '商品SKU主键',
    sku_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'SKU编码冻结快照',
    product_name VARCHAR(200) NOT NULL COMMENT '商品名称冻结快照',
    unit_id BIGINT NOT NULL COMMENT '销售单位主键',
    unit_name VARCHAR(100) NOT NULL COMMENT '销售单位名称冻结快照',
    barcode VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '首选条码字符串，保留前导零',
    old_price_minor BIGINT NULL COMMENT '原价，单位为分；首次定价可为空',
    new_price_minor BIGINT NULL COMMENT '新价，单位为分；无回退价时为空并进入异常',
    currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ISO 4217币种，商业V1为CNY',
    effective_at DATETIME(6) NOT NULL COMMENT '价格生效时间，UTC',
    state VARCHAR(24) NOT NULL COMMENT 'PENDING/PREVIEW_READY/REPLACED_CONFIRMED/EXCEPTION/SUPERSEDED',
    exception_reason VARCHAR(500) NULL COMMENT '当前异常摘要；完整异常事实另行只追加保存',
    snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '商品单位条码价格冻结快照SHA-256',
    created_at DATETIME(6) NOT NULL COMMENT '任务项创建时间，UTC',
    updated_at DATETIME(6) NOT NULL COMMENT '任务项状态更新时间，UTC',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    PRIMARY KEY (item_id),
    UNIQUE KEY uk_lbl_item_tenant_id (tenant_id, item_id),
    UNIQUE KEY uk_lbl_item_source (tenant_id, task_id, source_price_item_id),
    KEY idx_lbl_item_converge (tenant_id, store_id, sku_id, unit_id, effective_at, scope_priority, source_price_version, source_price_book_id),
    KEY idx_lbl_item_task_state (tenant_id, task_id, state, item_id),
    CONSTRAINT fk_lbl_item_task FOREIGN KEY (tenant_id, task_id) REFERENCES lbl_label_task (tenant_id, task_id),
    CONSTRAINT fk_lbl_item_price_book FOREIGN KEY (tenant_id, source_price_book_id) REFERENCES prc_price_book (tenant_id, price_book_id),
    CONSTRAINT fk_lbl_item_price_item FOREIGN KEY (tenant_id, source_price_item_id) REFERENCES prc_price_item (tenant_id, price_item_id),
    CONSTRAINT fk_lbl_item_store FOREIGN KEY (tenant_id, store_id) REFERENCES jsh_store (tenant_id, store_id),
    CONSTRAINT fk_lbl_item_sku FOREIGN KEY (tenant_id, sku_id) REFERENCES cat_sku (tenant_id, sku_id),
    CONSTRAINT fk_lbl_item_unit FOREIGN KEY (tenant_id, unit_id) REFERENCES cat_unit (tenant_id, unit_id),
    CONSTRAINT ck_lbl_item_version CHECK (source_price_version > 0),
    CONSTRAINT ck_lbl_item_priority CHECK (scope_priority IN (1,2)),
    CONSTRAINT ck_lbl_item_amount CHECK ((old_price_minor IS NULL OR old_price_minor >= 0) AND (new_price_minor IS NULL OR new_price_minor >= 0)),
    CONSTRAINT ck_lbl_item_currency CHECK (currency='CNY'),
    CONSTRAINT ck_lbl_item_state CHECK (state IN ('PENDING','PREVIEW_READY','REPLACED_CONFIRMED','EXCEPTION','SUPERSEDED')),
    CONSTRAINT ck_lbl_item_exception CHECK ((state='EXCEPTION' AND exception_reason IS NOT NULL) OR state<>'EXCEPTION'),
    CONSTRAINT ck_lbl_item_hash CHECK (snapshot_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='货架价签任务项不可变商品价格快照与受控状态投影';

CREATE TABLE lbl_task_event (
    event_id BIGINT NOT NULL COMMENT '价签工作流事件主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '由可信认证上下文注入的租户标识',
    task_id BIGINT NULL COMMENT '关联价签任务主键',
    item_id BIGINT NULL COMMENT '可选关联价签任务项主键',
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '版本化价签工作流事件类型',
    idempotency_key VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定幂等键',
    command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '命令内容SHA-256',
    payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件载荷SHA-256',
    payload_json JSON NOT NULL COMMENT '脱敏后的事件载荷',
    actor_user_id BIGINT NOT NULL COMMENT '可信操作者用户主键',
    correlation_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全链路关联标识',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件发生时间，UTC',
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_lbl_event_tenant_id (tenant_id, event_id),
    UNIQUE KEY uk_lbl_event_idempotency (tenant_id, idempotency_key),
    KEY idx_lbl_event_task (tenant_id, task_id, occurred_at, event_id),
    CONSTRAINT fk_lbl_event_task FOREIGN KEY (tenant_id, task_id) REFERENCES lbl_label_task (tenant_id, task_id),
    CONSTRAINT fk_lbl_event_item FOREIGN KEY (tenant_id, item_id) REFERENCES lbl_label_task_item (tenant_id, item_id),
    CONSTRAINT ck_lbl_event_hash CHECK (command_sha256 REGEXP '^[a-f0-9]{64}$' AND payload_sha256 REGEXP '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ShelfLabel Owner只追加工作流事件与幂等结果';

CREATE TABLE lbl_task_exception (
    exception_id BIGINT NOT NULL COMMENT '价签异常事实主键',
    tenant_id VARCHAR(20) NOT NULL COMMENT '由可信认证上下文注入的租户标识',
    task_id BIGINT NOT NULL COMMENT '关联价签任务主键',
    item_id BIGINT NULL COMMENT '可选关联价签任务项主键',
    exception_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定异常码',
    reason VARCHAR(500) NOT NULL COMMENT '脱敏异常原因',
    resolution_type VARCHAR(32) NOT NULL COMMENT 'OPEN/BLOCKED_EXTERNAL/ACKNOWLEDGED/RESOLVED',
    actor_user_id BIGINT NOT NULL COMMENT '可信操作者用户主键',
    correlation_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全链路关联标识',
    occurred_at DATETIME(6) NOT NULL COMMENT '异常或处置发生时间，UTC',
    PRIMARY KEY (exception_id),
    UNIQUE KEY uk_lbl_exception_tenant_id (tenant_id, exception_id),
    KEY idx_lbl_exception_task (tenant_id, task_id, occurred_at, exception_id),
    CONSTRAINT fk_lbl_exception_task FOREIGN KEY (tenant_id, task_id) REFERENCES lbl_label_task (tenant_id, task_id),
    CONSTRAINT fk_lbl_exception_item FOREIGN KEY (tenant_id, item_id) REFERENCES lbl_label_task_item (tenant_id, item_id),
    CONSTRAINT ck_lbl_exception_resolution CHECK (resolution_type IN ('OPEN','BLOCKED_EXTERNAL','ACKNOWLEDGED','RESOLVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='货架价签异常及处置只追加事实';

DELIMITER $$
CREATE TRIGGER trg_lbl_template_published_immutable
BEFORE UPDATE ON lbl_template FOR EACH ROW
BEGIN
    IF OLD.state IN ('PUBLISHED','RETIRED') AND (
        NEW.tenant_id <> OLD.tenant_id OR NEW.template_code <> OLD.template_code OR
        NEW.template_name <> OLD.template_name OR NEW.version_no <> OLD.version_no OR
        NEW.scope_type <> OLD.scope_type OR NOT (NEW.store_id <=> OLD.store_id) OR
        NEW.body_template <> OLD.body_template OR NEW.create_idempotency_key <> OLD.create_idempotency_key OR
        NEW.create_request_sha256 <> OLD.create_request_sha256 OR NEW.content_sha256 <> OLD.content_sha256 OR
        NOT (NEW.published_at <=> OLD.published_at)
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='published shelf label template is immutable';
    END IF;
    IF (OLD.state='PUBLISHED' AND NEW.state NOT IN ('PUBLISHED','RETIRED'))
       OR (OLD.state='RETIRED' AND NEW.state<>'RETIRED') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shelf label template state transition is invalid';
    END IF;
END$$

CREATE TRIGGER trg_lbl_item_snapshot_immutable
BEFORE UPDATE ON lbl_label_task_item FOR EACH ROW
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.task_id <> OLD.task_id OR
       NEW.source_price_book_id <> OLD.source_price_book_id OR NEW.source_price_item_id <> OLD.source_price_item_id OR
       NEW.source_price_version <> OLD.source_price_version OR NEW.scope_priority <> OLD.scope_priority OR
       NEW.store_id <> OLD.store_id OR NEW.store_name <> OLD.store_name OR NEW.sku_id <> OLD.sku_id OR
       NEW.sku_code <> OLD.sku_code OR NEW.product_name <> OLD.product_name OR NEW.unit_id <> OLD.unit_id OR
       NEW.unit_name <> OLD.unit_name OR NOT (NEW.barcode <=> OLD.barcode) OR
       NOT (NEW.old_price_minor <=> OLD.old_price_minor) OR NOT (NEW.new_price_minor <=> OLD.new_price_minor) OR
       NEW.currency <> OLD.currency OR NEW.effective_at <> OLD.effective_at OR
       NEW.snapshot_sha256 <> OLD.snapshot_sha256 OR NEW.created_at <> OLD.created_at THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shelf label item snapshot is immutable';
    END IF;
END$$

CREATE TRIGGER trg_lbl_event_no_update BEFORE UPDATE ON lbl_task_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shelf label event is append-only'; END$$
CREATE TRIGGER trg_lbl_event_no_delete BEFORE DELETE ON lbl_task_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shelf label event is append-only'; END$$
CREATE TRIGGER trg_lbl_exception_no_update BEFORE UPDATE ON lbl_task_exception FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shelf label exception is append-only'; END$$
CREATE TRIGGER trg_lbl_exception_no_delete BEFORE DELETE ON lbl_task_exception FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='shelf label exception is append-only'; END$$
DELIMITER ;

-- 权限种子沿用商品中心父菜单；保留ID发生语义冲突时失败关闭。
DELIMITER $$
CREATE PROCEDURE jsh_assert_gate7c_lbl_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu
        WHERE menu_id BETWEEN 9200115 AND 9200120
          AND NOT (
              (menu_id=9200115 AND perms='catalog:label:template:manage') OR
              (menu_id=9200116 AND perms='catalog:label:template:publish') OR
              (menu_id=9200117 AND perms='catalog:label:task:read') OR
              (menu_id=9200118 AND perms='catalog:label:task:confirm') OR
              (menu_id=9200119 AND perms='catalog:label:task:exception') OR
              (menu_id=9200120 AND perms='catalog:label:task:dispatch')
          )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 7C label sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate7c_lbl_menu_ids();
DROP PROCEDURE jsh_assert_gate7c_lbl_menu_ids;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
 (9200115,'价签模板管理',9200100,15,'#','',NULL,'',1,0,'F','0','0','catalog:label:template:manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建版本化纯文本价签模板'),
 (9200116,'价签模板发布',9200100,16,'#','',NULL,'',1,0,'F','0','0','catalog:label:template:publish','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'发布和停用不可变价签模板'),
 (9200117,'价签任务查询',9200100,17,'#','',NULL,'',1,0,'F','0','0','catalog:label:task:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按门店查询任务和安全预览'),
 (9200118,'货架换签确认',9200100,18,'#','',NULL,'',1,0,'F','0','0','catalog:label:task:confirm','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'受权人工确认换签，不代表打印成功'),
 (9200119,'价签异常处置',9200100,19,'#','',NULL,'',1,0,'F','0','0','catalog:label:task:exception','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'记录和恢复价签异常'),
 (9200120,'价签打印阻断',9200100,20,'#','',NULL,'',1,0,'F','0','0','catalog:label:task:dispatch','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'真实打印未解阻时仅允许失败关闭')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
