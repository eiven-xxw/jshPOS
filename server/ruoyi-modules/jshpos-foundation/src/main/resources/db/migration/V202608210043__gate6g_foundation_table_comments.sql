-- Gate 6G 仅补充表级中文元数据；不修改已发布迁移和业务事实。
ALTER TABLE jsh_org_unit COMMENT = '组织树主数据；租户隔离；Foundation Owner受控写入';
ALTER TABLE jsh_store COMMENT = '门店与业务时区主数据；租户隔离；Foundation Owner受控写入';
ALTER TABLE jsh_staff_scope COMMENT = '员工组织门店数据范围；租户隔离；Foundation Owner受控写入';
ALTER TABLE jsh_config_template COMMENT = '行业配置模板主数据；租户隔离；Foundation Owner受控写入';
ALTER TABLE jsh_config_template_version COMMENT = '不可变配置模板版本；租户隔离；Foundation Owner只追加发布';
ALTER TABLE jsh_config_binding COMMENT = '租户或门店配置版本绑定；租户隔离；Foundation Owner受控切换';
ALTER TABLE jsh_audit_event COMMENT = '关键操作审计事实；租户隔离；Foundation Owner只追加';
