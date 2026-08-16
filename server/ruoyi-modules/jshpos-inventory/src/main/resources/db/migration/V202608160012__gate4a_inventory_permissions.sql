DELIMITER $$
CREATE PROCEDURE jsh_assert_gate4a_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200500 AND 9200505 AND NOT (
          (menu_id=9200500 AND perms='inventory:balance:read' AND component='inventory/index') OR
          (menu_id=9200501 AND perms='inventory:balance:read') OR
          (menu_id=9200502 AND perms='inventory:movement:apply') OR
          (menu_id=9200503 AND perms='inventory:policy:publish') OR
          (menu_id=9200504 AND perms='inventory:rebuild') OR
          (menu_id=9200505 AND perms='inventory:ledger:read')
        )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 4A sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate4a_menu_ids();
DROP PROCEDURE jsh_assert_gate4a_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200500,'库存账本',0,28,'inventory','inventory/index',NULL,'Inventory',1,0,'C','0','0','inventory:balance:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 4A 不可变库存账本入口'),
(9200501,'库存余额查询',9200500,1,'#','',NULL,'',1,0,'F','0','0','inventory:balance:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查看可重建库存余额'),
(9200502,'应用销售退货库存',9200500,2,'#','',NULL,'',1,0,'F','0','0','inventory:movement:apply','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只从权威订单退款快照应用'),
(9200503,'发布库存策略',9200500,3,'#','',NULL,'',1,0,'F','0','0','inventory:policy:publish','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'发布不可变负库存策略版本'),
(9200504,'重建库存投影',9200500,4,'#','',NULL,'',1,0,'F','0','0','inventory:rebuild','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'从流水受控重建余额'),
(9200505,'库存流水查询',9200500,5,'#','',NULL,'',1,0,'F','0','0','inventory:ledger:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查看不可变库存流水')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
