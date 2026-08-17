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

    <el-card shadow="hover">
      <el-form :inline="true" :model="query" label-width="80px">
        <el-form-item label="报表类型">
          <el-select v-model="reportType" style="width: 190px">
            <el-option label="销售与收银" value="SALES_DAILY" />
            <el-option label="库存与成本" value="INVENTORY_COST_DAILY" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" />
        </el-form-item>
        <el-form-item label="门店 ID"><el-input v-model="query.storeId" style="width: 140px" /></el-form-item>
        <el-form-item v-if="reportType === 'SALES_DAILY'" label="终端"><el-input v-model="query.terminalId" clearable /></el-form-item>
        <el-form-item v-if="reportType === 'INVENTORY_COST_DAILY'" label="仓库 ULID"><el-input v-model="query.warehouseId" clearable /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['report:operation:read']" type="primary" icon="Search" :loading="loading" @click="loadReport">查询</el-button>
          <el-button v-hasPermi="['report:export:request']" icon="Download" @click="openExport">安全导出</el-button>
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

      <el-table v-else v-loading="loading" :data="inventoryRows" border>
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
    </el-card>

    <el-dialog v-model="exportDialog" title="安全导出与状态机" width="760px">
      <el-alert type="info" :closable="false" class="mb-3">导出最多 31 个业务日、10 万行；高风险导出由非申请人独立审批。</el-alert>
      <el-form label-width="110px">
        <el-form-item label="导出 ID"><el-input v-model="exportId" maxlength="26" /></el-form-item>
        <el-form-item label="门店范围"><el-input v-model="exportStoreIds" placeholder="11,12" /></el-form-item>
        <el-form-item label="字段白名单">
          <el-checkbox-group v-model="exportFields">
            <el-checkbox v-for="field in availableFields" :key="field.value" :label="field.value">{{ field.label }}</el-checkbox>
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
        <el-button v-hasPermi="['report:export:request']" type="primary" @click="submitExport">申请</el-button>
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
  </div>
</template>

<script setup name="ReportingOperation" lang="ts">
import { saveAs } from 'file-saver';
import {
  approveReportExport,
  downloadReportExport,
  generateReportExport,
  getReportExport,
  issueReportDownloadToken,
  queryInventoryCostDaily,
  querySalesDaily,
  requestReportExport
} from '@/api/reporting';
import { newUlid, parseStoreIds } from '@/api/reporting/contract';
import type { ExportVO, InventoryCostDailyVO, ReportQuery, ReportType, SalesDailyVO } from '@/api/reporting/types';

const today = new Date().toISOString().slice(0, 10);
const reportType = ref<ReportType>('SALES_DAILY');
const dateRange = ref<[string, string]>([today, today]);
const query = reactive<ReportQuery>({ fromDate: today, toDate: today, storeId: '', terminalId: '', warehouseId: '' });
const loading = ref(false);
const salesRows = ref<SalesDailyVO[]>([]);
const inventoryRows = ref<InventoryCostDailyVO[]>([]);
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
const availableFields = computed(() => (reportType.value === 'SALES_DAILY' ? salesFields : inventoryFields));

const normalizedQuery = (): ReportQuery => ({
  fromDate: dateRange.value[0],
  toDate: dateRange.value[1],
  storeId: query.storeId,
  ...(reportType.value === 'SALES_DAILY' ? { terminalId: query.terminalId || undefined } : { warehouseId: query.warehouseId || undefined })
});

const loadReport = async () => {
  loading.value = true;
  try {
    if (reportType.value === 'SALES_DAILY') salesRows.value = (await querySalesDaily(normalizedQuery())).data;
    else inventoryRows.value = (await queryInventoryCostDaily(normalizedQuery())).data;
  } finally {
    loading.value = false;
  }
};

const openExport = () => {
  exportId.value = newUlid();
  exportStoreIds.value = String(query.storeId || '');
  exportFields.value = ['businessDate', 'storeId'];
  currentExport.value = undefined;
  exportDialog.value = true;
};

const submitExport = async () => {
  currentExport.value = (
    await requestReportExport({
      exportId: exportId.value,
      reportType: reportType.value,
      fromDate: dateRange.value[0],
      toDate: dateRange.value[1],
      storeIds: parseStoreIds(exportStoreIds.value),
      fields: exportFields.value,
      correlationId: newUlid()
    })
  ).data;
  ElMessage.success('导出申请已提交');
};

const refreshExport = async () => {
  currentExport.value = (await getReportExport(exportId.value)).data;
};

const approveExport = async () => {
  if (!currentExport.value) return;
  currentExport.value = (await approveReportExport(exportId.value, true, approvalReason.value, currentExport.value.version, newUlid())).data;
};

const generateExport = async () => {
  if (!currentExport.value) return;
  currentExport.value = (await generateReportExport(exportId.value, currentExport.value.version, newUlid())).data;
};

const downloadExport = async () => {
  const token = (await issueReportDownloadToken(exportId.value)).data;
  const artifact = await downloadReportExport(exportId.value, token.token);
  saveAs(artifact.data, `jshpos-report-${exportId.value}.csv`);
};
</script>
