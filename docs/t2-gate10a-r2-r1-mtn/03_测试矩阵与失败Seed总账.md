# 测试矩阵与失败 Seed 总账

## 1. 测试矩阵

| 层级 | 覆盖 | 退出标准 | 当前结论 |
|---|---|---|---|
| 静态范围 | 分支祖先、允许文件、19类预算、Owner依赖 | 无越界、无环、预算全通过 | PASS |
| 行为金标 | 公开 API、错误码、事务、幂等、审计、Outbox、守恒 | 摘要与基线一致 | PASS |
| Server | 35模块 Maven `clean verify`、聚合 SBOM | 测试全绿、JAR/SBOM可生成 | 待 GitHub 同窗确认 |
| Web | 构建、ESLint、类型、组件测试、许可证 | 全绿 | 待 GitHub 同窗确认 |
| Flutter | Linux/Windows 格式、分析、测试、覆盖率 | 双平台全绿 | 待 GitHub 同窗确认 |
| Android/Kotlin | 单测与 Debug APK | 全绿、APK可构建 | 待 GitHub 同窗确认 |
| 安全供应链 | 漏洞、许可证、Secret、IaC | 既有阈值不退化 | 待 GitHub 同窗确认 |
| Gate 9C 回归 | 88需求、300 API、26页面、22 Owner | 当前封板基线不退化 | 待 GitHub 同窗确认 |

## 2. 固定失败 Seed

- `G10A-R2-R1-MTN-RED-001`：整改前复杂度预算失败，整改后通过；
- `G10A-R2-R1-API-001`：公开 API/事务边界必须稳定；
- `G10A-R2-R1-ERR-001`：错误码集合摘要必须稳定；
- `G10A-R2-R1-BOUNDARY-001`：Controller/Mapper/XML/POM/迁移/配置变化或 Owner 环必须阻断；
- `G10A-R2-R1-COMPILE-001/002`：职责提取遗漏 import 的真实编译失败已保留并最小修复，未靠自动重跑掩盖。

机器权威位于 `contracts/t2/gate10a-r2-r1-mtn/failure-seeds-v1.json`。
