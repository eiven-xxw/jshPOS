DELIMITER $$
CREATE PROCEDURE jsh_assert_gate3a_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200400 AND 9200409 AND NOT (
          (menu_id=9200400 AND perms='payment:read' AND component='payment/index') OR
          (menu_id=9200401 AND perms='payment:intent:create') OR
          (menu_id=9200402 AND perms='payment:attempt:create') OR
          (menu_id=9200403 AND perms='payment:read') OR
          (menu_id=9200404 AND perms='refund:create') OR
          (menu_id=9200405 AND perms='refund:approve') OR
          (menu_id=9200406 AND perms='refund:read') OR
          (menu_id=9200407 AND perms='reconciliation:run') OR
          (menu_id=9200408 AND perms='reconciliation:manage') OR
          (menu_id=9200409 AND perms='reconciliation:read')
        )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 3A sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate3a_menu_ids();
DROP PROCEDURE jsh_assert_gate3a_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200400,'支付与退款',0,27,'payments','payment/index',NULL,'Payments',1,0,'C','0','0','payment:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 3A Provider 无关支付核心入口'),
(9200401,'创建支付意图',9200400,1,'#','',NULL,'',1,0,'F','0','0','payment:intent:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'从权威原单创建支付意图'),
(9200402,'创建支付尝试',9200400,2,'#','',NULL,'',1,0,'F','0','0','payment:attempt:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只创建稳定请求事实，不执行网络调用'),
(9200403,'支付查询',9200400,3,'#','',NULL,'',1,0,'F','0','0','payment:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按门店数据范围查询支付'),
(9200404,'创建原单退款',9200400,4,'#','',NULL,'',1,0,'F','0','0','refund:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建金额数量受限的原单退款'),
(9200405,'退款审批',9200400,5,'#','',NULL,'',1,0,'F','0','0','refund:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'独立审批退款'),
(9200406,'退款查询',9200400,6,'#','',NULL,'',1,0,'F','0','0','refund:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查看退款和占额'),
(9200407,'执行对账',9200400,7,'#','',NULL,'',1,0,'F','0','0','reconciliation:run','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'受控账单双源匹配'),
(9200408,'处理对账差异',9200400,8,'#','',NULL,'',1,0,'F','0','0','reconciliation:manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'调查解决审批关闭差异'),
(9200409,'查看对账差异',9200400,9,'#','',NULL,'',1,0,'F','0','0','reconciliation:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只读差异与证据')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
