<template>
  <div class="p-4 business-migration-page">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="开业资料迁移采用预检、双人审批、Owner Saga、对账后激活"
      description="原文件不会进入日志或普通制品；页面只展示文件元数据、脱敏错误和 Owner 检查点。迁移不得自动开店、发布价格或形成采购承诺。"
    />
    <OwnerPageFeedback surface-id="VUE-05" :state="phase" :failure="pageFailure" @retry="refresh" />

    <el-card class="mt-3" shadow="never">
      <template #header><span>1. 建立隔离批次</span></template>
      <el-form :inline="true">
        <el-form-item label="资料类型">
          <el-checkbox-group v-model="selectedTypes">
            <el-checkbox v-for="item in typeOptions" :key="item.value" :value="item.value">{{ item.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['migration:upload']" type="primary" :loading="loading" @click="createBatch">创建批次</el-button>
        </el-form-item>
      </el-form>
      <el-form :inline="true">
        <el-form-item label="批次 ULID"><el-input v-model="batchId" class="batch-input" /></el-form-item>
        <el-form-item
          ><el-button v-hasPermi="['migration:read']" data-testid="migration-read" :loading="loading" @click="refresh">读取</el-button></el-form-item
        >
      </el-form>
    </el-card>

    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>2. 文件登记与完整预检</span></template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="批次状态"
          ><el-tag>{{ detail.batch.state }}</el-tag></el-descriptions-item
        >
        <el-descriptions-item label="版本">{{ detail.batch.version }}</el-descriptions-item>
        <el-descriptions-item label="有效/错误行">{{ detail.batch.validRowCount }} / {{ detail.batch.errorCount }}</el-descriptions-item>
        <el-descriptions-item label="审批数">{{ detail.batch.approvalCount }} / 2</el-descriptions-item>
        <el-descriptions-item label="请求摘要" :span="4"
          ><code>{{ detail.batch.requestSha256 }}</code></el-descriptions-item
        >
      </el-descriptions>

      <el-form class="mt-3" :inline="true">
        <el-form-item label="资料类型">
          <el-select v-model="upload.dataType" class="medium-input">
            <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="字符集">
          <el-select v-model="upload.charset" class="small-input">
            <el-option label="UTF-8 CSV" value="UTF-8" />
            <el-option label="GB18030 CSV" value="GB18030" />
            <el-option label="XLSX" value="XLSX" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源系统"><el-input v-model="upload.sourceSystem" class="medium-input" maxlength="80" /></el-form-item>
        <el-form-item label="受控保管引用"><el-input v-model="upload.custodyReference" class="medium-input" maxlength="256" /></el-form-item>
        <el-form-item label="文件">
          <input aria-label="选择迁移文件" type="file" accept=".csv,.xlsx" @change="selectFile" />
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['migration:upload']" type="primary" :loading="loading" @click="uploadAndPreflight"> 上传并预检 </el-button>
        </el-form-item>
      </el-form>

      <el-table :data="detail.files" border class="mt-3" max-height="260">
        <el-table-column prop="dataType" label="类型" width="160" />
        <el-table-column prop="safeFilename" label="安全文件名" min-width="180" />
        <el-table-column prop="sourceSha256" label="SHA-256" min-width="260" show-overflow-tooltip />
        <el-table-column prop="mappingVersion" label="映射" width="90" />
        <el-table-column prop="rowCount" label="行数" width="90" />
        <el-table-column prop="errorCount" label="错误" width="90" />
        <el-table-column prop="state" label="状态" width="150" />
        <el-table-column prop="custodyReference" label="保管引用" min-width="180" show-overflow-tooltip />
      </el-table>
      <el-table v-if="errorRows.length" :data="errorRows" border class="mt-3" max-height="280">
        <el-table-column prop="dataType" label="类型" width="150" />
        <el-table-column prop="rowNumber" label="行" width="80" />
        <el-table-column prop="fieldName" label="字段" width="140" />
        <el-table-column prop="errorCode" label="错误码" width="170" />
        <el-table-column prop="maskedMessage" label="脱敏错误" min-width="260" />
      </el-table>
      <el-pagination
        v-if="errorPage && errorPage.total > errorPage.pageSize"
        class="mt-3"
        background
        layout="total, prev, pager, next"
        :total="errorPage.total"
        :page-size="errorPage.pageSize"
        :current-page="errorPage.page"
        @current-change="loadErrors"
      />
    </el-card>

    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>3. 双人审批、Saga、对账与激活</span></template>
      <el-alert type="info" :closable="false" title="两次审批必须由不同账号完成；任一阻断错误或对账差异都会失败关闭。" />
      <el-form :inline="true" class="mt-3">
        <el-form-item label="操作原因"><el-input v-model="reason" class="reason-input" maxlength="256" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['migration:approve']" type="warning" :loading="loading" @click="approve">审批当前批次</el-button>
          <el-button v-hasPermi="['migration:execute']" type="primary" :loading="loading" @click="resume">执行/恢复原 Saga</el-button>
          <el-button v-hasPermi="['migration:activate']" :loading="loading" @click="reconcile">逐 Owner 对账</el-button>
          <el-button v-hasPermi="['migration:activate']" type="success" :loading="loading" @click="activate">激活可见版本</el-button>
          <el-button v-hasPermi="['migration:activate']" type="danger" plain :loading="loading" @click="cleanup">到期清理暂存</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="detail.checkpoints" border max-height="260">
        <el-table-column prop="ownerType" label="Owner" min-width="160" />
        <el-table-column prop="dataType" label="资料类型" width="160" />
        <el-table-column prop="appliedCount" label="成功" width="90" />
        <el-table-column prop="failedCount" label="失败" width="90" />
        <el-table-column prop="state" label="状态" width="140" />
        <el-table-column prop="resultSha256" label="结果摘要" min-width="260" show-overflow-tooltip />
      </el-table>
      <el-descriptions v-if="reconciliation" :column="4" border class="mt-3">
        <el-descriptions-item label="期望行">{{ reconciliation.expectedRows }}</el-descriptions-item>
        <el-descriptions-item label="应用行">{{ reconciliation.appliedRows }}</el-descriptions-item>
        <el-descriptions-item label="差异">{{ reconciliation.differenceCount }}</el-descriptions-item>
        <el-descriptions-item label="Go/No-Go">{{ reconciliation.go ? 'GO' : 'NO-GO' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {
  activateMigration,
  approveMigration,
  cleanupMigration,
  createMigrationBatch,
  getMigrationErrors,
  getMigrationBatch,
  reconcileMigration,
  resumeMigration,
  uploadMigrationFile
} from '@/api/migration';
import { sha256Hex } from '@/api/migration/contract';
import type { MigrationBatchDetail, MigrationDataType, MigrationPreflightErrorPage, MigrationReconciliation } from '@/api/migration/types';
import { newOperationCommandId } from '@/api/operations';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import OwnerPageFeedback from '../components/OwnerPageFeedback.vue';

const { phase, failure: pageFailure, runRead, runWrite } = useRecoverablePage('MIGRATION_PAGE_FAILED');

const typeOptions: Array<{ label: string; value: MigrationDataType }> = [
  { label: '商品、条码和基础单位', value: 'CATALOG' },
  { label: '供应商', value: 'SUPPLIER' },
  { label: '期初库存', value: 'OPENING_INVENTORY' },
  { label: '会员最小资料', value: 'MEMBER' }
];
const selectedTypes = ref<MigrationDataType[]>(typeOptions.map((item) => item.value));
const batchId = ref('');
const detail = ref<MigrationBatchDetail>();
const errorPage = ref<MigrationPreflightErrorPage>();
const errorRows = computed(() => errorPage.value?.records ?? detail.value?.errors ?? []);
const reconciliation = ref<MigrationReconciliation>();
const loading = computed(() => phase.value === 'LOADING' || phase.value === 'SUBMITTING');
const selectedFile = shallowRef<File>();
const reason = ref('开业资料已完成预检、权限和对账复核');
const upload = reactive({
  dataType: 'CATALOG' as MigrationDataType,
  charset: 'UTF-8',
  sourceSystem: '虚构旧收银系统',
  custodyReference: 'CUSTODY:SYNTHETIC-ONLY'
});
const actionKeys = new Map<string, string>();

const command = (action: string) => {
  const key = `${batchId.value}:${action}`;
  if (!actionKeys.has(key)) actionKeys.set(key, newOperationCommandId());
  return { idempotencyKey: actionKeys.get(key)!, reason: reason.value, correlationId: actionKeys.get(key)! };
};

const request = async <T,>(work: () => Promise<{ data: T }>, operationIdentity?: string): Promise<T | undefined> => {
  const response = operationIdentity ? await runWrite(operationIdentity, work) : await runRead(work);
  return response?.data;
};

const createBatch = async () => {
  if (!selectedTypes.value.length) return ElMessage.warning('至少选择一类资料');
  const requestIdentity = command('create').idempotencyKey;
  const created = await request(
    () => createMigrationBatch({ dataTypes: selectedTypes.value, idempotencyKey: requestIdentity, correlationId: requestIdentity }),
    requestIdentity
  );
  if (!created) return;
  batchId.value = created.batchId;
  await refresh();
};

const refresh = async () => {
  if (!batchId.value) return ElMessage.warning('请输入或创建批次');
  const result = await request(() => getMigrationBatch(batchId.value));
  if (!result) return;
  detail.value = result;
  errorPage.value = undefined;
  if (detail.value.batch.errorCount > 0) await loadErrors(1);
};

const loadErrors = async (page: number) => {
  const result = await request(() => getMigrationErrors(batchId.value, page, 200));
  if (result) errorPage.value = result;
};

const selectFile = (event: Event) => {
  selectedFile.value = (event.target as HTMLInputElement).files?.[0];
};

const uploadAndPreflight = async () => {
  if (!selectedFile.value || !detail.value) return ElMessage.warning('请选择文件并先读取批次');
  const digest = await sha256Hex(selectedFile.value);
  const correlationId = command(`upload:${upload.dataType}:${digest}`).idempotencyKey;
  const uploaded = await request(
    () =>
      uploadMigrationFile(
        batchId.value,
        {
          dataType: upload.dataType,
          mappingVersion: '1.0',
          charset: upload.charset,
          sourceSystem: upload.sourceSystem,
          custodyReference: upload.custodyReference,
          declaredSha256: digest,
          correlationId
        },
        selectedFile.value!
      ),
    correlationId
  );
  if (!uploaded) return;
  selectedFile.value = undefined;
  await refresh();
};

const approve = async () => {
  await ElMessageBox.confirm('审批不会跳过预检；第二人必须使用不同账号。', '确认迁移审批', { type: 'warning' });
  const requestIdentity = command('approve').idempotencyKey;
  const result = await request(() => approveMigration(batchId.value, command('approve')), requestIdentity);
  if (result) detail.value = result;
};
const resume = async () => {
  await ElMessageBox.confirm('将从已保存检查点继续原 Saga，不会重新生成 Owner 命令。', '确认执行', { type: 'warning' });
  const requestIdentity = command('resume').idempotencyKey;
  const result = await request(() => resumeMigration(batchId.value, command('resume')), requestIdentity);
  if (result) detail.value = result;
};
const reconcile = async () => {
  await ElMessageBox.confirm('对账只比较各 Owner 稳定摘要和检查点，不会覆盖业务事实。确认继续？', '确认逐 Owner 对账', { type: 'warning' });
  const requestIdentity = command('reconcile').idempotencyKey;
  const result = await request(() => reconcileMigration(batchId.value, command('reconcile')), requestIdentity);
  if (!result) return;
  reconciliation.value = result;
  await refresh();
};
const activate = async () => {
  await ElMessageBox.confirm('仅在双人审批且逐 Owner 零差异时激活；不自动开店或发布价格。', '确认激活', {
    type: 'warning'
  });
  const requestIdentity = command('activate').idempotencyKey;
  const result = await request(() => activateMigration(batchId.value, command('activate')), requestIdentity);
  if (result) detail.value = result;
};
const cleanup = async () => {
  await ElMessageBox.confirm('清理加密 staging 后不可恢复，不会删除已激活 Owner 事实。', '确认到期清理', { type: 'error' });
  const requestIdentity = command('cleanup').idempotencyKey;
  const result = await request(() => cleanupMigration(batchId.value, command('cleanup')), requestIdentity);
  if (result) detail.value = result;
};
</script>

<style scoped>
.batch-input {
  width: 300px;
}
.small-input {
  width: 150px;
}
.medium-input {
  width: 230px;
}
.reason-input {
  width: 440px;
}
code {
  overflow-wrap: anywhere;
}
</style>
