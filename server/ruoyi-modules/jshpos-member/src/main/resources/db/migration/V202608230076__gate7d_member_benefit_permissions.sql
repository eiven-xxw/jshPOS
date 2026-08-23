DELIMITER $$
CREATE PROCEDURE jsh_assert_mem003_menu_ids()
BEGIN
  IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201140 AND 9201148) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='T2-MEM-003 sys_menu reserved ID collision';
  END IF;
END$$
DELIMITER ;
CALL jsh_assert_mem003_menu_ids();
DROP PROCEDURE jsh_assert_mem003_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201140,'会员权益',0,36,'member-benefits','member-benefits/index',NULL,'MemberBenefits',1,0,'C','0','0','member:benefit:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'T2-MEM-003版本化会员权益'),
(9201141,'创建权益草稿',9201140,1,'#','',NULL,'',1,0,'F','0','0','member:benefit:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建默认关闭权益版本'),
(9201142,'验证权益版本',9201140,2,'#','',NULL,'',1,0,'F','0','0','member:benefit:validate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'完整预检'),
(9201143,'批准权益版本',9201140,3,'#','',NULL,'',1,0,'F','0','0','member:benefit:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'独立批准'),
(9201144,'发布权益版本',9201140,4,'#','',NULL,'',1,0,'F','0','0','member:benefit:publish','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'调度或生效权益版本'),
(9201145,'暂停权益版本',9201140,5,'#','',NULL,'',1,0,'F','0','0','member:benefit:pause','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'受审计暂停'),
(9201146,'撤回权益版本',9201140,6,'#','',NULL,'',1,0,'F','0','0','member:benefit:revoke','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'受审计撤回并提升纪元'),
(9201147,'查询权益版本',9201140,7,'#','',NULL,'',1,0,'F','0','0','member:benefit:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'无PII权益查询'),
(9201148,'POS会员权益报价',9201140,8,'#','',NULL,'',1,0,'F','0','0','pos:member-benefit:quote','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'发行和使用最小权益快照');
