abstract final class S9ManualSchema {
  static const int version = 4;

  static const String v4 = r'''
CREATE TABLE local_promotion_manual_policy (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  package_version INTEGER NOT NULL CHECK(package_version>0),
  policy_version_id INTEGER NOT NULL CHECK(policy_version_id>0),
  policy_sha256 TEXT NOT NULL CHECK(length(policy_sha256)=64),
  without_approval_minor INTEGER NOT NULL CHECK(without_approval_minor>=0),
  with_approval_minor INTEGER NOT NULL CHECK(with_approval_minor>=without_approval_minor),
  minimum_line_payable_minor INTEGER NOT NULL CHECK(minimum_line_payable_minor>=0),
  maximum_rounding_minor INTEGER NOT NULL CHECK(maximum_rounding_minor>=0),
  rounding_multiples_json TEXT NOT NULL CHECK(json_valid(rounding_multiples_json)),
  installed_at TEXT NOT NULL,
  PRIMARY KEY(tenant_id,store_id,package_version,policy_version_id)
) STRICT;

CREATE TABLE local_promotion_manual_event (
  manual_event_id TEXT NOT NULL PRIMARY KEY CHECK(length(manual_event_id)=26),
  tenant_id TEXT NOT NULL,
  authorization_id TEXT NOT NULL CHECK(length(authorization_id)=26),
  event_sequence INTEGER NOT NULL CHECK(event_sequence>0),
  state TEXT NOT NULL CHECK(state IN ('PENDING_APPROVAL','APPLIED','REJECTED')),
  command_id TEXT NOT NULL CHECK(length(command_id)=26),
  request_sha256 TEXT NOT NULL CHECK(length(request_sha256)=64),
  quote_id TEXT NOT NULL CHECK(length(quote_id)=26),
  store_id TEXT NOT NULL,
  terminal_id TEXT NOT NULL,
  action_type TEXT NOT NULL CHECK(action_type IN ('LINE_FIXED_PRICE','ORDER_AMOUNT_OFF','ORDER_PERCENT_OFF','ROUNDING')),
  source_line_id TEXT CHECK(source_line_id IS NULL OR length(source_line_id)=26),
  amount_or_rate TEXT NOT NULL,
  payment_method TEXT NOT NULL CHECK(payment_method IN ('CASH','NON_CASH')),
  before_fingerprint TEXT NOT NULL CHECK(length(before_fingerprint)=64),
  preview_fingerprint TEXT NOT NULL CHECK(length(preview_fingerprint)=64),
  incremental_discount_minor INTEGER NOT NULL CHECK(incremental_discount_minor>0),
  package_version INTEGER NOT NULL CHECK(package_version>0),
  policy_version_id INTEGER NOT NULL CHECK(policy_version_id>0),
  policy_sha256 TEXT NOT NULL CHECK(length(policy_sha256)=64),
  operator_user_id TEXT NOT NULL,
  approver_user_id TEXT,
  business_date TEXT NOT NULL CHECK(business_date GLOB '????-??-??'),
  reason_code TEXT NOT NULL,
  reason_text TEXT NOT NULL,
  correlation_id TEXT NOT NULL CHECK(length(correlation_id)=26),
  result_json TEXT NOT NULL CHECK(json_valid(result_json)),
  result_sha256 TEXT NOT NULL CHECK(length(result_sha256)=64),
  occurred_at TEXT NOT NULL,
  UNIQUE(tenant_id,command_id),
  UNIQUE(tenant_id,authorization_id,event_sequence),
  FOREIGN KEY(tenant_id,quote_id) REFERENCES local_promotion_quote(tenant_id,quote_id),
  FOREIGN KEY(tenant_id,store_id,package_version,policy_version_id)
    REFERENCES local_promotion_manual_policy(tenant_id,store_id,package_version,policy_version_id),
  CHECK((state='PENDING_APPROVAL' AND approver_user_id IS NULL)
    OR (state='APPLIED' AND (approver_user_id IS NULL OR approver_user_id<>operator_user_id))
    OR state='REJECTED')
) STRICT;

CREATE TRIGGER local_promotion_manual_policy_no_update
BEFORE UPDATE ON local_promotion_manual_policy
BEGIN SELECT RAISE(ABORT,'promotion manual policy is immutable'); END;
CREATE TRIGGER local_promotion_manual_policy_no_delete
BEFORE DELETE ON local_promotion_manual_policy
BEGIN SELECT RAISE(ABORT,'promotion manual policy is immutable'); END;
CREATE TRIGGER local_promotion_manual_policy_device_guard
BEFORE INSERT ON local_promotion_manual_policy
WHEN NOT EXISTS(
  SELECT 1 FROM local_device_binding b
  WHERE b.singleton_id=1 AND b.tenant_id=NEW.tenant_id AND b.store_id=NEW.store_id
)
BEGIN SELECT RAISE(ABORT,'promotion manual policy device binding mismatch'); END;
CREATE TRIGGER local_promotion_manual_event_no_update
BEFORE UPDATE ON local_promotion_manual_event
BEGIN SELECT RAISE(ABORT,'promotion manual event is immutable'); END;
CREATE TRIGGER local_promotion_manual_event_no_delete
BEFORE DELETE ON local_promotion_manual_event
BEGIN SELECT RAISE(ABORT,'promotion manual event is immutable'); END;
''';
}
