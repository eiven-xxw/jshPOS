-- T2-SVC-001 服务运营菜单；服务端可信上下文、门店范围和权限检查仍是最终授权。
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201780,'服务运营',0,20,'service-operations','service/operations/index','', 'ServiceOperations',1,0,'C','0','0','service:read','service',103,1,NOW(),NULL,NULL,'Gate 8A 服务目录、实施项目、工单与附件治理'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201780 OR perms='service:read');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201781,'维护服务目录',9201780,1,'','','','',1,0,'F','0','0','service:catalog:manage','#',103,1,NOW(),NULL,NULL,'创建并发布版本化服务目录'),
(9201782,'创建实施项目',9201780,2,'','','','',1,0,'F','0','0','service:project:create','#',103,1,NOW(),NULL,NULL,'创建标准实施项目'),
(9201783,'处置实施项目',9201780,3,'','','','',1,0,'F','0','0','service:project:operate','#',103,1,NOW(),NULL,NULL,'完成检查项与推进实施状态'),
(9201784,'创建服务工单',9201780,4,'','','','',1,0,'F','0','0','service:ticket:create','#',103,1,NOW(),NULL,NULL,'创建租户门店服务工单'),
(9201785,'处置服务工单',9201780,5,'','','','',1,0,'F','0','0','service:ticket:operate','#',103,1,NOW(),NULL,NULL,'认领、流转、复核、关闭与重开工单'),
(9201786,'上传工单附件',9201780,6,'','','','',1,0,'F','0','0','service:attachment:upload','#',103,1,NOW(),NULL,NULL,'上传受控对象存储附件正文'),
(9201787,'下载工单附件',9201780,7,'','','','',1,0,'F','0','0','service:attachment:download','#',103,1,NOW(),NULL,NULL,'生成最长五分钟短期下载链接'),
(9201788,'清理工单附件',9201780,8,'','','','',1,0,'F','0','0','service:attachment:cleanup','#',103,1,NOW(),NULL,NULL,'按保留规则清理附件正文并保留审计'),
(9201789,'查看实施项目',9201780,9,'','','','',1,0,'F','0','0','service:project:read','#',103,1,NOW(),NULL,NULL,'按租户门店数据范围查看实施项目'),
(9201790,'查看服务工单',9201780,10,'','','','',1,0,'F','0','0','service:ticket:read','#',103,1,NOW(),NULL,NULL,'按租户门店数据范围查看服务工单');
