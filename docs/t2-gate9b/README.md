# T2 Gate 9B / Sprint S27-A

本目录记录 `G9A-R1` 正式 API 与当前 OpenAPI 一致性修复。唯一复用需求为已接受的
`T2-API-001`；本批不新增业务能力、Requirement ID、迁移或外部执行。

- 基线：`f708271e977f995e83a24fe398a1bd658726fd09`
- 分支：`t2/gate9b-sprint27a-api-openapi-alignment`
- 初始差异：Controller 缺契约 64，契约缺 Controller 21
- 退出目标：300/300 精确一致，双向差异 0/0，operationId 全局唯一

## 文档目录

1. [基线、差异分类与修复结果](01_基线差异分类与修复结果.md)
2. [测试矩阵、兼容与回退](02_测试矩阵兼容与回退.md)
3. [G9A-R1 正式 API 契约修复独立周门禁报告](03_G9A-R1正式API契约修复独立周门禁报告.md)
4. [下一步操作指令](04_下一步操作指令.md)
5. [证据索引](05_证据索引.md)
