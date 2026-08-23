INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
  menu_type,visible,status,perms,icon,create_time,remark)
VALUES
(9200570,'门店业务日日结',0,57,'daily-close','operations/daily-close/index',NULL,'StoreDailyClose',1,0,'C','0','0','operations:daily-close:read','calendar',NOW(),'T2-CLS-001'),
(9200571,'创建门店日结',9200570,1,'',NULL,NULL,NULL,1,0,'F','0','0','operations:daily-close:create','#',NOW(),'T2-CLS-001'),
(9200572,'执行日结预检',9200570,2,'',NULL,NULL,NULL,1,0,'F','0','0','operations:daily-close:preflight','#',NOW(),'T2-CLS-001'),
(9200573,'审批门店日结',9200570,3,'',NULL,NULL,NULL,1,0,'F','0','0','operations:daily-close:approve','#',NOW(),'T2-CLS-001'),
(9200574,'签署门店日结',9200570,4,'',NULL,NULL,NULL,1,0,'F','0','0','operations:daily-close:sign','#',NOW(),'T2-CLS-001'),
(9200575,'扫描晚到事实',9200570,5,'',NULL,NULL,NULL,1,0,'F','0','0','operations:daily-close:late-fact','#',NOW(),'T2-CLS-001');
