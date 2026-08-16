# T1 Week 2 内部风险契约

本目录只服务于隔离的 `STATIC/FAKE` 技术探针。所有 `syn_*` 表、虚构租户、事件、支付状态、数据包和版本号都可整体删除，不是正式业务模型或外部接口。

| Schema | 用途 | 不构成的结论 |
|---|---|---|
| `offline-probe.schema.json` | SQLite 原子事务崩溃注入计划 | 物理断电或正式交易实现 |
| `inbox-event.schema.json` | 10k 合成事件、幂等和游标输入 | 生产同步 API |
| `tenant-attack.schema.json` | 虚构租户旁路攻击矩阵 | RuoYi 正式模块已验收 |
| `data-package-manifest.schema.json` | 10k/100k 合成包摘要、测试 MAC 与版本 | 主认证机性能或生产签名方案 |
| `upgrade-plan.schema.json` | 虚构 App/Schema 兼容与迁移故障 | APK/厂商升级认证 |
| `evidence.schema.json` | Week 2 分级证据 | SANDBOX、REAL_DEVICE、PILOT 或商业验收 |
