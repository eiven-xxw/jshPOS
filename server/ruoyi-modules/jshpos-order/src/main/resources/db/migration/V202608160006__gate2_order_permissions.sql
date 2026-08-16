DELIMITER $$
CREATE PROCEDURE jsh_assert_gate2_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200200 AND 9200207 AND NOT (
          (menu_id=9200200 AND perms='order:read' AND component='order/index') OR
          (menu_id=9200201 AND perms='pos:shift:open') OR
          (menu_id=9200202 AND perms='pos:basket:operate') OR
          (menu_id=9200203 AND perms='pos:cash:collect') OR
          (menu_id=9200204 AND perms='pos:order:suspend') OR
          (menu_id=9200205 AND perms='pos:shift:close') OR
          (menu_id=9200206 AND perms='pos:shift:approve-difference') OR
          (menu_id=9200207 AND perms='order:read')
        )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 2 sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate2_menu_ids();
DROP PROCEDURE jsh_assert_gate2_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200200,'收银交易',0,26,'orders','order/index',NULL,'Orders',1,0,'C','0','0','order:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 2 订单与班次入口'),
(9200201,'开班',9200200,1,'#','',NULL,'',1,0,'F','0','0','pos:shift:open','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'开班'),
(9200202,'购物篮',9200200,2,'#','',NULL,'',1,0,'F','0','0','pos:basket:operate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'购物篮操作'),
(9200203,'现金收款',9200200,3,'#','',NULL,'',1,0,'F','0','0','pos:cash:collect','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'现金成交'),
(9200204,'挂取单',9200200,4,'#','',NULL,'',1,0,'F','0','0','pos:order:suspend','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'挂单与取单'),
(9200205,'交班',9200200,5,'#','',NULL,'',1,0,'F','0','0','pos:shift:close','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'关闭班次'),
(9200206,'长短款审批',9200200,6,'#','',NULL,'',1,0,'F','0','0','pos:shift:approve-difference','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'独立主管审批'),
(9200207,'订单查询',9200200,7,'#','',NULL,'',1,0,'F','0','0','order:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按数据范围查询')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
