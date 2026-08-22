INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,
  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 9200560,'批次与效期',0,56,'lot-expiry','operations/lot-expiry/index',NULL,'LotExpiry',
  1,0,'C','0','0','inventory:lot:read','list',1,CURRENT_TIMESTAMP,'T2-LOT-001 社区超市可选批次效期工作台'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200560 OR perms='inventory:lot:read');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,
  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 9200561,'发布批次策略',9200560,1,'#','',NULL,'',1,0,'F','0','0',
  'catalog:lot-policy:publish','#',1,CURRENT_TIMESTAMP,'发布不可变社区超市批次效期策略'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200561 OR perms='catalog:lot-policy:publish');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,
  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 9200562,'查询批次策略',9200560,2,'#','',NULL,'',1,0,'F','0','0',
  'catalog:lot-policy:read','#',1,CURRENT_TIMESTAMP,'按可信门店范围查询生效策略'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200562 OR perms='catalog:lot-policy:read');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,
  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 9200563,'重建批次投影',9200560,3,'#','',NULL,'',1,0,'F','0','0',
  'inventory:lot:rebuild','#',1,CURRENT_TIMESTAMP,'受审计从不可变批次流水重建投影'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200563 OR perms='inventory:lot:rebuild');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,
  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 9200564,'下载批次数据包',9200560,4,'#','',NULL,'',1,0,'F','0','0',
  'inventory:lot-package:read','#',1,CURRENT_TIMESTAMP,'仅下载签名且绑定门店的离线批次包'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200564 OR perms='inventory:lot-package:read');

INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,
  is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
SELECT 9200565,'发布批次数据包',9200560,5,'#','',NULL,'',1,0,'F','0','0',
  'inventory:lot-package:publish','#',1,CURRENT_TIMESTAMP,'幂等发布独立单调版本的签名批次包'
WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE menu_id=9200565 OR perms='inventory:lot-package:publish');
