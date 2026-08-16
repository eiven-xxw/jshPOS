abstract final class S3SyncSchema {
  static const int version = 2;

  static const String v2 = r'''
ALTER TABLE local_outbox ADD COLUMN lease_token TEXT;
ALTER TABLE local_outbox ADD COLUMN lease_until TEXT;
ALTER TABLE local_outbox ADD COLUMN next_attempt_at TEXT;
ALTER TABLE local_outbox ADD COLUMN last_error_code TEXT;
ALTER TABLE local_outbox ADD COLUMN last_ack_status TEXT;
ALTER TABLE local_outbox ADD COLUMN acked_at TEXT;
ALTER TABLE local_outbox ADD COLUMN updated_at TEXT;
CREATE INDEX idx_local_outbox_dispatch
  ON local_outbox(tenant_id,status,next_attempt_at,device_sequence);
CREATE INDEX idx_local_outbox_lease
  ON local_outbox(tenant_id,status,lease_until);

CREATE TABLE local_inbox (
  change_id TEXT NOT NULL PRIMARY KEY CHECK(length(change_id)=26),
  tenant_id TEXT NOT NULL,
  stream_code TEXT NOT NULL,
  event_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL CHECK(length(aggregate_id)=26),
  aggregate_version INTEGER NOT NULL CHECK(aggregate_version>0),
  payload_json TEXT NOT NULL,
  payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64),
  page_cursor TEXT NOT NULL CHECK(length(page_cursor)=26),
  status TEXT NOT NULL CHECK(status IN ('RECEIVED','APPLIED','FINAL_REJECTED')),
  received_at TEXT NOT NULL,
  applied_at TEXT,
  UNIQUE(tenant_id,change_id),
  UNIQUE(tenant_id,stream_code,aggregate_id,aggregate_version,event_type),
  CHECK((status='APPLIED' AND applied_at IS NOT NULL) OR status<>'APPLIED')
) STRICT;

CREATE TABLE local_sync_cursor (
  tenant_id TEXT NOT NULL,
  stream_code TEXT NOT NULL,
  applied_cursor TEXT,
  applied_page_sha256 TEXT CHECK(applied_page_sha256 IS NULL OR length(applied_page_sha256)=64),
  applied_change_ids_json TEXT,
  remote_acked_cursor TEXT,
  ack_retry_count INTEGER NOT NULL DEFAULT 0 CHECK(ack_retry_count>=0),
  last_error_code TEXT,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,stream_code)
) STRICT;

CREATE TABLE local_sync_dead_letter (
  dead_letter_id TEXT NOT NULL PRIMARY KEY CHECK(length(dead_letter_id)=26),
  tenant_id TEXT NOT NULL,
  direction TEXT NOT NULL CHECK(direction IN ('OUTBOUND','INBOUND')),
  source_id TEXT NOT NULL CHECK(length(source_id)=26),
  failure_code TEXT NOT NULL,
  failure_summary TEXT NOT NULL CHECK(length(failure_summary)<=512),
  status TEXT NOT NULL CHECK(status IN ('OPEN','RETRYING','RESOLVED')),
  attempt_count INTEGER NOT NULL CHECK(attempt_count>=0),
  created_at TEXT NOT NULL,
  resolved_at TEXT,
  UNIQUE(tenant_id,direction,source_id)
) STRICT;

CREATE TABLE local_sync_alert (
  tenant_id TEXT NOT NULL,
  alert_code TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('OPEN','RESOLVED')),
  observed_value INTEGER NOT NULL CHECK(observed_value>=0),
  threshold_value INTEGER NOT NULL CHECK(threshold_value>=0),
  first_seen_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,alert_code)
) STRICT;

CREATE TABLE local_sync_control (
  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id=1),
  tenant_id TEXT NOT NULL,
  device_status TEXT NOT NULL CHECK(device_status IN ('ACTIVE','BLOCKED','REVOKED')),
  min_protocol_version TEXT NOT NULL,
  max_protocol_version TEXT NOT NULL,
  policy_version INTEGER NOT NULL CHECK(policy_version>0),
  updated_at TEXT NOT NULL
) STRICT;

CREATE TRIGGER local_inbox_no_delete BEFORE DELETE ON local_inbox
BEGIN SELECT RAISE(ABORT,'sync inbox cannot be deleted'); END;
CREATE TRIGGER local_sync_dead_letter_no_delete BEFORE DELETE ON local_sync_dead_letter
BEGIN SELECT RAISE(ABORT,'sync dead letter cannot be deleted'); END;
''';
}
