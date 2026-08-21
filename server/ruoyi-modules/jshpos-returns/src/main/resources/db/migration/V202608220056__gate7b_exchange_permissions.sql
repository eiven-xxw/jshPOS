-- EXG-001 权限；页面显示不替代服务端可信租户、门店范围和职责分离校验。
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9201004,'换货查询',9201000,4,'#','',NULL,'',1,0,'F','0','0','pos:exchange:read','#','查询门店数据范围内换货Saga',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201004 OR perms='pos:exchange:read');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9201005,'换货创建',9201000,5,'#','',NULL,'',1,0,'F','0','0','pos:exchange:create','#','只创建原退货与新销售关联',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201005 OR perms='pos:exchange:create');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9201006,'换货审批',9201000,6,'#','',NULL,'',1,0,'F','0','0','pos:exchange:approve','#','独立审批换货关联，不代替退货审批',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201006 OR perms='pos:exchange:approve');
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,remark,create_dept,create_by,create_time,update_by,update_time)
SELECT 9201007,'换货恢复',9201000,7,'#','',NULL,'',1,0,'F','0','0','pos:exchange:recover','#','只观察和推进原命令，不创建替代命令',NULL,1,CURRENT_TIMESTAMP,NULL,NULL
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201007 OR perms='pos:exchange:recover');
