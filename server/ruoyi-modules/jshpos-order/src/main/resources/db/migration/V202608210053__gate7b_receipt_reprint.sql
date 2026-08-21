-- Gate 7B POS-011：语义收据与打印请求只追加事实；不代表真实打印成功。
CREATE TABLE ord_receipt_document (
    document_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cashier_user_id BIGINT NOT NULL,
    document_type VARCHAR(24) NOT NULL,
    template_version VARCHAR(32) NOT NULL,
    template_schema_version INT NOT NULL,
    semantic_payload_json JSON NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_aggregate_version BIGINT NOT NULL,
    execution_status VARCHAR(24) NOT NULL,
    frozen_at DATETIME(3) NOT NULL,
    PRIMARY KEY (document_id),
    UNIQUE KEY uk_receipt_document_tenant (tenant_id, document_id),
    UNIQUE KEY uk_receipt_document_event (tenant_id, source_event_id),
    UNIQUE KEY uk_receipt_document_order (tenant_id, order_id, document_type),
    CONSTRAINT fk_receipt_document_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT ck_receipt_document_type CHECK (document_type='SALE_RECEIPT'),
    CONSTRAINT ck_receipt_document_schema CHECK (template_schema_version=1 AND order_aggregate_version>0),
    CONSTRAINT ck_receipt_document_hash CHECK (content_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_receipt_document_execution CHECK (execution_status='BLOCKED_EXTERNAL')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成交时冻结的语义收据；租户终端隔离；只追加且不代表实机打印';

CREATE TABLE ord_print_request (
    print_request_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id VARCHAR(20) NOT NULL,
    source_event_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    print_job_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_id BIGINT NOT NULL,
    terminal_id CHAR(26) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by BIGINT NOT NULL,
    authorization_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_kind VARCHAR(16) NOT NULL,
    reprint_no INT NOT NULL,
    reason_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_text VARCHAR(256) NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_status VARCHAR(24) NOT NULL,
    requested_at DATETIME(3) NOT NULL,
    PRIMARY KEY (print_request_id),
    UNIQUE KEY uk_print_request_tenant (tenant_id, print_request_id),
    UNIQUE KEY uk_print_request_event (tenant_id, source_event_id),
    UNIQUE KEY uk_print_request_sequence (tenant_id, order_id, request_kind, reprint_no),
    CONSTRAINT fk_print_request_job FOREIGN KEY (tenant_id, print_job_id) REFERENCES ord_print_job (tenant_id, print_job_id),
    CONSTRAINT fk_print_request_document FOREIGN KEY (tenant_id, document_id) REFERENCES ord_receipt_document (tenant_id, document_id),
    CONSTRAINT fk_print_request_order FOREIGN KEY (tenant_id, order_id) REFERENCES ord_sales_order (tenant_id, order_id),
    CONSTRAINT ck_print_request_kind CHECK (request_kind='REPRINT' AND reprint_no BETWEEN 1 AND 999),
    CONSTRAINT ck_print_request_hash CHECK (request_sha256 REGEXP '^[a-f0-9]{64}$' AND document_sha256 REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_print_request_execution CHECK (execution_status='BLOCKED_EXTERNAL')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补打请求与授权审计；只追加；真实打印未解阻时失败关闭';

DELIMITER $$
CREATE TRIGGER trg_receipt_document_no_update BEFORE UPDATE ON ord_receipt_document FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='receipt document is immutable'; END$$
CREATE TRIGGER trg_receipt_document_no_delete BEFORE DELETE ON ord_receipt_document FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='receipt document is immutable'; END$$
CREATE TRIGGER trg_print_request_no_update BEFORE UPDATE ON ord_print_request FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='print request is append-only'; END$$
CREATE TRIGGER trg_print_request_no_delete BEFORE DELETE ON ord_print_request FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='print request is append-only'; END$$
DELIMITER ;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9200210,'小票预览',9200200,10,'#','',NULL,'',1,0,'F','0','0','pos:print:preview','#','冻结模板和摘要预览',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200210 OR perms='pos:print:preview');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9200211,'小票补打',9200200,11,'#','',NULL,'',1,0,'F','0','0','pos:print:reprint','#','受权补打请求与审计；不代表实机执行',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200211 OR perms='pos:print:reprint');
