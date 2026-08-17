DELIMITER $$
CREATE PROCEDURE jsh_assert_gate6a_terminal_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9201200 AND 9201207 AND NOT (
          (menu_id=9201200 AND perms='terminal:registry:read' AND component='terminal/registry/index') OR
          (menu_id=9201201 AND perms='terminal:activation:issue') OR
          (menu_id=9201202 AND perms='terminal:activation:cancel') OR
          (menu_id=9201203 AND perms='terminal:status:manage') OR
          (menu_id=9201204 AND perms='terminal:credential:rotate') OR
          (menu_id=9201205 AND perms='terminal:audit:read') OR
          (menu_id=9201206 AND perms='pos:terminal:report') OR
          (menu_id=9201207 AND perms='terminal:registry:read')
        )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Gate 6A terminal sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_gate6a_terminal_menu_ids();
DROP PROCEDURE jsh_assert_gate6a_terminal_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9201200,'终端登记',0,36,'terminal-registry','terminal/registry/index',NULL,'TerminalRegistry',1,0,'C','0','0','terminal:registry:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Gate 6A 终端登记激活与安全状态'),
(9201201,'签发激活',9201200,1,'#','',NULL,'',1,0,'F','0','0','terminal:activation:issue','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'一次性激活秘密只显示一次'),
(9201202,'取消激活',9201200,2,'#','',NULL,'',1,0,'F','0','0','terminal:activation:cancel','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'取消未消费激活授权'),
(9201203,'终端状态',9201200,3,'#','',NULL,'',1,0,'F','0','0','terminal:status:manage','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'阻断解阻吊销和退役'),
(9201204,'轮换凭据',9201200,4,'#','',NULL,'',1,0,'F','0','0','terminal:credential:rotate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'终端设备凭据轮换'),
(9201205,'终端审计',9201200,5,'#','',NULL,'',1,0,'F','0','0','terminal:audit:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'终端生命周期与安全拒绝审计'),
(9201206,'能力上报',9201200,6,'#','',NULL,'',1,0,'F','0','0','pos:terminal:report','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'已认证终端只上报自身能力'),
(9201207,'终端查询',9201200,7,'#','',NULL,'',1,0,'F','0','0','terminal:registry:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'租户和门店范围内终端查询')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
