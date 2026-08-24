import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import IconsResolver from 'unplugin-icons/resolver';

export default (path: any) => {
  // Vitest 多 worker 只消费已提交声明，避免并发写入 components.d.ts 造成文件锁竞态。
  const generateDeclarations = process.env.VITEST !== 'true';
  return Components({
    resolvers: [
      // 自动导入 Element Plus 组件
      ElementPlusResolver({
        importStyle: false
      }),
      // 自动注册图标组件
      IconsResolver({
        enabledCollections: ['ep']
      })
    ],
    dts: generateDeclarations ? path.resolve(path.resolve(__dirname, '../../src'), 'types', 'components.d.ts') : false
  });
};
