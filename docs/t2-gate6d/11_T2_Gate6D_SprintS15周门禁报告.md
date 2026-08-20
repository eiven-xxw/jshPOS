# T2 Gate 6D / Sprint S15 周门禁报告

> 报告状态：`CONDITIONAL PASS — 等待项目发起人确认`
>
> 分支：`t2/gate6d-sprint15-internal-productization`
>
> Gate 6C 起点：`aece518b9ef1057462e835ad7f98ce1aa2bffbf3`
>
> 全绿候选：`f5a450ded88dcbdb2266d77391fafe5477bb0b2a`
>
> GitHub Actions：[Run 32366111390](https://github.com/eiven-xxw/jshPOS/actions/runs/32366111390)

## 1. 阶段结论

Gate 6D 已按 `T2-POS-007 → T2-POS-008 → T2-ADM-001 → T2-E2E-001` 顺序完成设计准入、实现、独立验证和完整 CI。四项需求当前均为 `VERIFIED`，建议项目发起人接受 Gate 6D `CONDITIONAL PASS` 后再更新为 `ACCEPTED`。

证据上限为 `STATIC/UNIT/WIDGET/COMPONENT/SYNTHETIC_E2E/SOFTWARE_EXECUTION`。本阶段没有调用支付 Provider 网络、没有操作真实设备、没有现场试点、没有执行完整 Alpha UAT，也没有生产或商业可用声明。

## 2. 已实现能力

- Flutter POS：正式应用壳、可信虚构终端失败关闭、员工登录/权限、门店/终端/班次/业务日上下文和安全退出。
- Flutter 收银：扫码/搜索、购物篮、促销报价、受权人工优惠、挂取单、现金原子结算、成交结果、打印任务预览、同步状态、大触控、快捷键、防重复点击与错误恢复。
- Vue 后台：经营工作台、组织门店、员工数据范围、商品/条码/多单位/分类/品牌、价格版本和批量导入正式运营 UI；只调用正式 API。
- 内部合成闭环：六条两租户三业态现金旅程组合既有 Owner 机器证据，核对金额、订单、同步、库存、成本、报表和班次守恒。

## 3. CI 与量化结果

GitHub Run `32366111390` 的九个 Job 全部成功，无跳过失败测试、自动重跑、绿色占位或阈值下调：

| Job | 结果 | 核验重点 |
|---|---|---|
| governance | PASS | AGENTS/ADR/RTM/CR、顺序准入、边界、迁移零变更和外部执行 0 |
| server | PASS | 全量服务端回归、覆盖率、JAR 和 CycloneDX SBOM |
| mysql | PASS | MySQL 8.4 与全部已发布迁移验证 |
| pos-linux | PASS | Flutter 全量测试/覆盖率、SQLite、Kotlin、Android debug APK、供应链 |
| pos-windows | PASS | Windows Flutter 全量回归及机器报告 |
| admin-web | PASS | 20 项测试、类型检查、ESLint、生产构建、依赖审计与许可证 |
| security | PASS | HIGH/CRITICAL 漏洞、Secret、IaC、双 SBOM 和许可证策略 |
| internal-e2e | PASS | 12 个 Owner 套件、两平台各 6 个冻结用例、3 个 Web 用例及六条现金旅程 |
| evidence | PASS | 汇总八类上游制品、逐文件 SHA-256 和证据等级边界 |

## 4. 封存制品

| Artifact | ID | GitHub SHA-256 |
|---|---:|---|
| t2-gate6d-evidence-index | 9405603585 | `0b55e9238b297b3ab2a535f0845cf91eb2fb56d0d529e63061c4155a28041126` |
| t2-gate6d-governance | 9405337054 | `3b5349dd39ec6d8f61121e62edef13e22aa23106c574164896eb8751017b85f0` |
| t2-gate6d-internal-e2e | 9405572867 | `cf36abf7e049174b68e5835ea0bb2b3be97e372d0f728df7db4d12de9f3955d5` |
| t2-gate6d-mysql | 9405385923 | `fac47049949f70837ae04baf6e36646cee1d777bd70ea7253fec6a058b631750` |
| t2-gate6d-pos-linux | 9405473754 | `4ce95f6f1cd7872716e29586666845ad163ce45a16db20aef68e6a6137de8488` |
| t2-gate6d-pos-windows | 9405406076 | `d7bc4b956815e539db74236af4da6650c390aef55f954cfeb31dd04cb9c633a8` |
| t2-gate6d-security | 9405574190 | `c3c6a40edc7f8167ae5fc4b1f403c192dea7bf6986cc37208841e678442c06f5` |
| t2-gate6d-server | 9405558506 | `7787fd9f45ef556658d5ceedd29df7fcb4fc6aaad5453c4ca58217aeaac27aa2` |
| t2-gate6d-web | 9405364611 | `ccc61359cfc9d58d3423dea6011cf9fea64fd5b8a9fd4d496d1795ffad5bebc9` |

## 5. 失败与修复审计

- Run `32363250672` 暴露 Web JUnit 文件发现及限定名匹配缺陷。
- Run `32364716110` 暴露 Flutter 合法扩展事件数组解析缺陷。
- 两次均由证据组合 Job 失败关闭；修复后从新提交完整重跑。最终规则要求 JUnit 无失败/错误/跳过，并分别要求 Linux/Windows 冻结 Flutter 用例成功完成且未跳过。

## 6. 未解除边界

- `T2-PAY-002`、`T2-HWD-001`、`T2-PAR-001` 继续 `BLOCKED`。
- `T2-UAT-001`、`T2-REL-001` 继续 `DRAFT`；本报告不等于完整 Alpha。
- `T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`。
- 打印只形成任务、预览和 Fake Adapter；支付只覆盖现金与 Provider 无关内部核心；真实扫码、打印、秤、钱箱、客显、APK 安装、Provider 回调/账单均未验证。

## 7. 评审建议

建议项目发起人接受 Gate 6D `CONDITIONAL PASS`，并授权将 `T2-POS-007`、`T2-POS-008`、`T2-ADM-001`、`T2-E2E-001` 从 `VERIFIED` 更新为 `ACCEPTED`。未经确认，不启动 Gate 6E，不改变外部 P0 状态，不宣称 Alpha、可试点或可商用。
