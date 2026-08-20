DELIMITER $$

CREATE TRIGGER trg_upg_release_update BEFORE UPDATE ON upg_release FOR EACH ROW
BEGIN
  IF NOT (OLD.tenant_id <=> NEW.tenant_id) OR NOT (OLD.artifact_type <=> NEW.artifact_type)
    OR NOT (OLD.release_version <=> NEW.release_version) OR NOT (OLD.channel_code <=> NEW.channel_code)
    OR NOT (OLD.object_key <=> NEW.object_key) OR NOT (OLD.artifact_sha256 <=> NEW.artifact_sha256)
    OR NOT (OLD.signature_base64 <=> NEW.signature_base64) OR NOT (OLD.key_version <=> NEW.key_version)
    OR NOT (OLD.build_commit <=> NEW.build_commit) OR NOT (OLD.sbom_sha256 <=> NEW.sbom_sha256)
    OR NOT (OLD.manifest_sha256 <=> NEW.manifest_sha256) OR NOT (OLD.min_app_version <=> NEW.min_app_version)
    OR NOT (OLD.max_app_version <=> NEW.max_app_version) OR NOT (OLD.min_protocol_version <=> NEW.min_protocol_version)
    OR NOT (OLD.max_protocol_version <=> NEW.max_protocol_version) OR NOT (OLD.min_schema_version <=> NEW.min_schema_version)
    OR NOT (OLD.max_schema_version <=> NEW.max_schema_version) OR NOT (OLD.min_system_version <=> NEW.min_system_version)
    OR NOT (OLD.max_system_version <=> NEW.max_system_version) OR NOT (OLD.required_capability_sha256 <=> NEW.required_capability_sha256)
    OR NOT (OLD.request_sha256 <=> NEW.request_sha256) OR NOT (OLD.created_by <=> NEW.created_by)
    OR NOT (OLD.correlation_id <=> NEW.correlation_id) OR NOT (OLD.created_at <=> NEW.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg release frozen identity cannot be changed';
  END IF;
  IF NOT ((OLD.state='DRAFT' AND NEW.state='SIGNED') OR (OLD.state='SIGNED' AND NEW.state='STAGED')
    OR (OLD.state<>'REVOKED' AND NEW.state='REVOKED')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg release illegal transition';
  END IF;
  IF NEW.version_no<>OLD.version_no+1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg release version must advance once';
  END IF;
END$$
CREATE TRIGGER trg_upg_release_delete BEFORE DELETE ON upg_release FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg release cannot be deleted'; END$$

CREATE TRIGGER trg_upg_rollout_update BEFORE UPDATE ON upg_rollout FOR EACH ROW
BEGIN
  IF NOT (OLD.tenant_id <=> NEW.tenant_id) OR NOT (OLD.release_id <=> NEW.release_id)
    OR NOT (OLD.canary_percent <=> NEW.canary_percent) OR NOT (OLD.request_sha256 <=> NEW.request_sha256)
    OR NOT (OLD.created_by <=> NEW.created_by) OR NOT (OLD.correlation_id <=> NEW.correlation_id)
    OR NOT (OLD.created_at <=> NEW.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg rollout frozen identity cannot be changed';
  END IF;
  IF NOT ((OLD.state='PLANNED' AND NEW.state='CANARY')
    OR (OLD.state='CANARY' AND NEW.state IN ('ROLLING','PAUSED','FAILED'))
    OR (OLD.state='ROLLING' AND NEW.state IN ('PAUSED','COMPLETED','FAILED'))
    OR (OLD.state='PAUSED' AND NEW.state IN ('CANARY','ROLLING','FAILED'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg rollout illegal transition';
  END IF;
  IF NEW.version_no<>OLD.version_no+1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg rollout version must advance once'; END IF;
END$$
CREATE TRIGGER trg_upg_rollout_delete BEFORE DELETE ON upg_rollout FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg rollout cannot be deleted'; END$$

CREATE TRIGGER trg_upg_task_update BEFORE UPDATE ON upg_terminal_task FOR EACH ROW
BEGIN
  IF NOT (OLD.tenant_id <=> NEW.tenant_id) OR NOT (OLD.rollout_id <=> NEW.rollout_id)
    OR NOT (OLD.release_id <=> NEW.release_id) OR NOT (OLD.device_id <=> NEW.device_id)
    OR NOT (OLD.store_id <=> NEW.store_id) OR NOT (OLD.request_sha256 <=> NEW.request_sha256)
    OR NOT (OLD.created_by <=> NEW.created_by) OR NOT (OLD.correlation_id <=> NEW.correlation_id)
    OR NOT (OLD.created_at <=> NEW.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg terminal task frozen identity cannot be changed';
  END IF;
  IF NOT ((OLD.state='PLANNED' AND NEW.state IN ('DOWNLOADING','FAILED_CLOSED'))
    OR (OLD.state='DOWNLOADING' AND NEW.state IN ('DOWNLOADING','VERIFIED','FAILED_CLOSED'))
    OR (OLD.state='VERIFIED' AND NEW.state IN ('INSTALLING','FAILED_CLOSED'))
    OR (OLD.state='INSTALLING' AND NEW.state IN ('HEALTH_CHECK','FORWARD_FIX_REQUIRED','FAILED_CLOSED'))
    OR (OLD.state='HEALTH_CHECK' AND NEW.state IN ('SUCCEEDED','ROLLED_BACK','FORWARD_FIX_REQUIRED','FAILED_CLOSED'))
    OR (OLD.state='ROLLED_BACK' AND NEW.state='ROLLED_BACK')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg terminal task illegal transition';
  END IF;
  IF NEW.version_no<>OLD.version_no+1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg terminal task version must advance once'; END IF;
END$$
CREATE TRIGGER trg_upg_task_delete BEFORE DELETE ON upg_terminal_task FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg terminal task cannot be deleted'; END$$

CREATE TRIGGER trg_upg_scope_update BEFORE UPDATE ON upg_target_scope FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg target scope append-only'; END$$
CREATE TRIGGER trg_upg_scope_delete BEFORE DELETE ON upg_target_scope FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg target scope cannot be deleted'; END$$
CREATE TRIGGER trg_upg_command_update BEFORE UPDATE ON upg_command_result FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg command result append-only'; END$$
CREATE TRIGGER trg_upg_command_delete BEFORE DELETE ON upg_command_result FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg command result cannot be deleted'; END$$
CREATE TRIGGER trg_upg_event_update BEFORE UPDATE ON upg_release_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg release event append-only'; END$$
CREATE TRIGGER trg_upg_event_delete BEFORE DELETE ON upg_release_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg release event cannot be deleted'; END$$
CREATE TRIGGER trg_upg_audit_update BEFORE UPDATE ON upg_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg audit append-only'; END$$
CREATE TRIGGER trg_upg_audit_delete BEFORE DELETE ON upg_audit FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='upg audit cannot be deleted'; END$$

DELIMITER ;

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201400,'发布治理',0,14,'release','',NULL,'Release',1,0,'M','0','0',NULL,'deployment-unit',103,1,UTC_TIMESTAMP(),NULL,NULL,'Gate 6B Provider无关发布治理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=9201400);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201401,'创建发布',9201400,1,'','','','',1,0,'F','0','0','release:create','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'创建受控发布草稿'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=9201401);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201402,'发布验签',9201400,2,'','','','',1,0,'F','0','0','release:verify','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'摘要签名和密钥版本校验'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=9201402);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201403,'发布灰度',9201400,3,'','','','',1,0,'F','0','0','release:rollout','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'灰度创建、扩散、暂停和完成'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=9201403);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201404,'任务执行证据',9201400,4,'','','','',1,0,'F','0','0','release:task:observe','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'记录软件执行证据；非真实远程命令'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=9201404);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201405,'发布读取',9201400,5,'','','','',1,0,'F','0','0','release:read','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'读取发布与批次状态'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=9201405);
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_dept,create_by,create_time,update_by,update_time,remark)
SELECT 9201406,'吊销发布',9201400,6,'','','','',1,0,'F','0','0','release:revoke','#',103,1,UTC_TIMESTAMP(),NULL,NULL,'停止后续分发且不重写历史'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id=9201406);
