# T1 Week 2 内部 STATIC/FAKE 探针

本目录使用 Python 标准库运行可重复的 SQLite、Inbox、租户、数据包、升级和支付 Fake 风险验证。它不导入服务端/POS 正式模块，不访问网络，不读取任何外部密钥，也不创建商业领域实现。

## 执行边界

- SQLite 表统一使用 `syn_` 前缀，事实、意图和 Outbox 都是合成占位对象；
- 进程 `kill` 由隔离子进程的 `os._exit` 模拟，不等于 Android 物理断电；
- 10k/100k 包在临时目录即时生成，测试 MAC 使用公开固定测试向量，不是生产签名设计；
- 100k 指标只是 GitHub Runner 趋势，不是主认证 Android 机性能结论；
- 支付只运行五家统一 Fake 矩阵，网络模块、沙箱和真实资金全部禁止；
- 所有输出只能标记为 `STATIC` 或 `FAKE`。

CI 证据写入忽略版本控制的 `artifacts/t1/week2/`，并由独立 evidence Job 生成 SHA-256 清单。
