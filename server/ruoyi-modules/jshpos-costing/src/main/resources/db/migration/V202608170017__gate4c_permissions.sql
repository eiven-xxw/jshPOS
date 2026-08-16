DELIMITER $$
CREATE PROCEDURE jsh_assert_gate4c_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200540 AND 9200543 AND NOT (
          (menu_id=9200540 AND perms='inventory:cost-balance:read') OR
          (menu_id=9200541 AND perms='inventory:cost-ledger:read') OR
          (menu_id=9200542 AND perms='inventory:cost-policy:publish') OR
          (menu_id=9200543 AND perms='inventory:cost-rebuild')
        )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 4C sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate4c_menu_ids();
DROP PROCEDURE jsh_assert_gate4c_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200540,'成本余额查询',9200500,40,'#','',NULL,'',1,0,'F','0','0','inventory:cost-balance:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查看仓级可重建移动加权成本余额'),
(9200541,'成本流水查询',9200500,41,'#','',NULL,'',1,0,'F','0','0','inventory:cost-ledger:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查看不可变成本流水和出库成本快照'),
(9200542,'发布成本策略',9200500,42,'#','',NULL,'',1,0,'F','0','0','inventory:cost-policy:publish','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'发布不可变仓级成本策略版本'),
(9200543,'重建成本投影',9200500,43,'#','',NULL,'',1,0,'F','0','0','inventory:cost-rebuild','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只从成本流水受控重建余额投影')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
