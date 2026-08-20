# T2 Gate 6B / Sprint S14 周门禁报告

> 报告状态：`IN_PROGRESS — 等待 GitHub 完整 CI 与证据封存`
>
> 分支：`t2/gate6b-sprint14-20260820`
>
> 基线起点：`ec26f18715c205f29f7b9ec7c8a6478aeffcc557`

## 1. 阶段结论

T2-UPG-001 已完成设计准入并进入正式实现。新增 `jshpos-release` Provider 无关 Release Owner，未把 Gate 6A 设计夹具转成生产表，未发送支付网络、真实安装、固件、重启或远程命令。当前只形成内部合成证据，需求保持 `IN_PROGRESS`；完整 CI 全绿并回填 SHA/Run/Artifact 后才可更新为 `VERIFIED`。

## 2. 已实现

- 七类发布物身份、Ed25519/SHA-256/key version/commit/SBOM/兼容窗口。
- 发布、灰度和终端软件任务三套状态机；幂等、乐观锁、只追加事件/审计和数据库触发器。
- Terminal Registry 可信只读端口；真实系统版本和多 Owner 安全探针未配置时 503 失败关闭。
- 应用健康失败回退与 Schema 安全前向修复分流。
- OpenAPI、事件、JSON Schema、固定向量、MySQL V40/V41 和安全攻击夹具。

## 3. 当前本地证据

- Maven 47 个模块及依赖历史回归通过；Gate 6B 新增 15 项单元、状态机和持久化策略测试。
- 核心纳管代码行覆盖 97%+，分支覆盖 86%+，保持 90%/85% 门禁。
- MySQL 8.4、Flutter 双平台、Android、Web、安全/SBOM/许可证和最终证据索引等待 GitHub 干净执行器。

## 4. 外部边界

`T2-PAY-002/HWD-001/PAR-001` 保持 `BLOCKED`；`T2-UAT-001/REL-001` 保持 `DRAFT`。Gate 6A 接受不改变：TRM 非 REAL_DEVICE；BAK 仅 SYNTHETIC_RESTORE，RPO/RTO 非生产 SLA。

## 5. Go/No-Go

当前为 `NOT READY FOR REVIEW`。必须满足完整 CI 单次全绿、迁移/故障/攻击/全量回归、SBOM/许可证/Secret 和证据摘要可见后，才能将本报告更新为 `CONDITIONAL PASS / VERIFIED awaiting confirmation`。
