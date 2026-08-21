/// Gate 7B POS-011 收据文档与打印请求前向迁移。
///
/// 语义收据在成交事务内冻结；打印请求只追加。真实打印尚未解阻，
/// 因而软件执行状态只能为 BLOCKED_EXTERNAL，不能伪造 PRINTED。
abstract final class Gate7bReceiptSchema {
  static const int version = 10;

  static const String v10 = r'''
CREATE TABLE local_receipt_document (
  document_id TEXT NOT NULL PRIMARY KEY CHECK(length(document_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  document_type TEXT NOT NULL CHECK(document_type='SALE_RECEIPT'),
  template_version TEXT NOT NULL CHECK(length(template_version) BETWEEN 1 AND 32),
  template_schema_version INTEGER NOT NULL CHECK(template_schema_version=1),
  semantic_payload_json TEXT NOT NULL CHECK(json_valid(semantic_payload_json)),
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64),
  frozen_at TEXT NOT NULL,
  UNIQUE(tenant_id,document_id),
  UNIQUE(tenant_id,order_id,document_type),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id)
) STRICT;

CREATE TABLE local_print_request (
  print_request_id TEXT NOT NULL PRIMARY KEY CHECK(length(print_request_id)=26),
  tenant_id TEXT NOT NULL,
  print_job_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  document_id TEXT NOT NULL,
  request_kind TEXT NOT NULL CHECK(request_kind IN ('ORIGINAL','REPRINT')),
  reprint_no INTEGER NOT NULL CHECK(reprint_no BETWEEN 0 AND 999),
  requested_by TEXT NOT NULL CHECK(length(requested_by) BETWEEN 1 AND 64),
  requested_by_name TEXT NOT NULL CHECK(length(requested_by_name) BETWEEN 1 AND 64),
  authorization_ref TEXT NOT NULL CHECK(length(authorization_ref) BETWEEN 16 AND 128),
  reason_code TEXT NOT NULL CHECK(length(reason_code) BETWEEN 2 AND 32),
  reason_text TEXT NOT NULL CHECK(length(reason_text) BETWEEN 1 AND 256),
  idempotency_key TEXT NOT NULL CHECK(length(idempotency_key) BETWEEN 16 AND 128),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  document_sha256 TEXT NOT NULL CHECK(length(document_sha256)=64),
  execution_status TEXT NOT NULL CHECK(execution_status='BLOCKED_EXTERNAL'),
  adapter_evidence TEXT NOT NULL CHECK(adapter_evidence='BLOCKED_REAL_PRINTER'),
  requested_at TEXT NOT NULL,
  UNIQUE(tenant_id,print_request_id),
  UNIQUE(tenant_id,order_id,request_kind,reprint_no),
  UNIQUE(tenant_id,idempotency_key),
  FOREIGN KEY(tenant_id,print_job_id) REFERENCES local_print_job(tenant_id,print_job_id),
  FOREIGN KEY(tenant_id,order_id) REFERENCES local_order(tenant_id,order_id),
  FOREIGN KEY(tenant_id,document_id) REFERENCES local_receipt_document(tenant_id,document_id),
  CHECK((request_kind='ORIGINAL' AND reprint_no=0 AND reason_code='ORDER_COMPLETED')
     OR (request_kind='REPRINT' AND reprint_no>0 AND reason_code<>'ORDER_COMPLETED'))
) STRICT;

CREATE TRIGGER local_receipt_document_no_update BEFORE UPDATE ON local_receipt_document
BEGIN SELECT RAISE(ABORT,'receipt document is immutable'); END;
CREATE TRIGGER local_receipt_document_no_delete BEFORE DELETE ON local_receipt_document
BEGIN SELECT RAISE(ABORT,'receipt document is immutable'); END;
CREATE TRIGGER local_print_request_no_update BEFORE UPDATE ON local_print_request
BEGIN SELECT RAISE(ABORT,'print request is append-only'); END;
CREATE TRIGGER local_print_request_no_delete BEFORE DELETE ON local_print_request
BEGIN SELECT RAISE(ABORT,'print request is append-only'); END;
''';
}
