DELIMITER $$
CREATE PROCEDURE jsh_assert_gate5d_report_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201117 AND 9201125) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 5D reporting sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate5d_report_menu_ids();
DROP PROCEDURE jsh_assert_gate5d_report_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201117,'经营报表',0,70,'reporting','reporting/operation/index',NULL,'Reporting',1,0,'C','0','0','report:operation:read','chart',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 5D可重建经营报表'),
(9201118,'基础报表查询',9201117,1,'#','',NULL,'',1,0,'F','0','0','report:operation:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'销售收银库存成本基础报表'),
(9201119,'来源事件消费',9201117,2,'#','',NULL,'',1,0,'F','0','0','report:projection:ingest','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'仅受控内部执行器消费'),
(9201120,'报表投影重建',9201117,3,'#','',NULL,'',1,0,'F','0','0','report:projection:rebuild','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'租户管理员影子版本重建'),
(9201121,'报表导出申请',9201117,4,'#','',NULL,'',1,0,'F','0','0','report:export:request','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'申请白名单字段安全导出'),
(9201122,'报表导出审批',9201117,5,'#','',NULL,'',1,0,'F','0','0','report:export:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'独立审批高风险导出'),
(9201123,'报表导出生成',9201117,6,'#','',NULL,'',1,0,'F','0','0','report:export:generate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'受控任务生成安全CSV'),
(9201124,'报表制品下载',9201117,7,'#','',NULL,'',1,0,'F','0','0','report:export:download','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'短期单次令牌下载'),
(9201125,'报表差异处理',9201117,8,'#','',NULL,'',1,0,'F','0','0','report:repair:manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'处理差异但不覆盖业务事实');
