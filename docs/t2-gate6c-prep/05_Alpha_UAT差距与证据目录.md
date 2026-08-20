# Alpha UAT 差距与证据目录

## 1. 当前结论

完整 Alpha UAT：`NO-GO`。原因不是内部模块未完成，而是三项外部 P0 均为 `BLOCKED`。`T2-UAT-001/REL-001` 继续 `DRAFT`。

## 2. 内部已封存证据

| 范围 | 状态 | 代表证据 | 证据上限 |
|---|---|---|---|
| Gate 0—5D 核心模块 | ACCEPTED | 各 Gate 周门禁、RTM、ADR 和 GitHub CI | STATIC/UNIT/MYSQL/SQLITE/SYNTHETIC |
| 终端身份治理 | ACCEPTED | T2-TRM-001 / Gate 6A | 非 REAL_DEVICE |
| 备份恢复 | ACCEPTED | T2-BAK-001 / SYNTHETIC_RESTORE | 非生产 DR/SLA |
| 发布治理 | ACCEPTED | Gate 6B Run 32333907801、封板 Run 32334995459 | 非真实 APK/生产发布 |
| Gate 6B 最终证据 | PASS | Artifact 9394476295，SHA-256 `ef742a654ab29bd8340e909abc73b4a4160353ef654a4325e7f2b57daefdc6bc` | 内部 CI |

## 3. P0 外部差距

| Gap ID | 关联需求 | 缺失事实 | 当前状态 | 对 Alpha 的影响 |
|---|---|---|---|---|
| G6C-PAY-01 | T2-PAY-002 | 首接 Provider、授权沙箱、测试终端和官方文档 | BLOCKED | 无主支付沙箱闭环 |
| G6C-PAY-02 | T2-PAY-002 | 真实签名/回调/退款/账单/限流证据 | BLOCKED | 无外部资金状态与对账证据 |
| G6C-HWD-01 | T2-HWD-001 | 主认证机型、固件、SDK、样机 | BLOCKED | 无真实安装、能力和回退证据 |
| G6C-HWD-02 | T2-HWD-001 | 两打印、扫码、秤、钱箱、客显 | BLOCKED | 无收银外设闭环 |
| G6C-PAR-01 | T2-PAR-001 | 5 家实名目标和 3 家书面意愿 | BLOCKED | 无设计伙伴事实 |
| G6C-PAR-02 | T2-PAR-001 | 合规样本和旧系统对账条件 | BLOCKED | 无真实业务口径/UAT输入 |
| G6C-CLOUD-01 | 外部环境 | 生产 KMS、真实对象存储/CDN | BLOCKED/ASSUMPTION | 无生产发布链路 |
| G6C-DR-01 | 外部环境 | 跨区域、真实 PITR、生产切换 | BLOCKED/ASSUMPTION | 无商业 RPO/RTO/SLA |

## 4. Alpha 证据目录结构

受控证据库应按以下逻辑目录管理；仓库只保存清单和摘要：

```text
alpha-evidence/
  00-governance/
  01-internal-baseline/
  02-payment-sandbox/<provider>/<review-id>/
  03-real-device/<vendor>/<model>/<firmware>/
  04-design-partner/<opaque-partner-id>/
  05-security-privacy/
  06-migration-recovery/
  07-uat-execution/
  08-signatures-decisions/
```

每个目录必须含 manifest、文件摘要、来源/版本、证据等级、适用范围、保管人、验真人、到期/删除信息和审计引用；敏感原件不得上传 GitHub Artifact。

## 5. 完整 Alpha 最小入口

只有在以下条件全部满足并经项目发起人另行确认后，`T2-UAT-001` 才可从 DRAFT 进入 READY：三项外部 P0 独立 ACCEPTED；授权隔离环境就绪；Secret/PII/样本审批完成；主支付和硬件回退演练通过；5 家伙伴/3 家意愿满足；UAT 范围、数据、人员、时间窗和事故响应冻结；P0/P1 开放风险为 0 或存在项目发起人书面限时例外。
