# CR-T2G8C-017：PERF-002 证据聚合生产者同名冲突

## 事实

修复提交 `cd6867a83388b300889ecbd93e991662cbd1a52e` 的 GitHub Run `32704925873` 已通过治理双平台、Server、Owner/MySQL、Web、Flutter 双平台/Android、POS 性能、正式运行栈和完整 Security。Evidence 将十类 Artifact 归一化到具名生产者目录后，聚合器仍从整个 bundle 递归查找 `TEST-*MigrationCapacityTrendTest.xml`；Server 全量测试与 Owner 容量测试各保留一份同名合格报告，聚合器按唯一性规则得到 2 份并失败。

## 根因与处置

根因是聚合查询缺少生产者来源限定，不是测试重复执行失败或证据缺失。处置为将正式运行栈证据固定读取 `formal-runtime` 生产者、Owner 容量证据固定读取 `owner-capacity` 生产者、POS 指标固定读取 `pos-performance` 生产者，并在每个具名来源内继续要求恰好一份匹配文件。未删除 Server 的同名报告，也未采用“取第一份”掩盖歧义。

## 边界

- 失败 Run `32704925873` 和十类已上传 Artifact 保留，不重跑失败 Job；
- 证据来源更严格：生产者目录缺失、来源内缺失或来源内重复仍立即失败；
- 业务算法、Owner 事实、API、迁移、依赖、性能阈值和安全门禁变化均为 0；
- 修复提交必须从治理开始执行新的完整工作流；`T2-PERF-002` 继续 `VERIFIED`，`T2-RDY-001` 继续 `DRAFT`。
