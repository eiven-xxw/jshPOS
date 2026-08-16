DELIMITER $$
CREATE PROCEDURE jsh_assert_sprint3_menu_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM sys_menu WHERE menu_id BETWEEN 9200300 AND 9200303 AND NOT (
          (menu_id=9200300 AND perms='sync:monitor:read' AND component='sync/index') OR
          (menu_id=9200301 AND perms='pos:sync:operate') OR
          (menu_id=9200302 AND perms='sync:monitor:read') OR
          (menu_id=9200303 AND perms='sync:repair')
        )
    ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sprint S3 sys_menu reserved ID collision';
    END IF;
END$$
DELIMITER ;
CALL jsh_assert_sprint3_menu_ids();
DROP PROCEDURE jsh_assert_sprint3_menu_ids;

INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark) VALUES
(9200300,'同步监控',0,27,'sync','sync/index',NULL,'SyncMonitor',1,0,'C','0','0','sync:monitor:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Sprint S3 同步积压与死信入口'),
(9200301,'POS同步',9200300,1,'#','',NULL,'',1,0,'F','0','0','pos:sync:operate','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'注册终端同步协议'),
(9200302,'同步查询',9200300,2,'#','',NULL,'',1,0,'F','0','0','sync:monitor:read','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'Inbox Outbox 游标与积压查询'),
(9200303,'同步修复',9200300,3,'#','',NULL,'',1,0,'F','0','0','sync:repair','#',NULL,1,CURRENT_TIMESTAMP,NULL,NULL,'受审计死信人工修复')
ON DUPLICATE KEY UPDATE menu_id=VALUES(menu_id);
