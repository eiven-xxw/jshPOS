# 先红后绿失败 Seed 报告

## 红基线

在实现前运行 `ReportingR2R2R2RedBaselineTest`，结果为 6 项测试、6 项失败、0 Error、0 自动重跑。
失败分别证明当时不存在有界销售页、销售 Owner 批量端口、可信上下文 v2 入口、签名游标、
同身份异摘要拒绝和游标/字节偏移恢复。

## 绿基线

实现后本地 Reporting 63 项与 Foundation 87 项全部通过；六项结构 Seed 均转绿。该转绿只代表
RPT-SALES 子范围：

- F01、F08、F12 的销售子范围已绿；
- F04、F07、F09 是跨三报表或全部候选的父 Seed，本批只关闭其 RPT-SALES 子缺口，父 Seed继续开放；
- F02/F03/F05/F06/F10/F11 未准入且没有被修改。

机器状态见 `failure-seed-ledger-v1.json`，禁止把子范围通过写成 SQL Finding 关闭。

