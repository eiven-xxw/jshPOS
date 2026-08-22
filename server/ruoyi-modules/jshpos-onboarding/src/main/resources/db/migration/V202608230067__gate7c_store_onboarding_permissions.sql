INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,query_param,route_name,is_frame,is_cache,
    menu_type,visible,status,perms,icon,create_time,remark)
VALUES
(9200550,'门店开通计划',0,55,'onboarding','operations/store-onboarding/index',NULL,'StoreOnboarding',1,0,'C','0','0','onboarding:plan:read','shop',NOW(),'T2-ONB-001'),
(9200551,'创建开店计划',9200550,1,'',NULL,NULL,NULL,1,0,'F','0','0','onboarding:plan:create','#',NOW(),'T2-ONB-001'),
(9200552,'开店预检',9200550,2,'',NULL,NULL,NULL,1,0,'F','0','0','onboarding:plan:preflight','#',NOW(),'T2-ONB-001'),
(9200553,'审批开店计划',9200550,3,'',NULL,NULL,NULL,1,0,'F','0','0','onboarding:plan:approve','#',NOW(),'T2-ONB-001'),
(9200554,'应用开店计划',9200550,4,'',NULL,NULL,NULL,1,0,'F','0','0','onboarding:plan:apply','#',NOW(),'T2-ONB-001'),
(9200555,'执行开店检查',9200550,5,'',NULL,NULL,NULL,1,0,'F','0','0','onboarding:plan:check','#',NOW(),'T2-ONB-001'),
(9200556,'确认开店',9200550,6,'',NULL,NULL,NULL,1,0,'F','0','0','onboarding:plan:open','#',NOW(),'T2-ONB-001'),
(9200557,'取消开店计划',9200550,7,'',NULL,NULL,NULL,1,0,'F','0','0','onboarding:plan:cancel','#',NOW(),'T2-ONB-001');
