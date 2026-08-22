/// PAY-004 Provider 无关组合支付本地前向迁移。
///
/// 计划和份额身份冻结后不可修改；本地只追加命令/观察事件并发送 Outbox，绝不把
/// 客户端状态当作电子资金成功。现金成功仍需服务端 Order/Shift Owner 原子确认。
abstract final class Gate7bTenderSchema {
  static const int version = 13;

  static const String v13 = r'''
CREATE TABLE local_tender_plan (
  plan_id TEXT NOT NULL CHECK(length(plan_id)=26),
  tenant_id TEXT NOT NULL,
  order_id TEXT NOT NULL CHECK(length(order_id)=26),
  order_snapshot_sha256 TEXT NOT NULL CHECK(length(order_snapshot_sha256)=64),
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL CHECK(length(terminal_id)=26),
  shift_id TEXT NOT NULL CHECK(length(shift_id)=26),
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  status TEXT NOT NULL CHECK(status IN ('FROZEN','COLLECTING','UNKNOWN','PAID','FAILED','CANCELLED','MANUAL_RECOVERY_REQUIRED')),
  receivable_amount_minor INTEGER NOT NULL CHECK(receivable_amount_minor>0),
  succeeded_amount_minor INTEGER NOT NULL DEFAULT 0 CHECK(succeeded_amount_minor>=0),
  occupied_amount_minor INTEGER NOT NULL DEFAULT 0 CHECK(occupied_amount_minor>=succeeded_amount_minor),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  allocation_count INTEGER NOT NULL CHECK(allocation_count BETWEEN 2 AND 8),
  content_sha256 TEXT NOT NULL CHECK(length(content_sha256)=64),
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  idempotency_key TEXT NOT NULL CHECK(length(idempotency_key) BETWEEN 16 AND 128),
  correlation_id TEXT NOT NULL CHECK(length(correlation_id)=26),
  record_version INTEGER NOT NULL DEFAULT 1 CHECK(record_version>0),
  frozen_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,plan_id),
  UNIQUE(tenant_id,order_id),
  UNIQUE(tenant_id,command_id),
  UNIQUE(tenant_id,idempotency_key),
  CHECK(occupied_amount_minor<=receivable_amount_minor)
) STRICT;

CREATE TABLE local_tender_allocation (
  allocation_id TEXT NOT NULL CHECK(length(allocation_id)=26),
  tenant_id TEXT NOT NULL,
  plan_id TEXT NOT NULL CHECK(length(plan_id)=26),
  sequence_no INTEGER NOT NULL CHECK(sequence_no BETWEEN 1 AND 8),
  tender_type TEXT NOT NULL CHECK(tender_type IN ('CASH','ELECTRONIC')),
  status TEXT NOT NULL CHECK(status IN ('PLANNED','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','CANCELLED')),
  amount_minor INTEGER NOT NULL CHECK(amount_minor>0),
  currency TEXT NOT NULL CHECK(currency='CNY'),
  allocation_sha256 TEXT NOT NULL CHECK(length(allocation_sha256)=64),
  owner_fact_id TEXT CHECK(owner_fact_id IS NULL OR length(owner_fact_id)=26),
  record_version INTEGER NOT NULL DEFAULT 1 CHECK(record_version>0),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,allocation_id),
  UNIQUE(tenant_id,plan_id,sequence_no),
  FOREIGN KEY(tenant_id,plan_id) REFERENCES local_tender_plan(tenant_id,plan_id)
) STRICT;

CREATE TABLE local_tender_event (
  event_id TEXT NOT NULL PRIMARY KEY CHECK(length(event_id)=26),
  tenant_id TEXT NOT NULL,
  plan_id TEXT NOT NULL CHECK(length(plan_id)=26),
  allocation_id TEXT CHECK(allocation_id IS NULL OR length(allocation_id)=26),
  event_type TEXT NOT NULL CHECK(event_type IN ('PLAN_FROZEN','COLLECT_REQUESTED','BLOCKED_EXTERNAL','OBSERVED')),
  status TEXT NOT NULL,
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  occurred_at TEXT NOT NULL,
  FOREIGN KEY(tenant_id,plan_id) REFERENCES local_tender_plan(tenant_id,plan_id)
) STRICT;

CREATE INDEX idx_local_tender_plan_status
  ON local_tender_plan(tenant_id,store_id,status,updated_at,plan_id);
CREATE INDEX idx_local_tender_allocation_status
  ON local_tender_allocation(tenant_id,plan_id,status,sequence_no);

CREATE TRIGGER local_tender_plan_identity_immutable BEFORE UPDATE ON local_tender_plan
WHEN OLD.plan_id<>NEW.plan_id OR OLD.tenant_id<>NEW.tenant_id OR OLD.order_id<>NEW.order_id
  OR OLD.order_snapshot_sha256<>NEW.order_snapshot_sha256 OR OLD.store_id<>NEW.store_id
  OR OLD.terminal_id<>NEW.terminal_id OR OLD.shift_id<>NEW.shift_id
  OR OLD.business_date<>NEW.business_date OR OLD.receivable_amount_minor<>NEW.receivable_amount_minor
  OR OLD.currency<>NEW.currency OR OLD.allocation_count<>NEW.allocation_count
  OR OLD.content_sha256<>NEW.content_sha256 OR OLD.command_id<>NEW.command_id
  OR OLD.idempotency_key<>NEW.idempotency_key OR OLD.correlation_id<>NEW.correlation_id
  OR OLD.frozen_at<>NEW.frozen_at
BEGIN SELECT RAISE(ABORT,'tender plan frozen identity changed'); END;

CREATE TRIGGER local_tender_allocation_identity_immutable BEFORE UPDATE ON local_tender_allocation
WHEN OLD.allocation_id<>NEW.allocation_id OR OLD.tenant_id<>NEW.tenant_id OR OLD.plan_id<>NEW.plan_id
  OR OLD.sequence_no<>NEW.sequence_no OR OLD.tender_type<>NEW.tender_type
  OR OLD.amount_minor<>NEW.amount_minor OR OLD.currency<>NEW.currency
  OR OLD.allocation_sha256<>NEW.allocation_sha256 OR OLD.created_at<>NEW.created_at
BEGIN SELECT RAISE(ABORT,'tender allocation frozen identity changed'); END;

CREATE TRIGGER local_tender_plan_no_delete BEFORE DELETE ON local_tender_plan
BEGIN SELECT RAISE(ABORT,'tender plan cannot be deleted'); END;
CREATE TRIGGER local_tender_allocation_no_delete BEFORE DELETE ON local_tender_allocation
BEGIN SELECT RAISE(ABORT,'tender allocation cannot be deleted'); END;
CREATE TRIGGER local_tender_event_no_update BEFORE UPDATE ON local_tender_event
BEGIN SELECT RAISE(ABORT,'tender event is append-only'); END;
CREATE TRIGGER local_tender_event_no_delete BEFORE DELETE ON local_tender_event
BEGIN SELECT RAISE(ABORT,'tender event is append-only'); END;
''';
}
