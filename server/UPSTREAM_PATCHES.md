# T0 上游差异

| 文件 | 差异 | 原因 | 需求/ADR |
|---|---|---|---|
| `pom.xml` | 默认执行测试 | 商业项目不能依赖隐式跳过测试 | T0-SRV-001 |
| `.mvn/wrapper/*` | 固定 Maven 3.9.9 | 可重复构建 | ADR-002 |
| `ruoyi-modules/pom.xml` | 加入 `jshpos-foundation` | 建立自有领域根边界，不污染框架模块 | ADR-001 |
| `ruoyi-admin/.../TagUnitTest.java` | 移除无业务意义的 `@SpringBootTest` | 上游标签语法示例不应要求真实MySQL；测试内容保持不变并继续执行 | T0-SRV-001 |

引入新上游版本时必须重新审查并人工重放这些差异。
