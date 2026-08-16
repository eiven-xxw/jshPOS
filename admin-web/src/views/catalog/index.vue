<template>
  <div class="p-2">
    <el-alert
      class="mb-3"
      title="Gate 1 商品价格工作台"
      description="租户仅由可信登录会话注入；金额以分为单位，数量和单位换算不使用浮点数。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-card shadow="hover">
      <template #header>
        <el-space wrap>
          <el-button icon="Refresh" @click="loadProducts">刷新商品</el-button>
          <el-button v-hasPermi="['catalog:definition:manage']" @click="definitionDialog = true">分类与单位</el-button>
          <el-button v-hasPermi="['catalog:product:manage']" type="primary" @click="productDialog = true">新增商品</el-button>
          <el-button v-hasPermi="['catalog:import:preflight']" type="warning" @click="importDialog = true">导入预检</el-button>
          <el-button v-hasPermi="['catalog:price:manage']" type="success" @click="priceDialog = true">价格版本</el-button>
        </el-space>
      </template>

      <el-table v-loading="loading" :data="products" row-key="skuId" border>
        <el-table-column prop="skuCode" label="SKU 编码" min-width="140" />
        <el-table-column prop="spuCode" label="SPU 编码" min-width="140" />
        <el-table-column prop="name" label="商品名称" min-width="200" />
        <el-table-column prop="productType" label="类型" width="120" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="操作" width="150">
          <template #default="scope">
            <el-button
              v-if="scope.row.status !== 'ACTIVE'"
              v-hasPermi="['catalog:product:manage']"
              link
              type="primary"
              @click="setState(scope.row, 'ACTIVE')"
              >启用</el-button
            >
            <el-button v-else v-hasPermi="['catalog:product:manage']" link type="warning" @click="setState(scope.row, 'INACTIVE')">停用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="definitionDialog" title="新增分类 / 单位" width="560px">
      <el-tabs>
        <el-tab-pane label="分类">
          <el-form :model="categoryForm" label-width="100px">
            <el-form-item label="编码"><el-input v-model="categoryForm.code" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="categoryForm.name" /></el-form-item>
          </el-form>
          <el-button type="primary" @click="submitCategory">保存分类</el-button>
        </el-tab-pane>
        <el-tab-pane label="单位">
          <el-form :model="unitForm" label-width="100px">
            <el-form-item label="编码"><el-input v-model="unitForm.code" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="unitForm.name" /></el-form-item>
            <el-form-item label="小数精度"><el-input-number v-model="unitForm.decimalScale" :min="0" :max="6" /></el-form-item>
          </el-form>
          <el-button type="primary" @click="submitUnit">保存单位</el-button>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <el-dialog v-model="productDialog" title="新增标准商品" width="620px">
      <el-form :model="productForm" label-width="110px">
        <el-form-item label="SPU 编码"><el-input v-model="productForm.spuCode" /></el-form-item>
        <el-form-item label="SKU 编码"><el-input v-model="productForm.skuCode" /></el-form-item>
        <el-form-item label="商品名称"><el-input v-model="productForm.name" /></el-form-item>
        <el-form-item label="分类 ID"><el-input v-model="productForm.categoryId" /></el-form-item>
        <el-form-item label="主单位 ID"><el-input v-model="primaryUnitId" /></el-form-item>
        <el-form-item label="条码"><el-input v-model="primaryBarcode" /></el-form-item>
        <el-form-item label="商品类型">
          <el-select v-model="productForm.productType" class="w-full">
            <el-option label="标准" value="STANDARD" /><el-option label="称重" value="WEIGHT" /><el-option label="计数" value="COUNT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="productDialog = false">取消</el-button><el-button type="primary" @click="submitProduct">保存</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="importDialog" title="商品导入预检（JSON 行数组）" width="760px">
      <el-input v-model="importKey" placeholder="幂等键，例如 catalog-import-20260816-001" class="mb-2" />
      <el-input v-model="importJson" type="textarea" :rows="12" placeholder='[{"rowNumber":1,...}]' />
      <el-alert v-if="importResult" class="mt-2" :type="importResult.batch.errorCount ? 'error' : 'success'" :closable="false">
        批次 {{ importResult.batch.importBatchId }}：{{ importResult.batch.rowCount }} 行，{{ importResult.batch.errorCount }} 个错误
      </el-alert>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button type="primary" @click="runPreflight">执行预检</el-button>
        <el-button v-if="importResult?.batch.state === 'PRECHECKED'" type="success" @click="commitImport">原子发布</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="priceDialog" title="价格版本" width="640px">
      <el-form :model="priceBookForm" label-width="120px">
        <el-form-item label="价格簿编码"><el-input v-model="priceBookForm.code" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="priceBookForm.name" /></el-form-item>
        <el-form-item label="版本"><el-input-number v-model="priceBookForm.versionNo" :min="1" /></el-form-item>
        <el-form-item label="范围"
          ><el-select v-model="priceBookForm.scopeType"><el-option value="TENANT_BASE" /><el-option value="STORE" /></el-select
        ></el-form-item>
        <el-form-item v-if="priceBookForm.scopeType === 'STORE'" label="门店 ID"><el-input v-model="priceBookStoreId" /></el-form-item>
      </el-form>
      <el-alert v-if="currentBook" type="success" :closable="false">当前草稿 ID：{{ currentBook.priceBookId }}</el-alert>
      <el-form v-if="currentBook" :model="priceItemForm" label-width="120px" class="mt-3">
        <el-form-item label="SKU ID"><el-input v-model="priceItemForm.skuId" /></el-form-item>
        <el-form-item label="单位 ID"><el-input v-model="priceItemForm.unitId" /></el-form-item>
        <el-form-item label="售价（分）"><el-input-number v-model="priceItemForm.amountMinor" :min="0" :precision="0" /></el-form-item>
        <el-form-item label="生效时间"
          ><el-date-picker v-model="priceItemForm.effectiveFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss[Z]"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceDialog = false">关闭</el-button>
        <el-button type="primary" @click="submitPriceBook">创建草稿</el-button>
        <el-button v-if="currentBook" type="warning" @click="submitPriceItem">添加价格项</el-button>
        <el-button v-if="currentBook" type="success" @click="publishBook">发布版本</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="CatalogWorkbench" lang="ts">
