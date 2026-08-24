# T2 完整 Alpha UAT 与发布启动条件冻结报告

## 1. 当前结论

- `T2-UAT-001 = DRAFT / NO_GO_FULL_ALPHA`
- `T2-REL-001 = DRAFT / NO_GO_PRODUCTION_AND_COMMERCIAL`
- `T2-RDY-001 = ACCEPTED` 只提供内部候选输入，不改变上述决定。

## 2. 完整 Alpha 必要条件

| 条件 | 验收 | 当前 |
|---|---|---|
| PAY | 11/11 VERIFIED_DOCUMENT + 独立执行批准 | BLOCKED 0/11 |
| HWD | 主认证/兼容 2/2 VERIFIED_DOCUMENT + 独立执行批准 | BLOCKED 0/2 |
| PRN | 六类外设 6/6 VERIFIED_DOCUMENT + 独立执行批准 | BLOCKED 0/6 |
| PAR | 5 家目标、3 家书面意愿和数据/对账/现场包 + 独立执行批准 | BLOCKED 0/5、0/3 |
| 环境 | 与开发/生产隔离的 Server/Web/MySQL/Redis/对象存储/POS/证据库 | FROZEN_NOT_PROVISIONED |
| RACI | 发起人、产品、QA、支付、设备外设、伙伴、安全隐私、SRE、发布具名签署 | ROLE_DEFINED_NOT_NAMED |
| 安全 | Secret/证书受控、最小权限、停止开关、恢复点、日志脱敏 | NOT_EXECUTED |
| 数据 | 授权、最小化、保留/撤回/删除和环境销毁证明 | NOT_EXECUTED |
| 缺陷 | 启动和接受时 P0=0、P1=0；不自动重跑掩盖 Flaky | NOT_EXECUTED |

四轨必须分别获批，禁止一次性打包解除。完整 Alpha 仍覆盖三业态、主支付/退款、真实主机/
外设、离线同步、迁移、升级回退、备份恢复、性能、安全、逐日对账和伙伴旧系统对账。

## 3. 发布必要条件

除完整 Alpha `ACCEPTED` 外，还必须满足：

1. `T2-LIC-001` 三项全部关闭，最终 SBOM、许可证、NOTICE 和发布 BOM 一致；
2. Release commit、版本、变更说明、正式 JAR/Web/生产签名 APK/镜像摘要和来源可追溯；
3. 使用批准的真实签名/KMS/Secret 边界，不得复用 RDY-001 临时 CI 私钥；
4. 生产式空环境迁移、备份、PITR、恢复、灰度、回退/前向修复和监控告警有真实证据；
5. 数据迁移、客服/实施、RMA、隐私、安全事件和发布停止手册签署；
6. 项目发起人、产品、架构、QA、安全、SRE、法务/发布联合 Go/No-Go。

## 4. 强制停止规则

任何 P0/P1、资金/库存/租户守恒异常、UNKNOWN 未收敛、真实资料摘要漂移、Secret/PII 泄漏、
许可证未关闭、恢复/回退失败或证据等级不符时立即 `NO_GO`。报表、Fake、合成恢复和内部候选
不得覆盖权威事实或提升外部证据等级。

## 5. 本轮执行计数

Provider 网络、真实资金、设备命令、外设命令、伙伴联系、现场、完整 Alpha、生产部署、
商业 tag 和商业声明全部为 0。
