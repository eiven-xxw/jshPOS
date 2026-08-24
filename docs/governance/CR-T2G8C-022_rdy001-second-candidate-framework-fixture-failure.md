# CR-T2G8C-022：RDY001 第二轮候选框架基表夹具缺失

## 结论

修复提交 `c7d46129c9840a8ebdf50726b60aeb2d8b420ca1` 的 GitHub Actions Run
`32714332457` 保留为第二轮失败证据。治理双平台和 Web 已通过；全 Owner 迁移测试在
首次执行正式集成回调 `beforeEachMigrate__repair_gate4c_gate7c_menu_ids.sql` 时，因空库
夹具只有 `sys_menu`、缺少回调所需的 RuoYi 框架基表 `sys_role_menu` 而失败关闭。

## 根因与处置

这是发布验收夹具与正式 RuoYi 初始化前置条件不一致，不是已发布迁移 SQL 失败。
仅为 MySQL 空库验收补充最小 `sys_role_menu` 框架夹具，并把 `sys_menu/sys_role_menu`
明确排除在 `jsh_*` 多租户业务表元数据检查之外；21 个 Owner 版本目录、1 个正式回调
目录、86 项迁移和 287 张业务表的门槛保持不变。

## 边界

不修改回调、已发布迁移、生产代码、依赖、业务事实或质量阈值；不重跑失败 Job，下一
提交必须从头复跑完整工作流。T2-RDY-001 继续为 `VERIFIED` 候选，外部阻断和零执行
状态不变。
