DELIMITER $$
CREATE PROCEDURE jsh_assert_gate4b_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200510 AND 9200533) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 4B sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate4b_menu_ids();
DROP PROCEDURE jsh_assert_gate4b_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200510,'盘点查询',9200500,10,'#','',NULL,'',1,0,'F','0','0','inventory:stocktake:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 4B 动态盘点查询'),
(9200511,'创建盘点',9200500,11,'#','',NULL,'',1,0,'F','0','0','inventory:stocktake:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建所选SKU动态盘点'),
(9200512,'盘点计数',9200500,12,'#','',NULL,'',1,0,'F','0','0','inventory:stocktake:count','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'追加不可变计数修订'),
(9200513,'提交盘点',9200500,13,'#','',NULL,'',1,0,'F','0','0','inventory:stocktake:submit','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'冻结截止账面并提交复核'),
(9200514,'复核盘点',9200500,14,'#','',NULL,'',1,0,'F','0','0','inventory:stocktake:review','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'职责分离复核'),
(9200515,'审批盘点',9200500,15,'#','',NULL,'',1,0,'F','0','0','inventory:stocktake:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'审批并追加差异流水'),
(9200520,'采购管理',0,29,'procurement','procurement/index',NULL,'Procurement',1,0,'C','0','0','procurement:order:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 4B 采购入口'),
(9200521,'供应商创建',9200520,1,'#','',NULL,'',1,0,'F','0','0','procurement:supplier:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建供应商'),
(9200522,'供应商状态',9200520,2,'#','',NULL,'',1,0,'F','0','0','procurement:supplier:state','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'停用或启用供应商'),
(9200523,'采购单创建',9200520,3,'#','',NULL,'',1,0,'F','0','0','procurement:order:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建采购单并冻结单位快照'),
(9200524,'采购单提交',9200520,4,'#','',NULL,'',1,0,'F','0','0','procurement:order:submit','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'提交采购单审批'),
(9200525,'采购单审批',9200520,5,'#','',NULL,'',1,0,'F','0','0','procurement:order:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'职责分离审批采购单'),
(9200526,'采购单关闭',9200520,6,'#','',NULL,'',1,0,'F','0','0','procurement:order:close','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'关闭采购单'),
(9200527,'采购单查询',9200520,7,'#','',NULL,'',1,0,'F','0','0','procurement:order:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'采购单查询'),
(9200528,'创建收货草稿',9200520,8,'#','',NULL,'',1,0,'F','0','0','procurement:receipt:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建无库存效果收货草稿'),
(9200529,'确认收货',9200520,9,'#','',NULL,'',1,0,'F','0','0','procurement:receipt:confirm','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'确认收货并追加库存流水'),
(9200530,'收货查询',9200520,10,'#','',NULL,'',1,0,'F','0','0','procurement:receipt:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'收货单查询'),
(9200531,'创建采购退货',9200520,11,'#','',NULL,'',1,0,'F','0','0','procurement:return:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建原收货退货草稿'),
(9200532,'提交采购退货',9200520,12,'#','',NULL,'',1,0,'F','0','0','procurement:return:submit','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'提交采购退货审批'),
(9200533,'审批采购退货',9200520,13,'#','',NULL,'',1,0,'F','0','0','procurement:return:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'职责分离审批并追加库存流水');
