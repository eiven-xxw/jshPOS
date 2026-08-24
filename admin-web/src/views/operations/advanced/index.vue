<template>
  <div class="p-2 gate6e-operations">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="Gate 6E 后台运营第二波（内部软件证据）"
      description="所有权威事实由服务端 Owner 校验和写入；支付沙箱、真实硬件、设计伙伴、完整 Alpha UAT 仍未解阻。"
    />

    <el-card class="mt-3" shadow="never">
      <el-row :gutter="12">
        <el-col v-for="item in ownerCards" :key="item.owner" :span="6">
          <el-card shadow="never" class="owner-card">
            <el-text tag="strong">{{ item.owner }}</el-text>
            <div class="mt-2">
              <el-tag effect="plain">{{ item.scope }}</el-tag>
            </div>
            <div class="mt-2">
              <el-text type="info">{{ item.boundary }}</el-text>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-tabs v-model="activeTab" class="mt-3" type="border-card">
      <el-tab-pane label="库存 / 成本 / 盘点" name="inventory"><InventoryCostPanel /></el-tab-pane>
      <el-tab-pane label="采购 / 收退货 / 调拨" name="supply"><SupplyPanel /></el-tab-pane>
      <el-tab-pane label="促销 / 会员" name="commercial"><CustomerPromotionPanel /></el-tab-pane>
      <el-tab-pane label="报表 / 终端 / 发布" name="governance">
        <el-row :gutter="12" class="mb-3">
          <el-col :span="12">
            <el-card shadow="never">
              <template #header><span>基础经营与对账报表</span></template>
              <p>复用 Reporting Owner 的权限脱敏、差异处理、安全导出和投影重建正式界面。</p>
              <el-button v-hasPermi="['report:operation:read']" data-testid="advanced-reporting-link" type="primary" @click="openReporting"
                >进入报表中心</el-button
              >
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card shadow="never">
              <template #header><span>可信终端登记</span></template>
              <p>复用 Terminal Registry 正式界面；本 Sprint 仍只允许虚构终端和软件生成密钥。</p>
              <el-button v-hasPermi="['terminal:registry:read']" data-testid="advanced-terminal-link" type="primary" @click="openTerminal"
                >进入终端中心</el-button
              >
            </el-card>
          </el-col>
        </el-row>
        <ReleasePanel />
      </el-tab-pane>
    </el-tabs>

    <el-alert
      class="mt-3"
      type="info"
      :closable="false"
      :title="`权限与审计边界：路由只控制展示，服务端权限与可信数据范围最终授权；页面不缓存租户标识、真实 PII 或签名材料。`"
    />
  </div>
</template>

<script setup name="AdvancedOperations" lang="ts">
import InventoryCostPanel from '../components/InventoryCostPanel.vue';
import SupplyPanel from '../components/SupplyPanel.vue';
import CustomerPromotionPanel from '../components/CustomerPromotionPanel.vue';
import ReleasePanel from '../components/ReleasePanel.vue';

const router = useRouter();
const activeTab = ref('inventory');
const ownerCards = [
  { owner: 'Inventory / Costing', scope: '流水与投影', boundary: '只追加事实、余额可重建' },
  { owner: 'Procurement / Transfer', scope: '职责链', boundary: '不直接覆盖库存或成本' },
  { owner: 'Promotion / Member', scope: '版本与隐私', boundary: '不在前端计算优惠或保存 PII' },
  { owner: 'Reporting / Terminal / Release', scope: '运维治理', boundary: '不反写事实、不下发真实设备命令' }
];

const openReporting = () => router.push('/reporting/operation');
const openTerminal = () => router.push('/terminal/registry');
</script>

<style scoped>
.gate6e-operations :deep(.el-card__header) {
  font-weight: 600;
}
.gate6e-operations p {
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.owner-card {
  min-height: 128px;
}
</style>
