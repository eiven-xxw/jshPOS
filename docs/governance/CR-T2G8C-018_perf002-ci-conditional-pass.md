# CR-T2G8C-018：T2-PERF-002 完整 CI 与 CONDITIONAL PASS

## 结论

候选提交 `5c6859056d7a04885a0b3639384e7cf09492292b` 的 GitHub Actions Run
`32705842999` 从头完成 11 个 Job，结论全部为 `success`。T2-PERF-002 维持
`VERIFIED`，建议 `CONDITIONAL PASS` 并等待项目发起人确认。

## 不可变证据

- Run：`https://github.com/eiven-xxw/jshPOS/actions/runs/32705842999`；
- 最终证据 Artifact：`9512489205`，名称 `t2-gate8c-perf002-evidence-index`，GitHub
  SHA-256 `f913690b201a438b32c9bf7a98bfe2ce4383a307b31f442b6d8182d57ddd4883`；
- 正式运行栈 Artifact：`9512450577`，名称 `t2-gate8c-perf002-formal-runtime`，GitHub
  SHA-256 `9bff209ae194f9c33d672ab5feb2c8171a127ef641a484b9da80bb5a1aea9f75`；
- 11 个 Job：治理 Ubuntu/Windows、Server、Flutter Ubuntu/Windows 与 Android、
  Owner/MySQL 容量、Web、POS 性能、正式 MySQL/Redis/JAR/HTTP、安全和证据聚合全绿；
- 制品共 11 份，最终证据索引绑定具名生产者并校验文件摘要、证据分类和零外部执行边界。

## 量化结果

- 正式 JAR 冷启动 `64650 ms`，低于内部阈值 `90000 ms`；
- 正式 HTTP 共 `78496` 个样本且错误为 `0`；并发 16 阶段吞吐 `386.864 RPS`、
  P95 `60.597 ms`、P99 `66.710 ms`；
- 120 秒持续阶段完成 `77648` 次请求，吞吐 `646.967 RPS`、P95 `27.803 ms`、
  P99 `34.686 ms`，错误为 `0`；
- 最大应用 RSS `1398.242 MiB`、MySQL 连接 `18`、Redis used memory `1.954 MiB`、
  JVM GC 最大暂停 `40.953 ms`，均低于冻结上限；
- Redis/MySQL 暂停分别在 `3004/3001 ms` 内失败关闭，恢复检查均为 `1` 次且进程存活；
- Flutter 正式 SQLite 合成基线：扫码 1000 次 `4324 ms`、现金结算 200 笔
  `1525 ms`、同步积压 10000 条 `1479 ms`、10 万签名批次包原子安装 `6299 ms`。

## 失败与修复审计

Run `32701100510`、`32702135141`、`32702869788`、`32704146499`、
`32704925873` 的失败证据均保留，并分别由 CR-T2G8C-013 至 CR-T2G8C-017 记录。
未重跑失败 Job、未隐藏 Flaky、未删除证据、未修改已发布迁移、未新增依赖、未放宽性能
或安全阈值；每次修复都由新提交重新执行完整流水线。

## 状态与边界

两项既定性能 P1 已在内部证据范围关闭，开放性能 P0/P1 为 `0/0`。证据最高仅为
`INTERNAL_FULL_STACK_PERFORMANCE_CANDIDATE`，不代表真实设备、真实网络、生产容量、
完整 Alpha、商业验收或商业 SLA。T2-RDY-001 继续 `DRAFT`；PAY/HWD/PRN/PAR、
UAT/REL、LIC/JSH 状态及全部零外部执行边界不变。项目发起人确认前不得把
T2-PERF-002 更新为 `ACCEPTED`，不得启动发布整改。
