# T2 Gate 6G / Sprint S17 周门禁报告

## 1. 当前建议

Gate 6G 候选代码、治理、契约和本地回归已收口，等待最终 GitHub Actions 独立运行。独立门禁全部通过后建议 `CONDITIONAL PASS`，五项需求保持 `VERIFIED` 等待项目发起人决定是否更新为 `ACCEPTED`。

证据上限严格为 `INTERNAL_V1_CORE_CANDIDATE`。它不更新 `T2-UAT-001/T2-REL-001`，也不代表支付沙箱、真实硬件、真实打印、设计伙伴、完整 Alpha、试点、生产或商用。

## 2. 串行结果

| Requirement | 状态 | 主要结果 |
| --- | --- | --- |
| T2-CORE-001 | VERIFIED | 55项已接受需求、15 Owner 覆盖审计；正式组合根和失败关闭 |
| T2-API-001 | VERIFIED | 167 Controller/167 OpenAPI；793错误码；租户覆写0 |
| T2-DAT-001 | VERIFIED | MySQL V1—V51、159表；SQLite 前向迁移和恢复 |
| T2-INT-001 | VERIFIED | 16模块可执行装配、20项协作、12 seed |
| T2-E2E-003 | VERIFIED 候选 | 六销售/六退货、正式 POS HTTP+签名包+文件SQLite链、12 seed、P0/P1=0 |

## 3. 本 Sprint 关键修复

1. 清除 Order/Promotion 跨 Owner 读取 Foundation 私表，改为正式只读端口。
2. 补齐历史表中文注释、索引/精度/租户元数据审计和空环境初始化治理。
3. 新建 `jshpos-integration` 组合根并校验每项能力唯一 Bean。
4. 补齐商品包、促销包、POS 本地销售、班次、退货预检和可信会话正式适配器。
5. POS 入口改用会话绑定组合根；安全凭据缺失、包校验失败或 SQLite 装配失败均不会解锁业务。
6. 建立同 run 内部候选、失败 seed、缺陷账、性能趋势与摘要证据链。

## 4. 外部与商业边界

- `T2-PAY-002/T2-HWD-001/T2-PAR-001/T2-PRN-001` 继续 `BLOCKED`。
- `T2-UAT-001/T2-REL-001` 与 V1 汇总项继续 `DRAFT`。
- `T2-LIC-001/T2-JSH-001` 继续 `DEFERRED`。
- Provider 网络、真实资金、真实终端命令、伙伴联系、现场试点、完整 Alpha 和生产部署均为 0。

## 5. CI 回填

最终 commit、run、各 Job、Artifact ID、摘要和测试数量在独立流水线完成后回填本节；任何必需 Job 非绿色时，本报告结论自动降为 `NO-GO/IN_PROGRESS`。
