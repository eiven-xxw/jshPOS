-- CR-T2G9R4-014：创建草稿的聚合版本按既有契约从 0 开始。
-- 只以前向方式修正检查约束；不得回写 V202608170018、调拨事实或事件正文。
ALTER TABLE inv_transfer_event_outbox
    DROP CHECK ck_trf_outbox_version,
    ADD CONSTRAINT ck_trf_outbox_version CHECK (aggregate_version >= 0);
