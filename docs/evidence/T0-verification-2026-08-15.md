# T0 技术基线验证记录

记录日期：2026-08-15  
记录范围：鲸熵汇收银系统 T0 技术基线  
证据状态：本地验证完成，远程 CI 证据待补

## 1. 验证环境

| 项目 | 固定/实测值 |
|---|---|
| 操作系统 | Windows，PowerShell |
| Java | Microsoft/OpenJDK 21.0.10；服务端语言级 17 |
| Maven | Wrapper 3.9.9 |
| Node.js | 24.9.0 |
| pnpm | 10.33.0 |
| Flutter | 3.47.0 stable |
| Dart | 3.13.0 |
| Python | 3.12 |
| Android SDK | 本机未安装，转由远程 CI 验证 |
| Docker | 本机未安装，转由远程 CI 验证 |

完整版本与上游 commit 见 `VERSION_BASELINE.md`。

## 2. 统一门禁结果

执行入口：

```powershell
$env:JSH_POS_PYTHON = '<Python 3.12 executable>'
$env:JSH_POS_FLUTTER = '<Flutter 3.47.0 executable>'
.\scripts\verify-t0.ps1 -SkipAndroidBuild -SkipInfrastructure
```

机器可读原始结果生成于 `artifacts/t0/summary.json`；该目录为临时构建产物，不提交仓库。本记录保存可审计摘要。

| 门禁 | 结果 | 耗时 | 证据摘要 |
|---|---:|---:|---|
| structure | PASS | 0.04s | 目录、必需文件、无嵌套 Git、Actions SHA 固定检查通过 |
| rtm | PASS | 0.04s | 24 条需求，其中 12 条 T0 需求可解析且状态合法 |
| contracts | PASS | 0.05s | OpenAPI 与 JSON Schema 契约骨架校验通过 |
| server | PASS | 46.98s | Maven `clean verify`，37 个 reactor 模块全部成功 |
| admin-web | PASS | 34.45s | frozen install、high audit、ESLint、类型检查、单测入口、生产构建通过 |
| device-adapter | PASS | 9.47s | 锁文件、Dart analyze、3 个契约测试通过 |
| flutter-pos | PASS | 8.86s | 锁文件、analyze、1 个骨架组件测试通过 |
| android-apk | SKIP | — | 本机无 Android SDK；GitHub Actions 必须执行 debug APK 构建和 Kotlin 编译 |
| compose | SKIP | — | 本机无 Docker；GitHub Actions 必须执行 Compose 配置解析 |

统一脚本退出码为 `0`；`SKIP` 不是验收通过，只表示已明确转移到远程 CI 的验证责任。

## 3. 补充核验

| 范围 | 命令/方法 | 结果 |
|---|---|---|
| 服务端 SBOM | CycloneDX Maven Plugin 2.9.1 | 生成 JSON/XML，415 个组件 |
| Vue 已知漏洞 | `pnpm audit --registry https://registry.npmjs.org --audit-level high` | 无已知漏洞 |
| GitHub Actions 静态检查 | actionlint 1.7.12 | 通过，无错误输出 |
| 上游来源 | tag、commit、许可证、内部快照核对 | 通过 |
| 正式业务范围 | 目录、页面、模块边界人工复核 | 未铺开订单、支付、库存、促销等正式业务实现 |

## 4. 尚未形成的证据

以下项目必须在首次 GitHub 远程 CI 中补齐，否则 T0 不得标记 `ACCEPTED`：

1. `flutter build apk --debug` 成功，并由此完成 Kotlin 设备适配层实际编译；
2. `docker compose --env-file .env config --quiet` 成功；
3. governance、server、admin-web、flutter-pos、infrastructure 五个 CI job 全绿；
4. dependency review 无 high/critical 漏洞和禁止许可证；
5. 服务端 SBOM、前端许可证清单构建制品可下载；
6. 产品、架构、研发、QA/安全责任人完成验收签署。

## 5. 已知非阻断债务

- Vue 生产构建存在大 chunk 警告，不影响 T0 骨架构建；应在 T2 页面性能预算中治理。
- Flutter 提示 2 个存在更新但与当前约束不兼容的间接包；当前锁文件可重复构建，升级需独立评估。
- `crypto-js` 已停止维护且上游使用 AES-ECB；任何商业公网发布前必须完成替换 ADR、双端兼容和安全回归。
- SBOM 只提供组成清单，不等同于服务端漏洞扫描；T1 必须接入 SCA。

## 6. 结论

T0 的代码、文档、目录和本地可执行门禁已经完成；由于两项平台门禁及首次远程 CI 尚未取得证据，本记录支持“有条件通过/待封板”，不支持“正式验收通过”或“可商用上线”的结论。
