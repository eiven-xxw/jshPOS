DELIMITER $$
CREATE PROCEDURE jsh_assert_gate5c_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201100 AND 9201110) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 5C sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate5c_menu_ids();
DROP PROCEDURE jsh_assert_gate5c_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201100,'会员隐私',0,34,'members','members/index',NULL,'Members',1,0,'C','0','0','member:profile:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 5C脱敏会员与隐私权利入口'),
(9201101,'创建会员',9201100,1,'#','',NULL,'',1,0,'F','0','0','member:profile:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建会员最小主体'),
(9201102,'查询会员',9201100,2,'#','',NULL,'',1,0,'F','0','0','member:profile:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'精确身份解析并只返回脱敏结果'),
(9201103,'绑定身份',9201100,3,'#','',NULL,'',1,0,'F','0','0','member:identity:bind','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'绑定版本化加密身份'),
(9201104,'撤销身份',9201100,4,'#','',NULL,'',1,0,'F','0','0','member:identity:revoke','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'撤销身份但保留审计事实'),
(9201105,'记录同意',9201100,5,'#','',NULL,'',1,0,'F','0','0','member:consent:record','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'追加同意或撤回流水'),
(9201106,'提交隐私请求',9201100,6,'#','',NULL,'',1,0,'F','0','0','member:privacy:request','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'提交访问导出更正或删除请求'),
(9201107,'处理隐私请求',9201100,7,'#','',NULL,'',1,0,'F','0','0','member:privacy:process','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'迁移隐私请求状态并审计'),
(9201108,'会员合并',9201100,8,'#','',NULL,'',1,0,'F','0','0','member:identity:merge','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'可审计会员合并'),
(9201109,'会员拆分',9201100,9,'#','',NULL,'',1,0,'F','0','0','member:identity:split','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'按原关联事实可逆拆分'),
(9201110,'隐私导出审批',9201100,10,'#','',NULL,'',1,0,'F','0','0','member:privacy:export','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'隐私导出独立授权点');
