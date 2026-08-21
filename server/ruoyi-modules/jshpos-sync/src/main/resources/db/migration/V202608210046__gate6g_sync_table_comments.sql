-- Gate 6G 同步数据面表级中文元数据。
ALTER TABLE pos_sync_device COMMENT = '可信POS同步设备注册；租户门店绑定；服务端授权';
ALTER TABLE pos_sync_inbox COMMENT = '服务端同步Inbox幂等收件；租户设备隔离；只追加接收事实';
ALTER TABLE pos_sync_business_fact COMMENT = '已接受POS业务事实索引；租户隔离；不可覆盖';
ALTER TABLE pos_sync_change_feed COMMENT = '服务端下行变更序列；租户门店隔离；单调游标';
ALTER TABLE pos_sync_pull_page COMMENT = '下行同步分页快照；租户设备隔离；摘要校验';
ALTER TABLE pos_sync_cursor COMMENT = 'POS同步ACK与单调游标；租户设备隔离；受控更新';
ALTER TABLE pos_sync_dead_letter COMMENT = '同步失败事件隔离区；租户隔离；人工审计修复';
ALTER TABLE pos_sync_security_event COMMENT = '同步租户权限与协议安全事件；租户隔离；只追加';
