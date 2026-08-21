-- Gate 6G 商品、价格和数据包表级中文元数据。
ALTER TABLE cat_category COMMENT = '商品分类主数据；租户隔离；Catalog Owner受控写入';
ALTER TABLE cat_brand COMMENT = '商品品牌主数据；租户隔离；Catalog Owner受控写入';
ALTER TABLE cat_unit COMMENT = '计量单位主数据；租户隔离；Catalog Owner受控写入';
ALTER TABLE cat_spu COMMENT = '商品SPU主数据；租户隔离；Catalog Owner受控写入';
ALTER TABLE cat_sku COMMENT = '可销售SKU主数据；租户隔离；Catalog Owner受控写入';
ALTER TABLE cat_sku_unit COMMENT = 'SKU多单位与精确换算；租户隔离；Catalog Owner受控写入';
ALTER TABLE cat_barcode COMMENT = '商品条码绑定；租户内唯一；Catalog Owner受控写入';
ALTER TABLE cat_import_batch COMMENT = '商品导入预检与发布批次；租户隔离；Catalog Owner状态迁移';
ALTER TABLE cat_import_record COMMENT = '商品导入规范化暂存记录；租户隔离；发布前不可见';
ALTER TABLE cat_import_error COMMENT = '商品导入错误明细；租户隔离；只追加诊断';
ALTER TABLE cat_catalog_binding COMMENT = '租户商品目录当前版本指针；租户隔离；原子切换';
ALTER TABLE prc_price_book COMMENT = '版本化基础价与门店价价格簿；租户隔离；发布后不可变';
ALTER TABLE prc_price_item COMMENT = '价格簿精确金额明细；租户隔离；最小货币单位';
ALTER TABLE dpk_catalog_package COMMENT = '正式商品价格数据包清单；租户门店绑定；摘要签名校验';
ALTER TABLE cat_event_outbox COMMENT = 'Catalog Owner领域事件Outbox；租户隔离；只追加投递';
