export type DailyCloseState =
  | 'DRAFT'
  | 'PREFLIGHTING'
  | 'PREFLIGHT_FAILED'
  | 'READY'
  | 'APPROVED'
  | 'CLOSING'
  | 'CLOSED'
  | 'FAILED'
  | 'CORRECTION_REQUIRED'
  | 'COMPENSATION_REQUIRED';

export type DailyCloseCheckStatus = 'PASS' | 'FAIL' | 'BLOCKED' | 'UNAVAILABLE' | 'WARN';

/** 服务端权威日结头；页面只展示，不自行推进状态。 */
export interface DailyCloseRecord {
  closeId: string;
  storeId: number;
  businessDate: string;
  zoneId: string;
  businessDayStart: string;
  closeVersion: number;
  correctionOfCloseId?: string;
  state: DailyCloseState;
  snapshotSha256: string;
  manifestSha256: string;
  creatorUserId: number;
  preflightRun: number;
  recordVersion: number;
  createdAt: string;
  updatedAt: string;
}

export interface DailyCloseSnapshot {
  snapshotId: string;
  runNo: number;
  currency: string;
  orderCount: number;
  cancelledOrderCount: number;
  returnCount: number;
  grossMinor: number;
  discountMinor: number;
  surchargeMinor: number;
  receivableMinor: number;
  refundMinor: number;
  cashReceivedMinor: number;
  cashRefundedMinor: number;
  electronicReceivedMinor: number;
  electronicRefundedMinor: number;
  unknownPaymentCount: number;
  unknownRefundCount: number;
  shiftDifferenceMinor: number;
  contentSha256: string;
  createdAt: string;
}

export interface DailyCloseCheckpoint {
  checkpointId: string;
  runNo: number;
  ownerCode: string;
  sourceVersion: string;
  sourceSequence: number;
  sourceStatus: string;
  contentSha256: string;
}

export interface DailyClosePreflight {
  preflightId: string;
  runNo: number;
  checkCode: string;
  ownerCode: string;
  required: boolean;
  external: boolean;
  status: DailyCloseCheckStatus;
  evidenceSha256: string;
  maskedMessage: string;
}

export interface DailyCloseDifference {
  differenceId: string;
  type: string;
  state: string;
  expectedSha256: string;
  actualSha256: string;
  detailSha256: string;
  detectedAt: string;
}

export interface DailyCloseDetail {
  close: DailyCloseRecord;
  snapshots: DailyCloseSnapshot[];
  checkpoints: DailyCloseCheckpoint[];
  preflights: DailyClosePreflight[];
  differences: DailyCloseDifference[];
  approvals: Array<{ approvalId: string; approverUserId: number; reasonSha256: string; approvedAt: string }>;
  signatures: Array<{ signatureId: string; signatoryUserId: number; signatureSha256: string; signedAt: string }>;
  correctionRequired: boolean;
}
