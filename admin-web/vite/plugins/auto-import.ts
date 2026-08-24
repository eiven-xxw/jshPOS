import AutoImport from 'unplugin-auto-import/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';

export default (path: any) => {
  // Vitest 多 worker 只消费已提交声明，不得并发改写生成物；开发/构建仍正常生成。
  const generateDeclarations = process.env.VITEST !== 'true';
  return AutoImport({
    // 自动导入 Vue 相关函数
    imports: ['vue', 'vue-router', '@vueuse/core', 'pinia'],
    eslintrc: {
      enabled: generateDeclarations,
      filepath: './.eslintrc-auto-import.json',
      globalsPropValue: true
    },
    resolvers: [
      // 自动导入 Element Plus 相关函数ElMessage, ElMessageBox... (带样式)
      ElementPlusResolver({
        importStyle: false
      })
    ],
    vueTemplate: true, // 是否在 vue 模板中自动导入
    dts: generateDeclarations ? path.resolve(path.resolve(__dirname, '../../src'), 'types', 'auto-imports.d.ts') : false
  });
};
