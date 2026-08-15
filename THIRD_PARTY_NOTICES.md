# 第三方基线声明

T0 引入的主要上游：

| 项目 | 来源 | 版本 | 许可证 | 处理方式 |
|---|---|---|---|---|
| RuoYi-Vue-Plus | https://github.com/dromara/RuoYi-Vue-Plus | 5.6.2 | MIT | 保留 `server/LICENSE` 和上游版权 |
| plus-ui | https://github.com/JavaLionLi/plus-ui | 5.6.2-2.6.2 | MIT | 保留 `admin-web/LICENSE` 和上游版权 |
| Flutter | https://flutter.dev | 3.47.0 | BSD-3-Clause | SDK 工具链，不提交 SDK 二进制 |

完整传递依赖许可证由 CI 生成清单并作为构建制品保存，执行规则见 `docs/compliance/dependency-baseline.md`。任何 copyleft、禁止商用、来源不明或许可证缺失依赖在法务确认前不得进入商业发布。
