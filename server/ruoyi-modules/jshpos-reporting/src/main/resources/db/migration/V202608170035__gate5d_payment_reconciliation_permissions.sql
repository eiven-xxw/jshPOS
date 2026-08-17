DELIMITER $$
CREATE PROCEDURE jsh_assert_gate5d_reconciliation_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201126 AND 9201129) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 5D reconciliation sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate5d_reconciliation_menu_ids();
DROP PROCEDURE jsh_assert_gate5d_reconciliation_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201126,'支付退款事实消费',9201117,9,'#','',NULL,'',1,0,'F','0','0','report:payment:ingest','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'仅受控内部执行器消费Provider无关事实'),
(9201127,'内部合成账单导入',9201117,10,'#','',NULL,'',1,0,'F','0','0','report:bill:synthetic-import','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只允许SYNTHETIC_INTERNAL且不构成SANDBOX证据'),
(9201128,'支付退款对账查询',9201117,11,'#','',NULL,'',1,0,'F','0','0','report:payment-reconciliation:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Provider无关内部合成对账查询'),
(9201129,'支付退款差异处理',9201117,12,'#','',NULL,'',1,0,'F','0','0','report:payment-reconciliation:manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'只写Reporting处理状态与只追加审计');
