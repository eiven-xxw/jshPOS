DELIMITER $$
CREATE PROCEDURE jsh_assert_gate4d_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200800 AND 9200808) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 4D sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate4d_menu_ids();
DROP PROCEDURE jsh_assert_gate4d_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200800,'调拨管理',0,31,'transfer','transfer/index',NULL,'Transfer',1,0,'C','0','0','transfer:order:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 4D 基础仓间调拨'),
(9200801,'调拨查询',9200800,1,'#','',NULL,'',1,0,'F','0','0','transfer:order:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查询同租户且在数据范围内调拨'),
(9200802,'创建调拨',9200800,2,'#','',NULL,'',1,0,'F','0','0','transfer:order:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建调拨草稿'),
(9200803,'提交调拨',9200800,3,'#','',NULL,'',1,0,'F','0','0','transfer:order:submit','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'提交调拨审批'),
(9200804,'审批调拨',9200800,4,'#','',NULL,'',1,0,'F','0','0','transfer:order:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'职责分离审批调拨'),
(9200805,'调拨发出',9200800,5,'#','',NULL,'',1,0,'F','0','0','transfer:dispatch:post','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'来源仓发出并追加库存成本流水'),
(9200806,'调拨收货',9200800,6,'#','',NULL,'',1,0,'F','0','0','transfer:receipt:post','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'目的仓收货并继承发出成本'),
(9200807,'处理差异',9200800,7,'#','',NULL,'',1,0,'F','0','0','transfer:difference:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'审批在途短少差异'),
(9200808,'取消调拨',9200800,8,'#','',NULL,'',1,0,'F','0','0','transfer:order:cancel','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'仅发出前取消调拨');
