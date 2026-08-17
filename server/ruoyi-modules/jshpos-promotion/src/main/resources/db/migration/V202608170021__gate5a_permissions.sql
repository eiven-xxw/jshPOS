DELIMITER $$
CREATE PROCEDURE jsh_assert_gate5a_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200900 AND 9200914) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 5A sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate5a_menu_ids();
DROP PROCEDURE jsh_assert_gate5a_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200900,'促销管理',0,32,'promotion','promotion/index',NULL,'Promotion',1,0,'C','0','0','promotion:rule:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 5A确定性促销规则'),
(9200901,'促销查询',9200900,1,'#','',NULL,'',1,0,'F','0','0','promotion:rule:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查询促销规则与解释'),
(9200902,'创建促销',9200900,2,'#','',NULL,'',1,0,'F','0','0','promotion:rule:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'创建促销规则草稿'),
(9200903,'校验促销',9200900,3,'#','',NULL,'',1,0,'F','0','0','promotion:rule:validate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'静态校验规则版本'),
(9200904,'审批促销',9200900,4,'#','',NULL,'',1,0,'F','0','0','promotion:rule:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'职责分离审批规则版本'),
(9200905,'发布促销',9200900,5,'#','',NULL,'',1,0,'F','0','0','promotion:rule:publish','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'发布规则和门店离线包'),
(9200906,'暂停促销',9200900,6,'#','',NULL,'',1,0,'F','0','0','promotion:rule:pause','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'暂停已发布促销'),
(9200907,'促销询价',9200900,7,'#','',NULL,'',1,0,'F','0','0','promotion:quote:calculate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'执行确定性促销询价'),
(9200908,'规则包读取',9200900,8,'#','',NULL,'',1,0,'F','0','0','promotion:package:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'读取门店绑定离线规则包');
