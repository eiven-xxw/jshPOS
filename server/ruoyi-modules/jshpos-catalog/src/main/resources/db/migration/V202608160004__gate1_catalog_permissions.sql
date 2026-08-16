-- 高位固定 ID 冲突必须在迁移前失败关闭；仅允许完全相同的既有种子。
DELIMITER $$
CREATE PROCEDURE jsh_assert_gate1_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu
        WHERE menu_id BETWEEN 9200100 AND 9200110
          AND NOT (
              (menu_id = 9200100 AND perms = 'catalog:product:query' AND component = 'catalog/index') OR
              (menu_id = 9200101 AND perms = 'catalog:product:query') OR
              (menu_id = 9200102 AND perms = 'catalog:product:manage') OR
              (menu_id = 9200103 AND perms = 'catalog:definition:manage') OR
              (menu_id = 9200104 AND perms = 'catalog:import:preflight') OR
              (menu_id = 9200105 AND perms = 'catalog:import:publish') OR
              (menu_id = 9200106 AND perms = 'catalog:price:query') OR
              (menu_id = 9200107 AND perms = 'catalog:price:manage') OR
              (menu_id = 9200108 AND perms = 'catalog:price:publish') OR
              (menu_id = 9200109 AND perms = 'catalog:package:query') OR
              (menu_id = 9200110 AND perms = 'catalog:package:publish')
          )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Gate 1 sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;

CALL jsh_assert_gate1_menu_ids();
DROP PROCEDURE jsh_assert_gate1_menu_ids;

INSERT INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query_param, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_dept, create_by, create_time, update_by, update_time, remark
) VALUES
    (9200100, '商品中心',       0,       25, 'catalog',     'catalog/index', NULL, 'Catalog', 1, 0, 'C', '0', '0', 'catalog:product:query', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, 'Gate 1 商品价格入口'),
    (9200101, '商品查询', 9200100, 1, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:product:query', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '查询商品'),
    (9200102, '商品管理', 9200100, 2, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:product:manage', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '维护商品'),
    (9200103, '资料管理', 9200100, 3, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:definition:manage', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '分类品牌单位'),
    (9200104, '导入预检', 9200100, 4, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:import:preflight', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '商品导入预检'),
    (9200105, '导入发布', 9200100, 5, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:import:publish', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '原子发布导入版本'),
    (9200106, '价格查询', 9200100, 6, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:price:query', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '解析价格'),
    (9200107, '价格管理', 9200100, 7, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:price:manage', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '维护价格版本'),
    (9200108, '价格发布', 9200100, 8, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:price:publish', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '发布价格版本'),
    (9200109, '数据包查询', 9200100, 9, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:package:query', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '查询正式数据包'),
    (9200110, '数据包发布', 9200100, 10, '#', '', NULL, '', 1, 0, 'F', '0', '0', 'catalog:package:publish', '#', NULL, 1, CURRENT_TIMESTAMP, NULL, NULL, '构建和签名数据包')
ON DUPLICATE KEY UPDATE menu_id = VALUES(menu_id);
