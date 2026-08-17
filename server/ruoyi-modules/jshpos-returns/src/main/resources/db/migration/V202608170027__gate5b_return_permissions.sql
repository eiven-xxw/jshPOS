DELIMITER $$
CREATE PROCEDURE jsh_assert_gate5b_menu_ids()
BEGIN
    IF EXISTS (SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201000 AND 9201003) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 5B sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate5b_menu_ids();
DROP PROCEDURE jsh_assert_gate5b_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201000,'退货退款',0,33,'returns','returns/index',NULL,'Returns',1,0,'C','0','0','return:request:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 5B原单退货退款查询'),
(9201001,'退货申请',9201000,1,'#','',NULL,'',1,0,'F','0','0','return:request:create','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'申请原单退货退款'),
(9201002,'退货审批',9201000,2,'#','',NULL,'',1,0,'F','0','0','return:request:approve','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'独立审批原单退货退款'),
(9201003,'退货查询',9201000,3,'#','',NULL,'',1,0,'F','0','0','return:request:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'查询门店数据范围内退货退款Saga');
