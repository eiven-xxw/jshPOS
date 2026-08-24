# CR-T2G8C-006：T2-SEC-002 首轮 CI 失败保留

- 决策：`FAILURE_PRESERVED_AND_FIXED_WITHOUT_THRESHOLD_CHANGE`
- Requirement：`T2-SEC-002`、`T2-CI-001`
- 失败 Run：`32687096984`
- 失败提交：`4481d350025d4d116e4b516118da9580283feea2`

## 失败结论

治理双平台、Server、Web、MySQL 与 Flutter 双平台均通过；Security 在下载 Server/Flutter Artifact 后执行仓库范围检查，把 CI 临时目录 `downloaded/` 误识别为越界源码，Evidence 按依赖规则跳过。Trivy 漏洞扫描和许可证策略在失败点前已经通过。

## 修复边界

将 T2-SEC-002 仓库范围门禁前置到 Artifact 下载之前，并把其机器结果复制进 Security 证据目录。未扩张源码允许清单，未排除应受审计的源码目录，未跳过扫描、降低阈值、重跑单个失败 Job 或改变需求状态；修复提交必须从头执行完整工作流。
