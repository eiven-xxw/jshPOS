# T0 上游差异

| 范围 | 差异 | 原因 | 需求 |
|---|---|---|---|
| `package.json` / `pnpm-lock.yaml` | 私有包身份、固定Node/pnpm和全部浮动依赖、增加lint/typecheck/test命令 | 可重复构建与质量门禁 | T0-WEB-001 |
| `src/**` | 使用上游自身ESLint/Prettier执行一次机械修复 | 上游5.6.2标签存在可自动修复的格式错误 | T0-WEB-001 |
| `TopBar/index.vue` | 标记 `script setup lang=ts` | 满足项目统一TypeScript规则 | T0-WEB-001 |
| `src/**` / `vite.config.ts` | 修复上游在 `vue-tsc` 下暴露的组件解析、路由、流程、日期范围和配置类型问题 | 在不降低TypeScript严格度的前提下建立类型门禁 | T0-WEB-001 |
| `package.json` / `pnpm-lock.yaml` | 升级Quill桥接、Axios、ECharts、js-cookie、Vite、Vitest并覆盖存在已知漏洞的传递依赖 | 2026-08-15全量依赖审计达到已知漏洞零项 | T0-LIC-001 |
| `Editor/index.vue` | 使用Quill 2实例API替代已废弃的静态调用 | 配合无已知XSS漏洞的Quill 2基线 | T0-LIC-001 |

升级上游时应先在独立分支重放格式化并审查功能差异，不能把机械改动与业务变更混在同一提交。
