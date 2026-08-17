INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201300,'备份恢复管理',0,93,'backupRecovery',NULL,NULL,'BackupRecovery',1,0,'M','0','0',NULL,'database',103,1,UTC_TIMESTAMP(),NULL,NULL,'Gate 6A备份恢复权限根节点'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201300);

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201301,'查询备份目录',9201300,1,'',NULL,NULL,NULL,1,0,'F','0','0','backup:catalog:read','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'读取清单与恢复状态，不返回密钥或明文'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201301);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201302,'创建加密备份',9201300,2,'',NULL,NULL,NULL,1,0,'F','0','0','backup:create','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'需独立备份身份与可信租户范围'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201302);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201303,'执行恢复演练',9201300,3,'',NULL,NULL,NULL,1,0,'F','0','0','backup:restore:execute','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'只允许空隔离目标，生产切换另行审批'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201303);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201304,'读取恢复证据',9201300,4,'',NULL,NULL,NULL,1,0,'F','0','0','backup:evidence:read','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'读取RPO RTO和校验摘要'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9201304);

DELIMITER $$
CREATE TRIGGER trg_bak_object_no_update BEFORE UPDATE ON bak_backup_object FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_backup_object is append-only'; END$$
CREATE TRIGGER trg_bak_object_no_delete BEFORE DELETE ON bak_backup_object FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_backup_object cannot be deleted'; END$$
CREATE TRIGGER trg_bak_check_no_update BEFORE UPDATE ON bak_restore_check FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_restore_check is append-only'; END$$
CREATE TRIGGER trg_bak_check_no_delete BEFORE DELETE ON bak_restore_check FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_restore_check cannot be deleted'; END$$
CREATE TRIGGER trg_bak_audit_no_update BEFORE UPDATE ON bak_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_audit is append-only'; END$$
CREATE TRIGGER trg_bak_audit_no_delete BEFORE DELETE ON bak_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_audit cannot be deleted'; END$$
CREATE TRIGGER trg_bak_set_guard BEFORE UPDATE ON bak_backup_set FOR EACH ROW
BEGIN
  IF OLD.backup_id<>NEW.backup_id OR OLD.tenant_scope_sha256<>NEW.tenant_scope_sha256
     OR OLD.tenant_ids_csv<>NEW.tenant_ids_csv OR OLD.point_in_time<>NEW.point_in_time
     OR OLD.schema_version<>NEW.schema_version OR OLD.key_version<>NEW.key_version
     OR OLD.immutable_until<>NEW.immutable_until OR OLD.request_sha256<>NEW.request_sha256 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_backup_set frozen identity cannot change';
  END IF;
  IF NOT ((OLD.state='CREATING' AND NEW.state IN ('AVAILABLE','FAILED'))
       OR (OLD.state='AVAILABLE' AND NEW.state='EXPIRED') OR OLD.state=NEW.state) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_backup_set illegal transition';
  END IF;
END$$
CREATE TRIGGER trg_bak_drill_guard BEFORE UPDATE ON bak_restore_drill FOR EACH ROW
BEGIN
  IF OLD.drill_id<>NEW.drill_id OR OLD.backup_id<>NEW.backup_id OR OLD.request_sha256<>NEW.request_sha256
     OR OLD.started_at<>NEW.started_at THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_restore_drill frozen identity cannot change';
  END IF;
  IF NOT ((OLD.state='RUNNING' AND NEW.state IN ('PASS','FAIL_CLOSED')) OR OLD.state=NEW.state) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='bak_restore_drill illegal transition';
  END IF;
END$$
DELIMITER ;
