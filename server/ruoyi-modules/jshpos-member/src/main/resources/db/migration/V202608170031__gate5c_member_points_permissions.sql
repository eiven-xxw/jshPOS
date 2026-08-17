DELIMITER $$
CREATE PROCEDURE jsh_assert_gate5c_points_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201111 AND 9201116) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 5C points sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate5c_points_menu_ids();
DROP PROCEDURE jsh_assert_gate5c_points_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201111,'积分账户查询',9201100,11,'#','',NULL,'',1,0,'F','0','0','member:points:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查询无PII积分账户'),
(9201112,'在线积分冻结',9201100,12,'#','',NULL,'',1,0,'F','0','0','member:points:freeze','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'仅在线冻结FEFO积分'),
(9201113,'在线积分结算',9201100,13,'#','',NULL,'',1,0,'F','0','0','member:points:settle','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按原冻结分配消费或解冻'),
(9201114,'人工积分调整',9201100,14,'#','',NULL,'',1,0,'F','0','0','member:points:adjust','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'租户管理员人工积分调整'),
(9201115,'会员等级管理',9201100,15,'#','',NULL,'',1,0,'F','0','0','member:level:manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'追加会员等级历史'),
(9201116,'积分投影重建',9201100,16,'#','',NULL,'',1,0,'F','0','0','member:points:rebuild','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'从只追加流水安全重建积分账户投影');
