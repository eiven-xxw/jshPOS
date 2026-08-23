# 正式 API 内部合成 E2E 设计与运行手册

## 1. 旅程

商户申请与独立审批 → 套餐/权益版本发布 → 服务端分配技术租户并激活 → 订阅创建、激活、续期、受控降级和恢复 → 服务目录发布 → 实施项目与检查项 → 工单认领、处理、附件元数据、短期下载、清理、解决与独立关闭 → 商业租户逻辑停用和受控恢复。

## 2. 执行方式

`CommercialSaasOperationsFormalApiE2ETest` 实例化三个正式 Controller 并由 MockMvc 发出 JSON/multipart HTTP 请求。它只 Mock 已接受的应用服务边界，不访问 Mapper 或数据库，不新增测试 Controller，不调用外部网络。

Windows/本地执行：

```powershell
Set-Location server
.\mvnw.cmd -pl :jshpos-service -am "-Dtest=CommercialSaasOperationsFormalApiE2ETest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Linux/CI 执行：

```bash
cd server
./mvnw -pl :jshpos-service -am -Dtest=CommercialSaasOperationsFormalApiE2ETest -Dsurefire.failIfNoSpecifiedTests=false test
```

## 3. 通过标准

- 旅程所有请求返回正式成功响应且逐步调用对应 Owner 应用服务。
- 身份、状态和摘要使用固定虚构向量；无 Secret、真实 PII 或真实商户数据。
- 不出现直接数据库、跨 Owner Mapper、Provider/设备/对象存储真实调用。
- 单项 Owner 测试、V1—V86 MySQL、Web、Flutter/Android 与安全回归保持绿色。

本旅程失败时 Gate 8B-Prep 为 NO-GO；通过时也只形成内部 API 装配证据。
