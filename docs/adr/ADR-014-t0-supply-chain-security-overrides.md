# ADR-014：T0 供应链安全版本覆盖与 fastjson2 兼容迁移

- 状态：Accepted
- 日期：2026-08-16

## 背景

GitHub Actions 的 Trivy 0.72.0 SBOM 门禁在 T0 候选提交 `92cf6196509ee6862cbac862e4edb45619c13112` 中发现 20 项 HIGH/CRITICAL 漏洞，涉及 fastjson 1.2.83、Netty 4.1.135.Final、Fory 0.13.x、HttpCore 5.3.6、Bouncy Castle 1.83 和 PostgreSQL JDBC 42.7.11。T0 明确禁止忽略漏洞、降低阈值或绕过测试，因此必须从依赖图消除受影响版本。

## 决策

1. `com.alibaba:fastjson` 从无可修复版本的 1.2.83 切换到阿里 fastjson2 官方提供的 1.x API 兼容制品 2.0.61，并以回归测试覆盖当前 JustAuth 适配代码实际使用的解析、序列化、读取和合并 API。
2. 使用固定版本覆盖上游依赖管理：Netty 4.1.136.Final、HttpCore 5.4.3、Bouncy Castle 1.85、PostgreSQL JDBC 42.7.12、Fory 1.1.0。
3. Netty BOM 在 Spring Boot BOM 之前导入，其他坐标在本项目 `dependencyManagement` 显式管理，确保 Maven 解析结果不受上游传递版本漂移影响。
4. 删除 Redis 模块未实际启用的 Fory 直接依赖；SnailJob 所需的传递依赖统一提升至已修复版本。
5. 继续使用 HIGH/CRITICAL 零容忍门禁，不增加 ignore、VEX 豁免或降级扫描范围。

## 依赖治理记录

| 组件 | 固定版本 | 用途 | 许可证 | 替代/处置 | 安全结论 |
|---|---:|---|---|---|---|
| fastjson2 1.x 兼容制品 (`com.alibaba:fastjson`) | 2.0.61 | 兼容 JustAuth 适配代码的既有 JSON API | Apache-2.0 | 后续可迁移 Jackson 后移除 | 替换存在 RCE 且无修复版本的 fastjson 1.x |
| Netty BOM | 4.1.136.Final | Redisson、Reactor Netty、SnailJob 网络栈 | Apache-2.0 | 跟随 Spring Boot 安全版本后可撤销覆盖 | 修复本次 SBOM 报告的 Netty HIGH 漏洞 |
| Apache HttpCore | 5.4.3 | HttpClient 5 底层 HTTP/1.1、HTTP/2 核心 | Apache-2.0 | 等 Spring Boot BOM 纳入修复版本 | 修复 CVE-2026-54399、CVE-2026-54428 |
| Bouncy Castle | 1.85 | 加密、证书与 PEM 支持 | Bouncy Castle License (MIT-like) | 无等价低风险替换需求 | 高于本次漏洞要求的 1.84 修复线 |
| PostgreSQL JDBC | 42.7.12 | SnailJob PostgreSQL 数据源 | BSD-2-Clause | 不启用 PostgreSQL 时仍保留上游多数据源能力 | 修复 CVE-2026-54291 |
| Apache Fory | 1.1.0 | SnailJob 内部序列化传递依赖 | Apache-2.0 | Redis 模块未启用的直接依赖已删除 | 达到 CVE-2026-50076 修复版本 |

## 后果与风险

- 依赖覆盖会暂时偏离 Spring Boot 3.5.15 和 SnailJob 1.10.0 的原始传递版本，因此每次升级上游 BOM 时必须复查是否仍需覆盖。
- fastjson2 兼容层并不承诺覆盖所有 fastjson 1.x 边缘 API；当前使用面由专门回归测试约束，后续新增调用必须优先使用项目 Jackson 能力。
- Fory 从 0.13.x 跨越到 1.1.0，必须保持完整 Maven 构建、SnailJob 编译和 SBOM 扫描为发布门禁；T0 不启动正式调度业务或生产数据迁移。
- 回滚只能回滚整项依赖决策并重新通过 HIGH/CRITICAL 门禁，不得回退到已知漏洞版本后发布。

## 验证方式

- `./mvnw -DskipTests=false clean verify`：37 个 Maven 模块全部构建成功，兼容性回归测试通过。
- CycloneDX 聚合 SBOM：只允许出现上述修复版本，不得同时残留旧版本。
- Trivy SBOM、仓库漏洞/密钥、许可证和 IaC 门禁全部通过。
- GitHub Pull Request 的 Dependency Review 不得发现新增高风险依赖。
