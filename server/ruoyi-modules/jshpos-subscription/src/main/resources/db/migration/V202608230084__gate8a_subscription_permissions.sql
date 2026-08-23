-- T2-SUB-001 订阅运营菜单；服务端权限和平台管理员检查仍是最终授权。
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201720,'订阅运营',0,19,'subscription-operations','subscription/operations/index','', 'SubscriptionOperations',1,0,'C','0','0','subscription:read','date-range',103,1,NOW(),NULL,NULL,'Gate 8A 订阅期限与受控降级运营'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201720 OR perms='subscription:read');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201721,'创建订阅',9201720,1,'','','','',1,0,'F','0','0','subscription:create','#',103,1,NOW(),NULL,NULL,'为已开户租户创建订阅'),
(9201722,'激活订阅',9201720,2,'','','','',1,0,'F','0','0','subscription:activate','#',103,1,NOW(),NULL,NULL,'激活订阅并切换正常访问'),
(9201723,'续期订阅',9201720,3,'','','','',1,0,'F','0','0','subscription:renew','#',103,1,NOW(),NULL,NULL,'追加期限版本并续期'),
(9201724,'暂停订阅',9201720,4,'','','','',1,0,'F','0','0','subscription:suspend','#',103,1,NOW(),NULL,NULL,'暂停并进入受控恢复访问'),
(9201725,'恢复订阅',9201720,5,'','','','',1,0,'F','0','0','subscription:restore','#',103,1,NOW(),NULL,NULL,'追加新期限并受控恢复'),
(9201726,'终止订阅',9201720,6,'','','','',1,0,'F','0','0','subscription:terminate','#',103,1,NOW(),NULL,NULL,'逻辑终止订阅'),
(9201727,'运行到期扫描',9201720,7,'','','','',1,0,'F','0','0','subscription:job:run','#',103,1,NOW(),NULL,NULL,'显式运行持久化租约到期扫描'),
(9201728,'租户订阅自查',9201720,8,'','','','',1,0,'F','0','0','subscription:self:read','#',103,1,NOW(),NULL,NULL,'租户读取自身订阅与受限原因');
