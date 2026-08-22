export type MigrationDataType = 'CATALOG' | 'SUPPLIER' | 'OPENING_INVENTORY' | 'MEMBER';

export interface MigrationBatchView {
  batchId: string;
  state: string;
  requestedTypes: MigrationDataType[];
  fileCount: number;
  validRowCount: number;
  errorCount: number;
  approvalCount: number;
  appliedRowCount: number;
  version: number;
  requestSha256: string;
  correlationId: string;
  createdAt: string;
}

export interface MigrationFileView {
  fileId: string;
  batchId: string;
  dataType: MigrationDataType;
  mappingVersion: string;
  sourceSha256: string;
  safeFilename: string;
  charset: string;
  rowCount: number;
  errorCount: number;
  state: string;
  sourceSystem: string;
  custodyReference: string;
}

export interface MigrationPreflightError {
  errorId: string;
  dataType: MigrationDataType;
  rowNumber: number;
  fieldName: string;
  errorCode: string;
  maskedMessage: string;
}

export interface MigrationPreflightErrorPage {
  page: number;
  pageSize: number;
  total: number;
  records: MigrationPreflightError[];
}

export interface MigrationCheckpoint {
  ownerType: string;
  dataType: MigrationDataType;
  appliedCount: number;
  failedCount: number;
  resultSha256: string;
  state: string;
}

export interface MigrationBatchDetail {
  batch: MigrationBatchView;
  files: MigrationFileView[];
  errors: MigrationPreflightError[];
  checkpoints: MigrationCheckpoint[];
}

export interface MigrationReconciliation {
  batchId: string;
  expectedRows: number;
  appliedRows: number;
  differenceCount: number;
  resultSha256: string;
  go: boolean;
}
