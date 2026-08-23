-- T2-SAA-001 平台运营菜单与服务端权限；菜单只控制展示，最终授权仍由服务端执行。
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201700,'SaaS商户运营',0,18,'saas-operations','saas/operations/index','', 'SaasOperations',1,0,'C','0','0','saas:application:read','tenant',103,1,NOW(),NULL,NULL,'Gate 8A 商户开户与套餐权益运营'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201700 OR perms='saas:application:read');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201701,'创建申请',9201700,1,'','','','',1,0,'F','0','0','saas:application:create','#',103,1,NOW(),NULL,NULL,'创建商户申请'),
(9201702,'预检申请',9201700,2,'','','','',1,0,'F','0','0','saas:application:preflight','#',103,1,NOW(),NULL,NULL,'商户申请预检'),
(9201703,'审批申请',9201700,3,'','','','',1,0,'F','0','0','saas:application:approve','#',103,1,NOW(),NULL,NULL,'独立审批商户申请'),
(9201704,'技术开户',9201700,4,'','','','',1,0,'F','0','0','saas:application:provision','#',103,1,NOW(),NULL,NULL,'通过Foundation创建技术租户'),
(9201705,'租户初始化',9201700,5,'','','','',1,0,'F','0','0','saas:application:initialize','#',103,1,NOW(),NULL,NULL,'推进初始化Saga'),
(9201706,'租户激活',9201700,6,'','','','',1,0,'F','0','0','saas:application:activate','#',103,1,NOW(),NULL,NULL,'激活商业租户'),
(9201707,'套餐维护',9201700,7,'','','','',1,0,'F','0','0','saas:plan:create','#',103,1,NOW(),NULL,NULL,'创建版本化套餐'),
(9201708,'权益版本创建',9201700,8,'','','','',1,0,'F','0','0','saas:entitlement:create','#',103,1,NOW(),NULL,NULL,'创建权益版本'),
(9201709,'权益版本发布',9201700,9,'','','','',1,0,'F','0','0','saas:entitlement:publish','#',103,1,NOW(),NULL,NULL,'审批发布权益版本'),
(9201710,'租户生命周期',9201700,10,'','','','',1,0,'F','0','0','saas:tenant:lifecycle','#',103,1,NOW(),NULL,NULL,'暂停停用恢复逻辑注销'),
(9201711,'权益决策查询',9201700,11,'','','','',1,0,'F','0','0','saas:entitlement:read','#',103,1,NOW(),NULL,NULL,'服务端权益决策查询'),
(9201712,'权益配额消费',9201700,12,'','','','',1,0,'F','0','0','saas:entitlement:consume','#',103,1,NOW(),NULL,NULL,'原子消费服务端权益配额');
