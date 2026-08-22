INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,remark)
VALUES
(9200540,'开业资料迁移',0,95,'business-migration','operations/business-migration/index','',NULL,1,0,'C','0','0','migration:read','upload',103,1,NOW(),'T2-DMT-001 开业资料迁移工作台'),
(9200541,'迁移上传预检',9200540,1,'','','',NULL,1,0,'F','0','0','migration:upload','#',103,1,NOW(),'上传与预检'),
(9200542,'迁移双人审批',9200540,2,'','','',NULL,1,0,'F','0','0','migration:approve','#',103,1,NOW(),'双人审批'),
(9200543,'迁移执行恢复',9200540,3,'','','',NULL,1,0,'F','0','0','migration:execute','#',103,1,NOW(),'可恢复Saga执行'),
(9200544,'迁移对账激活',9200540,4,'','','',NULL,1,0,'F','0','0','migration:activate','#',103,1,NOW(),'对账、激活与清理');
