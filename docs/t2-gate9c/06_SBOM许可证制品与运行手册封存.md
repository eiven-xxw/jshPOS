# SBOM、许可证、制品与运行手册封存

## 1. 本次重新生成的制品

- Server：商业 JAR、Maven 测试报告、聚合 CycloneDX `bom.json/bom.xml`；
- Web：production dist 摘要、Lint、Typecheck、Vitest JUnit、许可证 JSON；
- Flutter Ubuntu/Windows：格式、Analyze、测试、覆盖率摘要、依赖与供应链清单；
- 治理：RTM、契约、API/页面/Owner/临时标记、关闭账、失败历史、Go/No-Go；
- 聚合：所有生产者文件的路径、大小和 SHA-256。

## 2. 运行手册

1. 从候选提交进行干净 checkout；
2. 执行 `check_t0_structure.py`、RTM、契约和 Gate 9C 范围校验；
3. 执行 Gate 9C 封板审计并保存 `seal-summary.json`、`critical-inputs.json`、
   `failure-history.json` 和 `go-no-go.json`；
4. Server 执行 `clean verify` 和聚合 SBOM；
5. Web 执行生产构建、Lint、Typecheck、单测与许可证扫描；
6. Flutter 在 Ubuntu/Windows 执行格式、Analyze、测试和供应链生成；
7. 下载七类生产者 Artifact，按目录隔离后生成最终证据索引；
8. 任一摘要、计数、状态或范围漂移即 `NO_GO`。

## 3. 已知非阻断维护项

GitHub 页面当前提示部分固定版本 Action 使用的 Node 20 运行时将被平台强制切换为 Node 24，
以及 `setup-java v4` 生命周期提示。这些不属于本 Gate P0/P1，也不得在封板阶段修改工作流
以外的依赖；后续应作为独立 P2 CI 维护项评审。

## 4. 许可证边界

本次只封存当前许可证清单和供应链证据，不改变 `T2-LIC-001 DEFERRED`。Aviator、
simple-http、MySQL Connector/J 的商业关闭仍是商业发布前 `NO_GO` 条件。
