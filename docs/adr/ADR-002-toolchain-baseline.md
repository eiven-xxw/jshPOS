# ADR-002：工具链与语言基线

- 状态：Accepted
- 日期：2026-08-15

## 决策

后端固定 RuoYi 5.6.2、Spring Boot 3.5.15、Java 编译级别17、JDK21 LTS、Maven3.9.9；Web 固定 Node24 LTS、pnpm10.33.0及配套Vue版本；POS固定Flutter3.47.0/Dart3.13.0。版本明细以根目录 `VERSION_BASELINE.md` 为准。

## 后果与验证

JDK21用于受支持运行与构建，但不在T0改变上游Java语言级。升级语言级21或RuoYi6.x须新ADR、依赖/性能/租户隔离/全量回归。
