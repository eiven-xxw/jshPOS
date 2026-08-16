-- Gate 0 权限种子只扩展 RuoYi 菜单/权限目录，不修改框架表结构和既有记录。
-- 使用高位固定 ID；若目标环境已占用这些 ID，实施前置检查必须阻断迁移。
DELIMITER $$
CREATE PROCEDURE jsh_assert_gate0_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu
        WHERE menu_id BETWEEN 9200000 AND 9200011
          AND NOT (
              (menu_id = 9200000 AND perms = 'foundation:org:query' AND component = 'foundation/index') OR
              (menu_id = 9200001 AND perms = 'foundation:org:query') OR
              (menu_id = 9200002 AND perms = 'foundation:org:manage') OR
              (menu_id = 9200003 AND perms = 'foundation:store:query') OR
              (menu_id = 9200004 AND perms = 'foundation:store:manage') OR
              (menu_id = 9200005 AND perms = 'foundation:scope:query') OR
              (menu_id = 9200006 AND perms = 'foundation:scope:grant') OR
              (menu_id = 9200007 AND perms = 'foundation:config:query') OR
              (menu_id = 9200008 AND perms = 'foundation:config:manage') OR
              (menu_id = 9200009 AND perms = 'foundation:config:publish') OR
              (menu_id = 9200010 AND perms = 'foundation:config:activate') OR
              (menu_id = 9200011 AND perms = 'foundation:audit:query')
          )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Gate 0 sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;

CALL jsh_assert_gate0_menu_ids();
DROP PROCEDURE jsh_assert_gate0_menu_ids;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
SELECT 9200000, '平台基础', 0, 1, 'foundation', 'foundation/index', '', 1, 0,
       'C', '0', '0', 'foundation:org:query', 'company', 103, 1, UTC_TIMESTAMP(), '鲸熵汇 Gate 0 平台基础工作台'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 9200000);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES
    (9200001, '组织查询', 9200000, 1, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:org:query', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200002, '组织管理', 9200000, 2, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:org:manage', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200003, '门店查询', 9200000, 3, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:store:query', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200004, '门店管理', 9200000, 4, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:store:manage', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200005, '范围查询', 9200000, 5, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:scope:query', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200006, '范围授权', 9200000, 6, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:scope:grant', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200007, '配置查询', 9200000, 7, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:config:query', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200008, '配置管理', 9200000, 8, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:config:manage', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200009, '配置发布', 9200000, 9, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:config:publish', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200010, '配置激活回退', 9200000, 10, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:config:activate', '#', 103, 1, UTC_TIMESTAMP(), ''),
    (9200011, '审计查询', 9200000, 11, '#', '', '', 1, 0, 'F', '0', '0', 'foundation:audit:query', '#', 103, 1, UTC_TIMESTAMP(), '')
ON DUPLICATE KEY UPDATE menu_id = VALUES(menu_id);
