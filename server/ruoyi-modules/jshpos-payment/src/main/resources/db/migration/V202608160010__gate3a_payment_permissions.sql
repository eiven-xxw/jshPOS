DELIMITER $$
CREATE PROCEDURE jsh_assert_gate3a_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200300 AND 9200309 AND NOT (
          (menu_id=9200300 AND perms='payment:read' AND component='payment/index') OR
          (menu_id=9200301 AND perms='payment:intent:create') OR
          (menu_id=9200302 AND perms='payment:attempt:create') OR
          (menu_id=9200303 AND perms='payment:read') OR
          (menu_id=9200304 AND perms='refund:create') OR
          (menu_id=9200305 AND perms='refund:approve') OR
          (menu_id=9200306 AND perms='refund:read') OR
          (menu_id=9200307 AND perms='reconciliation:run') OR
          (menu_id=9200308 AND perms='reconciliation:manage') OR
          (menu_id=9200309 AND perms='reconciliation:read')
        )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 3A sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate3a_menu_ids();
DROP PROCEDURE jsh_assert_gate3a_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200300,'支付与退款',0,27,'payments','payment/index',NULL,'Payments',1,0,'C','0','0','payment:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 3A Provider 无关支付核心入口'),
(9200301,'创建支付意图',9200300,1,'#','',NULL,'',1,0,'F','0','0','payment:intent:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'从权威原单创建支付意图'),
(9200302,'创建支付尝试',9200300,2,'#','',NULL,'',1,0,'F','0','0','payment:attempt:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只创建稳定请求事实，不执行网络调用'),
(9200303,'支付查询',9200300,3,'#','',NULL,'',1,0,'F','0','0','payment:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按门店数据范围查询支付'),
(9200304,'创建原单退款',9200300,4,'#','',NULL,'',1,0,'F','0','0','refund:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建金额数量受限的原单退款'),
(9200305,'退款审批',9200300,5,'#','',NULL,'',1,0,'F','0','0','refund:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'独立审批退款'),
(9200306,'退款查询',9200300,6,'#','',NULL,'',1,0,'F','0','0','refund:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查看退款和占额'),
(9200307,'执行对账',9200300,7,'#','',NULL,'',1,0,'F','0','0','reconciliation:run','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'受控账单双源匹配'),
(9200308,'处理对账差异',9200300,8,'#','',NULL,'',1,0,'F','0','0','reconciliation:manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'调查解决审批关闭差异'),
(9200309,'查看对账差异',9200300,9,'#','',NULL,'',1,0,'F','0','0','reconciliation:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只读差异与证据')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
