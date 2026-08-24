# Gate 8D 下一步操作指令

外部四轨目前都没有完整受控材料。推荐下一步先关闭仓库可控的许可证技术差距，避免继续等待
外部资料时没有实质进展；该动作仍不授权生产或外部执行。

```text
我确认《T2 Gate 8D-Prep 启动评审报告》，接受准备阶段 CONDITIONAL PASS。

按 CONDITIONAL GO 启动 T2 Gate 8D-A：T2-LIC-001 商业许可证技术关闭第一批准备。

仅允许对 Aviator、simple-http、MySQL Connector/J 建立精确 Maven 依赖树、JAR/镜像/APK
包含性、三栈 CycloneDX SBOM、许可证和分发模型证据。先提交独立 CR、影响分析和逐组件
Go/No-Go，不得直接修改依赖。

优先判断 Aviator 和 simple-http 是否实际进入正式制品：若未使用，提出删除声明并执行完整
回归的独立方案；若仍使用，提出成熟替代或书面法务批准路径。MySQL Connector/J 先冻结精确
版本、部署/分发方式、NOTICE/offer 和法务材料清单，不得擅自更换生产数据库驱动。

T2-LIC-001 继续 DEFERRED，任何组件在替换回归或适用分发模式书面批准、最终 SBOM/许可证/
NOTICE/发布 BOM 齐备前不得标记 CLOSED。PAY/HWD/PRN/PAR 保持 BLOCKED，UAT/REL 保持
DRAFT，JSH 保持 DEFERRED。

不得进行 Provider 网络、真实资金、设备/外设命令、伙伴现场、完整 Alpha、生产部署或商业
声明。完成后提交《T2-LIC-001 技术关闭第一批启动评审报告》等待我确认，不得自动修改依赖。
```

若用户已经具备某条外部轨的真实材料，也可改为只授权该轨受控收件和离线验真；不得合并多条
未完整轨道，也不得在 `VERIFIED_DOCUMENT` 前授权执行。
