<template>
  <div class="p-2">
    <el-alert
      class="mb-3"
      title="Gate 5D 经营报表"
      description="数据来自可重建投影；租户、组织和门店范围由服务端可信会话校验。INCOMPLETE 数据不得作为封账结论。"
      type="warning"
      :closable="false"
      show-icon
    />

    <OwnerPageFeedback
      surface-id="reporting"
      :state="phase"
      :failure="failure"
      empty-title="当前权限、门店和业务日范围内暂无报表事实"
      @retry="loadReport"
    />

    <el-card shadow="hover">
      <el-form :inline="true" :model="query" label-width="80px">
        <el-form-item label="报表类型">
          <el-select v-model="reportType" style="width: 190px">
            <el-option label="销售与收银" value="SALES_DAILY" />
            <el-option label="库存与成本" value="INVENTORY_COST_DAILY" />
            <el-option label="支付退款内部对账" value="PAYMENT_RECONCILIATION" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" />
        </el-form-item>
        <el-form-item label="门店 ID"><el-input v-model="query.storeId" data-testid="reporting-store" style="width: 140px" /></el-form-item>
        <el-form-item v-if="reportType === 'SALES_DAILY'" label="终端"><el-input v-model="query.terminalId" clearable /></el-form-item>
        <el-form-item v-if="reportType === 'INVENTORY_COST_DAILY'" label="仓库 ULID"><el-input v-model="query.warehouseId" clearable /></el-form-item>
        <el-form-item v-if="reportType === 'PAYMENT_RECONCILIATION'" label="差异">
          <el-select v-model="query.differenceType" clearable style="width: 190px">
            <el-option label="已匹配" value="MATCHED" /><el-option label="缺账单" value="MISSING_BILL" />
            <el-option label="缺内部事实" value="MISSING_INTERNAL" /><el-option label="金额差异" value="AMOUNT_MISMATCH" />
            <el-option label="币种差异" value="CURRENCY_MISMATCH" />
            <el-option label="状态差异" value="STATUS_MISMATCH" /><el-option label="业务日差异" value="BUSINESS_DATE_MISMATCH" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="reportType === 'PAYMENT_RECONCILIATION'" label="处理状态">
          <el-select v-model="query.handlingState" clearable style="width: 150px">
            <el-option label="已匹配" value="MATCHED" /><el-option label="待处理" value="OPEN" />
            <el-option label="已分配" value="ASSIGNED" /><el-option label="已解决" value="RESOLVED" />
            <el-option label="已忽略" value="IGNORED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="queryPermissions" data-testid="reporting-query" type="primary" icon="Search" :loading="pageBusy" @click="loadReport"
            >查询</el-button
          >
          <el-button
            v-hasPermi="['report:export:request']"
            data-testid="reporting-export-open"
            icon="Download"
            :disabled="pageBusy"
            @click="openExport"
            >安全导出</el-button
          >
        </el-form-item>
      </el-form>

      <el-table v-if="reportType === 'SALES_DAILY'" v-loading="loading" :data="salesRows" border>
        <el-table-column prop="businessDate" label="业务日" width="115" fixed />
        <el-table-column prop="storeId" label="门店" width="100" />
        <el-table-column prop="terminalId" label="终端" width="120" />
        <el-table-column prop="cashierId" label="收银员" width="100" />
        <el-table-column prop="orderCount" label="订单数" width="90" />
        <el-table-column prop="returnCount" label="退货数" width="90" />
        <el-table-column prop="grossMinor" label="原价(分)" width="110" />
        <el-table-column prop="discountMinor" label="优惠(分)" width="110" />
        <el-table-column prop="surchargeMinor" label="附加(分)" width="110" />
        <el-table-column prop="receivableMinor" label="应收(分)" width="110" />
        <el-table-column prop="cashReceivedMinor" label="现金实收(分)" width="130" />
        <el-table-column prop="shiftDifferenceMinor" label="班次差异(分)" width="130" />
        <el-table-column label="完整性" width="110" fixed="right">
          <template #default="scope">
            <el-tag :type="scope.row.projectionStatus === 'CURRENT' ? 'success' : 'danger'">{{ scope.row.projectionStatus }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="reportType === 'SALES_DAILY' && salesHasMore" class="mt-3 text-center">
        <el-button data-testid="reporting-sales-load-more" :loading="pageBusy" @click="loadMoreSales">加载下一页</el-button>
      </div>

      <el-table v-else-if="reportType === 'INVENTORY_COST_DAILY'" v-loading="loading" :data="inventoryRows" border>
        <el-table-column prop="businessDate" label="业务日" width="115" fixed />
        <el-table-column prop="storeId" label="门店" width="100" />
        <el-table-column prop="warehouseId" label="仓库" width="210" />
        <el-table-column prop="skuId" label="SKU" width="110" />
        <el-table-column prop="onHandDelta" label="在手变化" width="115" />
        <el-table-column prop="availableDelta" label="可用变化" width="115" />
        <el-table-column prop="reservedDelta" label="预占变化" width="115" />
        <el-table-column prop="inventoryValueDeltaMinor" label="库存价值(分)" width="140" />
        <el-table-column prop="cogsDeltaMinor" label="销售成本(分)" width="140" />
        <el-table-column prop="purchaseCostDeltaMinor" label="采购影响(分)" width="140" />
        <el-table-column prop="stocktakeCostDeltaMinor" label="盘点影响(分)" width="140" />
        <el-table-column prop="transferCostDeltaMinor" label="调拨影响(分)" width="140" />
        <el-table-column label="完整性" width="110" fixed="right">
          <template #default="scope">
            <el-tag :type="scope.row.projectionStatus === 'CURRENT' ? 'success' : 'danger'">{{ scope.row.projectionStatus }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-table v-else v-loading="loading" :data="paymentRows" border>
        <el-table-column prop="businessDate" label="业务日" width="115" fixed />
        <el-table-column prop="storeId" label="门店" width="90" />
        <el-table-column prop="terminalId" label="终端" width="120" />
        <el-table-column prop="factType" label="类型" width="95" />
        <el-table-column prop="internalAmountMinor" label="内部金额(分)" width="130" />
        <el-table-column prop="billAmountMinor" label="账单金额(分)" width="130" />
        <el-table-column prop="internalStatus" label="内部状态" width="110" />
        <el-table-column prop="billStatus" label="账单状态" width="110" />
        <el-table-column prop="differenceType" label="差异类型" width="190" />
        <el-table-column prop="handlingState" label="处理状态" width="110" />
        <el-table-column prop="handlerId" label="处理人" width="100" />
        <el-table-column prop="reconciliationId" label="对账 ID" min-width="230" />
        <el-table-column label="审计" width="90" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['report:payment-reconciliation:read']" link type="primary" @click="openReconciliation(scope.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="exportDialog" title="安全导出与状态机" width="760px">
      <el-alert type="info" :closable="false" class="mb-3">导出最多 31 个业务日、10 万行；高风险导出由非申请人独立审批。</el-alert>
      <el-form label-width="110px">
        <el-form-item label="导出 ID"><el-input v-model="exportId" maxlength="26" /></el-form-item>
        <el-form-item label="门店范围"><el-input v-model="exportStoreIds" placeholder="11,12" /></el-form-item>
        <el-form-item label="字段白名单">
          <el-checkbox-group v-model="exportFields">
            <el-checkbox v-for="field in availableFields" :key="field.value" :value="field.value">{{ field.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="审批原因"><el-input v-model="approvalReason" maxlength="256" /></el-form-item>
      </el-form>
      <el-descriptions v-if="currentExport" :column="2" border class="mb-3">
        <el-descriptions-item label="状态">{{ currentExport.state }}</el-descriptions-item>
        <el-descriptions-item label="预计行数">{{ currentExport.estimatedRows }}</el-descriptions-item>
        <el-descriptions-item label="需审批">{{ currentExport.approvalRequired ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ currentExport.version }}</el-descriptions-item>
        <el-descriptions-item label="制品摘要" :span="2">{{ currentExport.artifactSha256 || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="exportDialog = false">关闭</el-button>
        <el-button @click="refreshExport">刷新状态</el-button>
        <el-button
          v-hasPermi="['report:export:request']"
          data-testid="reporting-export-request"
          type="primary"
          :loading="submitting"
          @click="submitExport"
          >申请</el-button
        >
        <el-button
          v-if="currentExport?.state === 'REQUESTED' && currentExport.approvalRequired"
          v-hasPermi="['report:export:approve']"
          type="warning"
          @click="approveExport"
          >批准</el-button
        >
        <el-button
          v-if="currentExport && (currentExport.state === 'APPROVED' || (currentExport.state === 'REQUESTED' && !currentExport.approvalRequired))"
          v-hasPermi="['report:export:generate']"
          type="success"
          @click="generateExport"
          >生成制品</el-button
        >
        <el-button v-if="currentExport?.state === 'READY'" v-hasPermi="['report:export:download']" type="success" @click="downloadExport"
          >单次下载</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="reconciliationDialog" title="支付退款对账审计链" width="900px">
      <el-descriptions v-if="selectedReconciliation" :column="3" border class="mb-3">
        <el-descriptions-item label="对账 ID" :span="3">{{ selectedReconciliation.reconciliationId }}</el-descriptions-item>
        <el-descriptions-item label="差异">{{ selectedReconciliation.differenceType }}</el-descriptions-item>
        <el-descriptions-item label="处理状态">{{ selectedReconciliation.handlingState }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ selectedReconciliation.version }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="reconciliationAudit" border max-height="360">
        <el-table-column prop="occurredAt" label="时间" width="190" />
        <el-table-column prop="actionType" label="动作" width="180" />
        <el-table-column prop="fromHandlingState" label="原状态" width="100" />
        <el-table-column prop="toHandlingState" label="新状态" width="100" />
        <el-table-column prop="operatorId" label="处理人" width="100" />
        <el-table-column prop="reasonSha256" label="原因摘要" min-width="260" />
      </el-table>
      <el-form v-if="canTransition" class="mt-3" label-width="90px">
        <el-form-item label="目标状态">
          <el-select v-model="transitionState" style="width: 180px">
            <el-option v-if="selectedReconciliation?.handlingState === 'OPEN'" label="分配给本人" value="ASSIGNED" />
            <el-option label="确认解决" value="RESOLVED" />
            <el-option label="审计忽略" value="IGNORED" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理原因"><el-input v-model="transitionReason" type="textarea" maxlength="256" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reconciliationDialog = false">关闭</el-button>
        <el-button v-if="canTransition" v-hasPermi="['report:payment-reconciliation:manage']" type="primary" @click="submitReconciliationTransition"
          >提交处理</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="ReportingOperation" lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { saveAs } from 'file-saver';
import OwnerPageFeedback from '@/views/operations/components/OwnerPageFeedback.vue';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import { useStableOperationIdentity } from '@/composables/useStableOperationIdentity';
import {
  approveReportExport,
  downloadReportExport,
  generateReportExport,
  getPaymentReconciliationAudit,
  getReportExport,
  issueReportDownloadToken,
  queryInventoryCostDaily,
  queryPaymentReconciliation,
  querySalesDailyPage,
  requestReportExport,
  transitionPaymentReconciliation
} from '@/api/reporting';
import { newUlid, parseStoreIds } from '@/api/reporting/contract';
import type {
  ExportVO,
  InventoryCostDailyVO,
  PaymentReconciliationAuditVO,
  PaymentReconciliationVO,
  ReportQuery,
  ReportType,
  SalesDailyVO
} from '@/api/reporting/types';

const today = new Date().toISOString().slice(0, 10);
const reportType = ref<ReportType>('SALES_DAILY');
const dateRange = ref<[string, string]>([today, today]);
const query = reactive<ReportQuery>({ fromDate: today, toDate: today, storeId: '', terminalId: '', warehouseId: '' });
const { phase, failure, submitting, runRead, runWrite } = useRecoverablePage('REPORTING_PAGE_FAILED');
const operationKeys = useStableOperationIdentity(newUlid);
const pageBusy = computed(() => phase.value === 'LOADING' || submitting.value);
const loading = computed(() => phase.value === 'LOADING');
const salesRows = ref<SalesDailyVO[]>([]);
const salesCursor = ref<string>();
const salesHasMore = ref(false);
const inventoryRows = ref<InventoryCostDailyVO[]>([]);
const paymentRows = ref<PaymentReconciliationVO[]>([]);
const queryPermissions = computed(() =>
  reportType.value === 'PAYMENT_RECONCILIATION' ? ['report:payment-reconciliation:read'] : ['report:operation:read']
);
const reconciliationDialog = ref(false);
const selectedReconciliation = ref<PaymentReconciliationVO>();
const reconciliationAudit = ref<PaymentReconciliationAuditVO[]>([]);
const transitionState = ref<'ASSIGNED' | 'RESOLVED' | 'IGNORED'>('ASSIGNED');
const transitionReason = ref('按支付退款对账处理规范核准');
const canTransition = computed(() => ['OPEN', 'ASSIGNED'].includes(selectedReconciliation.value?.handlingState || ''));
const exportDialog = ref(false);
const exportId = ref(newUlid());
const exportStoreIds = ref('');
const exportFields = ref<string[]>(['businessDate', 'storeId']);
const approvalReason = ref('按报表导出管理规范核准');
const currentExport = ref<ExportVO>();

const salesFields = [
  { value: 'businessDate', label: '业务日' },
  { value: 'storeId', label: '门店' },
  { value: 'terminalId', label: '终端' },
  { value: 'cashierId', label: '收银员' },
  { value: 'orderCount', label: '订单数' },
  { value: 'grossMinor', label: '原价' },
  { value: 'discountMinor', label: '优惠' },
  { value: 'receivableMinor', label: '应收' },
  { value: 'cashReceivedMinor', label: '现金实收' },
  { value: 'shiftDifferenceMinor', label: '班次差异' },
  { value: 'projectionStatus', label: '完整性' }
];
const inventoryFields = [
  { value: 'businessDate', label: '业务日' },
  { value: 'storeId', label: '门店' },
  { value: 'warehouseId', label: '仓库' },
  { value: 'skuId', label: 'SKU' },
  { value: 'onHandDelta', label: '在手变化' },
  { value: 'availableDelta', label: '可用变化' },
  { value: 'reservedDelta', label: '预占变化' },
  { value: 'inventoryValueDeltaMinor', label: '库存价值' },
  { value: 'cogsDeltaMinor', label: '销售成本' },
  { value: 'projectionStatus', label: '完整性' }
];
const paymentFields = [
  { value: 'reconciliationId', label: '对账 ID' },
  { value: 'businessDate', label: '业务日' },
  { value: 'storeId', label: '门店' },
  { value: 'terminalId', label: '终端' },
  { value: 'factType', label: '类型' },
  { value: 'internalAmountMinor', label: '内部金额' },
  { value: 'billAmountMinor', label: '账单金额' },
  { value: 'internalStatus', label: '内部状态' },
  { value: 'billStatus', label: '账单状态' },
  { value: 'differenceType', label: '差异类型' },
  { value: 'handlingState', label: '处理状态' },
  { value: 'handlerId', label: '处理人' }
];
const availableFields = computed(() =>
  reportType.value === 'SALES_DAILY' ? salesFields : reportType.value === 'INVENTORY_COST_DAILY' ? inventoryFields : paymentFields
);

const normalizedQuery = (): ReportQuery => ({
  fromDate: dateRange.value[0],
  toDate: dateRange.value[1],
  storeId: query.storeId,
  ...(reportType.value === 'SALES_DAILY'
    ? { terminalId: query.terminalId || undefined }
    : reportType.value === 'INVENTORY_COST_DAILY'
      ? { warehouseId: query.warehouseId || undefined }
      : { differenceType: query.differenceType || undefined, handlingState: query.handlingState || undefined })
});

const loadReport = async () => {
  if (reportType.value === 'SALES_DAILY') {
    salesCursor.value = undefined;
    salesHasMore.value = false;
    const result = await runRead(
      async () => (await querySalesDailyPage({ ...normalizedQuery(), limit: 200 })).data,
      (page) => page.items.length === 0
    );
    if (!result) return;
    salesRows.value = result.items;
    salesCursor.value = result.nextCursor;
    salesHasMore.value = result.hasMore;
    inventoryRows.value = [];
    paymentRows.value = [];
    return;
  }
  const result = await runRead(
    async () =>
      reportType.value === 'INVENTORY_COST_DAILY'
        ? (await queryInventoryCostDaily(normalizedQuery())).data
        : (await queryPaymentReconciliation(normalizedQuery())).data,
    (rows) => rows.length === 0
  );
  if (!result) return;
  salesRows.value = [];
  inventoryRows.value = reportType.value === 'INVENTORY_COST_DAILY' ? (result as InventoryCostDailyVO[]) : [];
  paymentRows.value = reportType.value === 'PAYMENT_RECONCILIATION' ? (result as PaymentReconciliationVO[]) : [];
};

const loadMoreSales = async () => {
  if (!salesCursor.value || pageBusy.value) return;
  const frozenCursor = salesCursor.value;
  const result = await runRead(
    async () => (await querySalesDailyPage({ ...normalizedQuery(), cursor: frozenCursor, limit: 200 })).data,
    () => false
  );
  if (!result) return;
  salesRows.value = [...salesRows.value, ...result.items];
  salesCursor.value = result.nextCursor;
  salesHasMore.value = result.hasMore;
};

const confirmImpact = async (title: string, message: string): Promise<boolean> => {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' });
    return true;
  } catch {
    return false;
  }
};

const executeWrite = async <T,>(operation: string, work: (key: string) => Promise<T>): Promise<T | undefined> => {
  const result = await runWrite(operation, () => work(operationKeys.get(operation)));
  if (result !== undefined) operationKeys.complete(operation);
  return result;
};

const openExport = () => {
  exportId.value = newUlid();
  exportStoreIds.value = String(query.storeId || '');
  exportFields.value = ['businessDate', 'storeId'];
  currentExport.value = undefined;
  exportDialog.value = true;
};

const submitExport = async () => {
  const operation = `report:export:${exportId.value}:request`;
  if (!(await confirmImpact('安全导出申请确认', `导出 ${exportId.value}；门店范围 ${exportStoreIds.value || '当前数据范围'}；最多 31 个业务日。`)))
    return;
  const result = await executeWrite(operation, (key) =>
    requestReportExport({
      exportId: exportId.value,
      reportType: reportType.value,
      fromDate: dateRange.value[0],
      toDate: dateRange.value[1],
      storeIds: parseStoreIds(exportStoreIds.value),
      fields: exportFields.value,
      correlationId: key
    })
  );
  if (!result) return;
  currentExport.value = result.data;
  ElMessage.success('导出申请已提交');
};

const refreshExport = async () => {
  const result = await runRead(() => getReportExport(exportId.value));
  if (result) currentExport.value = result.data;
};

const approveExport = async () => {
  if (!currentExport.value) return;
  if (!(await confirmImpact('独立审批确认', `批准导出 ${exportId.value}；版本 ${currentExport.value.version}；原因：${approvalReason.value}`)))
    return;
  const operation = `report:export:${exportId.value}:approve`;
  const result = await executeWrite(operation, (key) =>
    approveReportExport(exportId.value, true, approvalReason.value, currentExport.value!.version, key)
  );
  if (result) currentExport.value = result.data;
};

const generateExport = async () => {
  if (!currentExport.value) return;
  if (!(await confirmImpact('导出制品生成确认', `生成导出 ${exportId.value}；版本 ${currentExport.value.version}；制品受短期下载权限保护。`))) return;
  const operation = `report:export:${exportId.value}:generate`;
  const result = await executeWrite(operation, (key) => generateReportExport(exportId.value, currentExport.value!.version, key));
  if (result) currentExport.value = result.data;
};

const downloadExport = async () => {
  if (!(await confirmImpact('单次下载确认', `下载导出 ${exportId.value}；链接仅供当前受权操作使用并受服务端数据范围复核。`))) return;
  const operation = `report:export:${exportId.value}:download`;
  const artifact = await executeWrite(operation, async () => {
    const token = (await issueReportDownloadToken(exportId.value)).data;
    return downloadReportExport(exportId.value, token.token);
  });
  if (!artifact) return;
  saveAs(artifact.data, `jshpos-report-${exportId.value}.csv`);
};

const openReconciliation = async (row: PaymentReconciliationVO) => {
  selectedReconciliation.value = row;
  transitionState.value = row.handlingState === 'OPEN' ? 'ASSIGNED' : 'RESOLVED';
  const result = await runRead(() => getPaymentReconciliationAudit(row.reconciliationId));
  if (!result) return;
  reconciliationAudit.value = result.data;
  reconciliationDialog.value = true;
};

const submitReconciliationTransition = async () => {
  if (!selectedReconciliation.value || !transitionReason.value.trim()) return;
  if (
    !(await confirmImpact(
      '对账差异处置确认',
      `对账 ${selectedReconciliation.value.reconciliationId}；版本 ${selectedReconciliation.value.version}；目标状态 ${transitionState.value}。UNKNOWN 只能查询原事实。`
    ))
  )
    return;
  const operation = `report:reconciliation:${selectedReconciliation.value.reconciliationId}:${transitionState.value}`;
  const result = await executeWrite(operation, (key) =>
    transitionPaymentReconciliation(
      selectedReconciliation.value.reconciliationId,
      transitionState.value,
      transitionReason.value,
      selectedReconciliation.value.version,
      key
    )
  );
  if (!result) return;
  const changed = result.data;
  selectedReconciliation.value = changed;
  reconciliationAudit.value = (await getPaymentReconciliationAudit(changed.reconciliationId)).data;
  paymentRows.value = paymentRows.value.map((row) => (row.reconciliationId === changed.reconciliationId ? changed : row));
  ElMessage.success('对账处理状态和审计链已更新');
};
</script>
