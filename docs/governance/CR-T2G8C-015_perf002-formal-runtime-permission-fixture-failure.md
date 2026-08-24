# CR-T2G8C-015：PERF-002 正式运行栈合成权限夹具失败

## 事实

修复提交 `4439216105cab1a7300d748c3ccaac08350888b6` 的 GitHub Run `32702869788` 中，治理双平台、Server、Owner/MySQL、Web、Flutter 双平台/Android 和 POS 性能均已通过；正式 JAR 成功启动，Gate 8B 的 SAA→SUB→SVC 正式 HTTP 旅程 55 次观察全部通过。随后性能预热访问受保护路径 `/api/v1/subscriptions/current` 时，合成租户管理员因套餐缺少 `subscription:self:read` 被正确拒绝，性能脚本按错误率必须为零的规则失败。

## 根因与处置

Gate 8B 合成租户套餐原先只装配 Foundation、Service 与基础系统权限；订阅创建、续期和恢复由平台管理员完成，因此既有旅程没有覆盖租户自查权限。PERF-002 将该已接受只读能力纳入并发路径后暴露了夹具遗漏。处置仅在正式 API 合成初始化脚本中纳入既有订阅权限菜单 `9201720..9201728`，并在性能夹具自检中硬断言 `9201728` 存在。保留订阅受保护端点与服务端授权，不通过删除路径或绕过权限获得绿色结果。

## 证据与边界

- 失败 Run `32702869788` 和 Artifact `9511299627` 完整保留；Artifact GitHub SHA-256 为 `a2af7e38177bd290df673ddedc2cc9fccf6ece3f2f65ddf9f4dc0cb1caf9f982`；
- 生产权限定义、Controller、Owner 事实、API、数据库迁移、依赖与冻结性能阈值变化均为 0；
- 不新增业务能力，不扩大真实租户权限；变更只作用于每次从空环境创建的虚构合成租户套餐；
- 不重跑失败 Job；修复提交必须从治理开始执行新的完整工作流；`T2-RDY-001` 继续 `DRAFT`。
