INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_time,remark)
VALUES
(9200580,'统一异常中心',0,58,'exception-center','operations/exception-center/index',NULL,'OperationsExceptionCenter',1,0,'C','0','0','operations:exception:read','warning',NOW(),'T2-EXC-001'),
(9200581,'扫描Owner异常',9200580,1,'',NULL,NULL,NULL,1,0,'F','0','0','operations:exception:scan','#',NOW(),'T2-EXC-001'),
(9200582,'认领异常案件',9200580,2,'',NULL,NULL,NULL,1,0,'F','0','0','operations:exception:claim','#',NOW(),'T2-EXC-001'),
(9200583,'处置异常案件',9200580,3,'',NULL,NULL,NULL,1,0,'F','0','0','operations:exception:operate','#',NOW(),'T2-EXC-001'),
(9200584,'执行Owner修复',9200580,4,'',NULL,NULL,NULL,1,0,'F','0','0','operations:exception:repair','#',NOW(),'T2-EXC-001'),
(9200585,'复核异常案件',9200580,5,'',NULL,NULL,NULL,1,0,'F','0','0','operations:exception:review','#',NOW(),'T2-EXC-001'),
(9200586,'关闭重开异常',9200580,6,'',NULL,NULL,NULL,1,0,'F','0','0','operations:exception:close','#',NOW(),'T2-EXC-001');
