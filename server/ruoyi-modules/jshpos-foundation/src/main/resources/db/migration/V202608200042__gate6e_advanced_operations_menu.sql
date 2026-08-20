-- Gate 6E 仅新增后台路由和展示权限；各 Owner 按钮仍由其既有服务端权限最终授权。
DELIMITER $$
CREATE PROCEDURE jsh_assert_gate6e_operations_menu_id()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 9201500) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 6E operations sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate6e_operations_menu_id();
DROP PROCEDURE jsh_assert_gate6e_operations_menu_id;

INSERT INTO sys_menu (
    menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark
) VALUES (
    9201500,'高级运营中心',0,15,'advanced-operations','operations/advanced/index',NULL,'AdvancedOperations',1,0,
    'C','0','0','operations:advanced:read','dashboard',103,1,UTC_TIMESTAMP(),NULL,NULL,
    'Gate 6E Owner API 编排界面；路由权限只控制展示'
);
