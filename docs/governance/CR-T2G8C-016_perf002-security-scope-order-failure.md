# CR-T2G8C-016：PERF-002 后置安全范围检查顺序失败

## 事实

修复提交 `d74cf6c9299ea4e41ac244fa27feacfa883d119d` 的 GitHub Run `32704146499` 已通过治理双平台、Server、Owner/MySQL、Web、Flutter 双平台/Android、POS 性能和正式 MySQL/Redis/JAR/HTTP 性能作业。Security 作业先将 Server 与 Flutter Artifact 下载到工作区 `downloaded/`，再执行仓库范围检查器；检查器按设计把所有未跟踪文件视为候选变更，因此正确拒绝了下载制品，Evidence 按依赖规则跳过。

## 根因与处置

根因是安全作业步骤顺序与范围检查器的输入边界不一致，不是源码越界，也不是 Secret、PII、依赖、许可证或漏洞阈值失败。处置仅把既有 `check_t2_gate8c_perf002.py --stage closure` 前置到下载 Artifact 之前；下载后仍完整执行依赖差异、SBOM 许可证、Trivy 高危/严重漏洞、Secret 和工作流配置扫描。范围白名单、扫描器参数、严重级别和失败策略保持不变。

## 边界

- 失败 Run `32704146499` 和 Security 失败证据完整保留，不重跑失败 Job；
- 不忽略 `downloaded/`、不扩大仓库变更白名单，范围检查继续审计提交与未跟踪源码；
- 业务算法、Owner 事实、API、迁移、依赖和性能阈值变化均为 0；
- 修复提交必须从治理开始执行新的完整工作流；`T2-PERF-002` 继续 `VERIFIED`，`T2-RDY-001` 继续 `DRAFT`。