import {
  addPriceItem,
  changeProductState,
  createCategory,
  createPriceBook,
  createProduct,
  createUnit,
  listProducts,
  preflightImport,
  publishImport,
  publishPriceBook
} from '@/api/catalog';
import type { CreatePriceBookForm, CreateProductForm, ImportPreflightVO, ImportRow, PriceBookVO, ProductState, ProductVO } from '@/api/catalog/types';

const loading = ref(false);
const products = ref<ProductVO[]>([]);
const definitionDialog = ref(false);
const productDialog = ref(false);
const importDialog = ref(false);
const priceDialog = ref(false);
const categoryForm = reactive({ code: '', name: '', sortNo: 0 });
const unitForm = reactive({ code: '', name: '', decimalScale: 0 });
const primaryUnitId = ref('');
const primaryBarcode = ref('');
const productForm = reactive<CreateProductForm>({
  spuCode: '',
  skuCode: '',
  name: '',
  categoryId: '',
  productType: 'STANDARD',
  attributes: { schemaVersion: '1.0' },
  units: []
});
const importKey = ref('catalog-import-20260816-001');
const importJson = ref('[]');
const importResult = ref<ImportPreflightVO>();
const priceBookForm = reactive<CreatePriceBookForm>({ code: '', name: '', versionNo: 1, scopeType: 'TENANT_BASE' });
const priceBookStoreId = ref('');
const currentBook = ref<PriceBookVO>();
const priceItemForm = reactive({ skuId: '', unitId: '', amountMinor: 0, effectiveFrom: '2026-08-16T00:00:00Z' });

const loadProducts = async () => {
  loading.value = true;
  try {
    products.value = (await listProducts()).data;
  } finally {
    loading.value = false;
  }
};
const submitCategory = async () => {
  await createCategory({ ...categoryForm });
  ElMessage.success('分类已创建');
};
const submitUnit = async () => {
  await createUnit({ ...unitForm });
  ElMessage.success('单位已创建');
};
const submitProduct = async () => {
  const payload: CreateProductForm = {
    ...productForm,
    units: [
      {
        unitId: primaryUnitId.value,
        ratioNumerator: 1,
        ratioDenominator: 1,
        primary: true,
        barcodes: primaryBarcode.value ? [primaryBarcode.value] : []
      }
    ]
  };
  await createProduct(payload);
  productDialog.value = false;
  ElMessage.success('商品已创建');
  await loadProducts();
};
const setState = async (product: ProductVO, state: ProductState) => {
  await changeProductState(product.skuId, state, product.version);
  await loadProducts();
};
const runPreflight = async () => {
  const rows = JSON.parse(importJson.value) as ImportRow[];
  if (!Array.isArray(rows)) throw new Error('导入内容必须是 JSON 数组');
  importResult.value = (await preflightImport(importKey.value, rows)).data;
};
const commitImport = async () => {
  if (!importResult.value) return;
  importResult.value.batch = (await publishImport(importResult.value.batch.importBatchId)).data;
  ElMessage.success('导入版本已原子发布');
};
const submitPriceBook = async () => {
  currentBook.value = (
    await createPriceBook({
      ...priceBookForm,
      storeId: priceBookForm.scopeType === 'STORE' ? priceBookStoreId.value : undefined
    })
  ).data;
  ElMessage.success('价格草稿已创建');
};
const publishBook = async () => {
  if (!currentBook.value) return;
  currentBook.value = (await publishPriceBook(currentBook.value.priceBookId)).data;
  ElMessage.success('价格版本已发布');
};
const submitPriceItem = async () => {
  if (!currentBook.value) return;
  await addPriceItem(currentBook.value.priceBookId, { ...priceItemForm });
  ElMessage.success('价格项已加入草稿');
};

onMounted(loadProducts);
</script>
