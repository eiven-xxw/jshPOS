package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportBatchView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PriceBookView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PriceCandidateView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 所有原生 SQL 都显式携带可信 tenant_id；框架租户拦截器构成第二道防线。 */
public interface CatalogMapper {

    /** 读取启用 SKU 的基础单位快照，tenantId 必须来自可信上下文。 */
    @Select("""
        SELECT s.sku_id skuId,s.sku_code skuCode,u.unit_id unitId,u.unit_id baseUnitId,su.ratio_numerator numerator,
               su.ratio_denominator denominator,su.primary_unit primaryUnit
        FROM cat_sku s
        JOIN cat_sku_unit su ON su.tenant_id=s.tenant_id AND su.sku_id=s.sku_id AND su.primary_unit=1
        JOIN cat_unit u ON u.tenant_id=su.tenant_id AND u.unit_id=su.unit_id
        WHERE s.tenant_id=#{tenantId} AND s.sku_id=#{skuId} AND s.status='ACTIVE' AND u.status='ACTIVE'
        """)
    SkuUnitSnapshot findInventoryPrimaryUnit(@Param("tenantId") String tenantId, @Param("skuId") Long skuId);

    /** 读取指定采购单位的冻结换算快照。 */
    @Select("""
        SELECT s.sku_id skuId,s.sku_code skuCode,u.unit_id unitId,psu.unit_id baseUnitId,su.ratio_numerator numerator,
               su.ratio_denominator denominator,su.primary_unit primaryUnit
        FROM cat_sku s
        JOIN cat_sku_unit su ON su.tenant_id=s.tenant_id AND su.sku_id=s.sku_id
        JOIN cat_sku_unit psu ON psu.tenant_id=s.tenant_id AND psu.sku_id=s.sku_id AND psu.primary_unit=1
        JOIN cat_unit u ON u.tenant_id=su.tenant_id AND u.unit_id=su.unit_id
        WHERE s.tenant_id=#{tenantId} AND s.sku_id=#{skuId} AND u.unit_id=#{unitId}
          AND s.status='ACTIVE' AND u.status='ACTIVE'
        """)
    SkuUnitSnapshot findInventorySkuUnit(@Param("tenantId") String tenantId, @Param("skuId") Long skuId,
                                         @Param("unitId") Long unitId);

    @Insert("""
        INSERT INTO cat_event_outbox(outbox_id,tenant_id,event_type,aggregate_type,aggregate_id,aggregate_version,
          payload_json,payload_sha256,delivery_state,available_at)
        VALUES(#{outboxId},#{tenantId},#{eventType},#{aggregateType},#{aggregateId},#{aggregateVersion},
          CAST(#{payloadJson} AS JSON),#{payloadSha256},'NEW',#{availableAt})
        """)
    int insertOutbox(@Param("tenantId") String tenantId, @Param("outboxId") Long outboxId,
                     @Param("eventType") String eventType, @Param("aggregateType") String aggregateType,
                     @Param("aggregateId") Long aggregateId, @Param("aggregateVersion") long aggregateVersion,
                     @Param("payloadJson") String payloadJson, @Param("payloadSha256") String payloadSha256,
                     @Param("availableAt") LocalDateTime availableAt);

    @Insert("""
        INSERT INTO cat_category(category_id,tenant_id,parent_id,category_code,category_name,status,sort_no)
        VALUES(#{id},#{tenantId},#{parentId},#{code},#{name},'ACTIVE',#{sortNo})
        """)
    int insertCategory(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("parentId") Long parentId,
                       @Param("code") String code, @Param("name") String name, @Param("sortNo") int sortNo);

    @Insert("""
        INSERT INTO cat_brand(brand_id,tenant_id,brand_code,brand_name,status)
        VALUES(#{id},#{tenantId},#{code},#{name},'ACTIVE')
        """)
    int insertBrand(@Param("tenantId") String tenantId, @Param("id") Long id,
                    @Param("code") String code, @Param("name") String name);

    @Insert("""
        INSERT INTO cat_unit(unit_id,tenant_id,unit_code,unit_name,decimal_scale,status)
        VALUES(#{id},#{tenantId},#{code},#{name},#{scale},'ACTIVE')
        """)
    int insertUnit(@Param("tenantId") String tenantId, @Param("id") Long id,
                   @Param("code") String code, @Param("name") String name, @Param("scale") int scale);

    @Select("SELECT category_id id,category_code code,category_name name,status FROM cat_category WHERE tenant_id=#{tenantId} AND category_id=#{id}")
    DefinitionView findCategory(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT brand_id id,brand_code code,brand_name name,status FROM cat_brand WHERE tenant_id=#{tenantId} AND brand_id=#{id}")
    DefinitionView findBrand(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT unit_id id,unit_code code,unit_name name,status FROM cat_unit WHERE tenant_id=#{tenantId} AND unit_id=#{id}")
    DefinitionView findUnit(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Insert("""
        INSERT INTO cat_spu(spu_id,tenant_id,spu_code,spu_name,category_id,brand_id,attributes_json,status)
        VALUES(#{spuId},#{tenantId},#{spuCode},#{name},#{categoryId},#{brandId},CAST(#{attributesJson} AS JSON),'DRAFT')
        """)
    int insertSpu(@Param("tenantId") String tenantId, @Param("spuId") Long spuId,
                  @Param("spuCode") String spuCode, @Param("name") String name,
                  @Param("categoryId") Long categoryId, @Param("brandId") Long brandId,
                  @Param("attributesJson") String attributesJson);

    @Insert("""
        INSERT INTO cat_sku(sku_id,tenant_id,spu_id,sku_code,sku_name,product_type,attributes_json,status)
        VALUES(#{skuId},#{tenantId},#{spuId},#{skuCode},#{name},#{productType},CAST(#{attributesJson} AS JSON),'DRAFT')
        """)
    int insertSku(@Param("tenantId") String tenantId, @Param("skuId") Long skuId, @Param("spuId") Long spuId,
                  @Param("skuCode") String skuCode, @Param("name") String name,
                  @Param("productType") String productType, @Param("attributesJson") String attributesJson);

    @Insert("""
        INSERT INTO cat_sku_unit(sku_unit_id,tenant_id,sku_id,unit_id,ratio_numerator,ratio_denominator,primary_unit)
        VALUES(#{skuUnitId},#{tenantId},#{skuId},#{unitId},#{numerator},#{denominator},#{primaryUnit})
        """)
    int insertSkuUnit(@Param("tenantId") String tenantId, @Param("skuUnitId") Long skuUnitId,
                      @Param("skuId") Long skuId, @Param("unitId") Long unitId,
                      @Param("numerator") long numerator, @Param("denominator") long denominator,
                      @Param("primaryUnit") boolean primaryUnit);

    @Insert("""
        INSERT INTO cat_barcode(barcode_id,tenant_id,sku_id,sku_unit_id,barcode_value,barcode_type,status)
        VALUES(#{barcodeId},#{tenantId},#{skuId},#{skuUnitId},#{barcode},#{barcodeType},'ACTIVE')
        """)
    int insertBarcode(@Param("tenantId") String tenantId, @Param("barcodeId") Long barcodeId,
                      @Param("skuId") Long skuId, @Param("skuUnitId") Long skuUnitId,
                      @Param("barcode") String barcode, @Param("barcodeType") String barcodeType);

    @Select("""
        SELECT s.sku_id skuId,s.spu_id spuId,p.spu_code spuCode,s.sku_code skuCode,s.sku_name name,
               p.category_id categoryId,p.brand_id brandId,s.product_type productType,s.status,s.version
        FROM cat_sku s JOIN cat_spu p ON p.tenant_id=s.tenant_id AND p.spu_id=s.spu_id
        WHERE s.tenant_id=#{tenantId} AND (#{status} IS NULL OR s.status=#{status})
        ORDER BY s.sku_code LIMIT #{limit}
        """)
    List<ProductView> listProducts(@Param("tenantId") String tenantId, @Param("status") String status,
                                   @Param("limit") int limit);

    @Select("""
        SELECT s.sku_id skuId,s.spu_id spuId,p.spu_code spuCode,s.sku_code skuCode,s.sku_name name,
               p.category_id categoryId,p.brand_id brandId,s.product_type productType,s.status,s.version
        FROM cat_sku s JOIN cat_spu p ON p.tenant_id=s.tenant_id AND p.spu_id=s.spu_id
        WHERE s.tenant_id=#{tenantId} AND s.sku_id=#{skuId}
        """)
    ProductView findProduct(@Param("tenantId") String tenantId, @Param("skuId") Long skuId);

    @Update("UPDATE cat_sku SET status=#{state},version=version+1 WHERE tenant_id=#{tenantId} AND sku_id=#{skuId} AND version=#{version}")
    int updateProductState(@Param("tenantId") String tenantId, @Param("skuId") Long skuId,
                           @Param("state") String state, @Param("version") int version);

    @Insert("""
        INSERT INTO cat_import_batch(import_batch_id,tenant_id,idempotency_key,payload_sha256,row_count,error_count,state,previous_batch_id)
        VALUES(#{batchId},#{tenantId},#{key},#{hash},#{rows},#{errors},#{state},#{previousBatchId})
        """)
    int insertImportBatch(@Param("tenantId") String tenantId, @Param("batchId") Long batchId,
                          @Param("key") String key, @Param("hash") String hash,
                          @Param("rows") int rows, @Param("errors") int errors,
                          @Param("state") String state, @Param("previousBatchId") Long previousBatchId);

    @Select("""
        SELECT import_batch_id importBatchId,idempotency_key idempotencyKey,payload_sha256 payloadSha256,
               row_count rowCount,error_count errorCount,state,previous_batch_id previousBatchId
        FROM cat_import_batch WHERE tenant_id=#{tenantId} AND idempotency_key=#{key}
        """)
    ImportBatchView findImportByKey(@Param("tenantId") String tenantId, @Param("key") String key);

    @Select("""
        SELECT import_batch_id importBatchId,idempotency_key idempotencyKey,payload_sha256 payloadSha256,
               row_count rowCount,error_count errorCount,state,previous_batch_id previousBatchId
        FROM cat_import_batch WHERE tenant_id=#{tenantId} AND import_batch_id=#{batchId}
        """)
    ImportBatchView findImport(@Param("tenantId") String tenantId, @Param("batchId") Long batchId);

    @Insert("""
        INSERT INTO cat_import_record(import_record_id,tenant_id,import_batch_id,source_row_no,sku_code,canonical_json,record_sha256)
        VALUES(#{recordId},#{tenantId},#{batchId},#{rowNumber},#{skuCode},CAST(#{canonicalJson} AS JSON),#{recordHash})
        """)
    int insertImportRecord(@Param("tenantId") String tenantId, @Param("recordId") Long recordId,
                           @Param("batchId") Long batchId, @Param("rowNumber") int rowNumber,
                           @Param("skuCode") String skuCode, @Param("canonicalJson") String canonicalJson,
                           @Param("recordHash") String recordHash);

    @Insert("""
        INSERT INTO cat_import_error(import_error_id,tenant_id,import_batch_id,source_row_no,field_code,error_message)
        VALUES(#{errorId},#{tenantId},#{batchId},#{rowNumber},#{field},#{message})
        """)
    int insertImportError(@Param("tenantId") String tenantId, @Param("errorId") Long errorId,
                          @Param("batchId") Long batchId, @Param("rowNumber") int rowNumber,
                          @Param("field") String field, @Param("message") String message);

    @Select("SELECT current_batch_id FROM cat_catalog_binding WHERE tenant_id=#{tenantId}")
    Long findCurrentImportBatch(@Param("tenantId") String tenantId);

    @Select("SELECT previous_batch_id FROM cat_catalog_binding WHERE tenant_id=#{tenantId}")
    Long findPreviousImportBatch(@Param("tenantId") String tenantId);

    @Insert("""
        INSERT INTO cat_catalog_binding(catalog_binding_id,tenant_id,current_batch_id,previous_batch_id,activated_at)
        VALUES(#{bindingId},#{tenantId},#{batchId},#{previousBatchId},#{activatedAt})
        ON DUPLICATE KEY UPDATE previous_batch_id=current_batch_id,current_batch_id=VALUES(current_batch_id),
                                activated_at=VALUES(activated_at),version=version+1
        """)
    int activateImportBatch(@Param("tenantId") String tenantId, @Param("bindingId") Long bindingId,
                            @Param("batchId") Long batchId, @Param("previousBatchId") Long previousBatchId,
                            @Param("activatedAt") LocalDateTime activatedAt);

    @Update("UPDATE cat_import_batch SET state='PUBLISHED',published_at=#{at},version=version+1 WHERE tenant_id=#{tenantId} AND import_batch_id=#{batchId} AND state='PRECHECKED'")
    int publishImportBatch(@Param("tenantId") String tenantId, @Param("batchId") Long batchId,
                           @Param("at") LocalDateTime at);

    @Update("""
        UPDATE cat_catalog_binding SET current_batch_id=#{previousBatchId},previous_batch_id=#{batchId},
          activated_at=#{at},version=version+1
        WHERE tenant_id=#{tenantId} AND current_batch_id=#{batchId} AND previous_batch_id=#{previousBatchId}
        """)
    int rollbackImportBinding(@Param("tenantId") String tenantId, @Param("batchId") Long batchId,
                              @Param("previousBatchId") Long previousBatchId, @Param("at") LocalDateTime at);

    @Update("UPDATE cat_import_batch SET state='ROLLED_BACK',version=version+1 WHERE tenant_id=#{tenantId} AND import_batch_id=#{batchId} AND state='PUBLISHED'")
    int markImportRolledBack(@Param("tenantId") String tenantId, @Param("batchId") Long batchId);

    @Insert("""
        INSERT INTO prc_price_book(price_book_id,tenant_id,book_code,book_name,version_no,scope_type,store_id,state)
        VALUES(#{bookId},#{tenantId},#{code},#{name},#{versionNo},#{scopeType},#{storeId},'DRAFT')
        """)
    int insertPriceBook(@Param("tenantId") String tenantId, @Param("bookId") Long bookId,
                        @Param("code") String code, @Param("name") String name,
                        @Param("versionNo") int versionNo, @Param("scopeType") String scopeType,
                        @Param("storeId") Long storeId);

    @Select("""
        SELECT price_book_id priceBookId,book_code bookCode,book_name bookName,version_no versionNo,
               scope_type scopeType,store_id storeId,state,content_sha256 contentSha256
        FROM prc_price_book WHERE tenant_id=#{tenantId} AND price_book_id=#{bookId}
        """)
    PriceBookView findPriceBook(@Param("tenantId") String tenantId, @Param("bookId") Long bookId);

    @Insert("""
        INSERT INTO prc_price_item(price_item_id,tenant_id,price_book_id,sku_id,unit_id,amount_minor,currency,effective_from,effective_to)
        VALUES(#{itemId},#{tenantId},#{bookId},#{skuId},#{unitId},#{amount},'CNY',#{from},#{to})
        """)
    int insertPriceItem(@Param("tenantId") String tenantId, @Param("itemId") Long itemId,
                        @Param("bookId") Long bookId, @Param("skuId") Long skuId,
                        @Param("unitId") Long unitId, @Param("amount") long amount,
                        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
        SELECT COUNT(*) FROM prc_price_item i JOIN prc_price_book b
          ON b.tenant_id=i.tenant_id AND b.price_book_id=i.price_book_id
        WHERE i.tenant_id=#{tenantId} AND i.sku_id=#{skuId} AND i.unit_id=#{unitId}
          AND b.scope_type=#{scopeType} AND (b.store_id <=> #{storeId})
          AND b.state='DRAFT' AND b.price_book_id=#{bookId}
          AND i.effective_from < COALESCE(#{to},'9999-12-31 23:59:59.999999')
          AND COALESCE(i.effective_to,'9999-12-31 23:59:59.999999') > #{from}
        """)
    int countPriceOverlap(@Param("tenantId") String tenantId, @Param("bookId") Long bookId,
                          @Param("scopeType") String scopeType, @Param("storeId") Long storeId,
                          @Param("skuId") Long skuId, @Param("unitId") Long unitId,
                          @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
        SELECT CONCAT(LPAD(i.price_item_id,20,'0'),'|',i.sku_id,'|',i.unit_id,'|',i.amount_minor,'|',
                      DATE_FORMAT(i.effective_from,'%Y-%m-%dT%H:%i:%s.%fZ'),'|',COALESCE(DATE_FORMAT(i.effective_to,'%Y-%m-%dT%H:%i:%s.%fZ'),''))
        FROM prc_price_item i WHERE i.tenant_id=#{tenantId} AND i.price_book_id=#{bookId}
        ORDER BY i.sku_id,i.unit_id,i.effective_from,i.price_item_id
        """)
    List<String> listPriceCanonicalRows(@Param("tenantId") String tenantId, @Param("bookId") Long bookId);

    @Update("UPDATE prc_price_book SET state='PUBLISHED',content_sha256=#{hash},published_at=#{at},version=version+1 WHERE tenant_id=#{tenantId} AND price_book_id=#{bookId} AND state='DRAFT'")
    int publishPriceBook(@Param("tenantId") String tenantId, @Param("bookId") Long bookId,
                         @Param("hash") String hash, @Param("at") LocalDateTime at);

    @Update("UPDATE prc_price_book SET state='RETIRED',version=version+1 WHERE tenant_id=#{tenantId} AND price_book_id=#{bookId} AND state='PUBLISHED'")
    int retirePriceBook(@Param("tenantId") String tenantId, @Param("bookId") Long bookId);

    @Select("""
        SELECT b.price_book_id priceBookId,i.price_item_id priceItemId,b.version_no versionNo,b.scope_type scopeType,
               b.store_id scopeStoreId,i.amount_minor amountMinor,i.currency,
               i.effective_from effectiveFrom,i.effective_to effectiveTo,(b.state='PUBLISHED') published
        FROM prc_price_item i JOIN prc_price_book b ON b.tenant_id=i.tenant_id AND b.price_book_id=i.price_book_id
        WHERE i.tenant_id=#{tenantId} AND i.sku_id=#{skuId} AND i.unit_id=#{unitId}
          AND (b.scope_type='TENANT_BASE' OR (b.scope_type='STORE' AND b.store_id=#{storeId}))
          AND i.effective_from<=#{at} AND (i.effective_to IS NULL OR i.effective_to>#{at})
        """)
    List<PriceCandidateView> listPriceCandidates(@Param("tenantId") String tenantId,
                                                 @Param("skuId") Long skuId, @Param("unitId") Long unitId,
                                                 @Param("storeId") Long storeId, @Param("at") LocalDateTime at);

    @Select("""
        SELECT CAST(JSON_OBJECT(
          'skuId',CAST(s.sku_id AS CHAR),'skuCode',s.sku_code,'name',s.sku_name,
          'productType',s.product_type,'status',s.status,
          'categoryId',CAST(p.category_id AS CHAR),
          'brandId',IF(p.brand_id IS NULL,NULL,CAST(p.brand_id AS CHAR)),
          'unitId',CAST(su.unit_id AS CHAR),'unitCode',u.unit_code,'unitName',u.unit_name,
          'decimalScale',u.decimal_scale,'ratioNumerator',su.ratio_numerator,
          'ratioDenominator',su.ratio_denominator,
          'barcode',(SELECT bc.barcode_value FROM cat_barcode bc
            WHERE bc.tenant_id=s.tenant_id AND bc.sku_unit_id=su.sku_unit_id AND bc.status='ACTIVE'
            ORDER BY bc.barcode_id LIMIT 1)
        ) AS CHAR)
        FROM cat_sku s
        JOIN cat_spu p ON p.tenant_id=s.tenant_id AND p.spu_id=s.spu_id
        JOIN cat_sku_unit su ON su.tenant_id=s.tenant_id AND su.sku_id=s.sku_id AND su.primary_unit=TRUE
        JOIN cat_unit u ON u.tenant_id=su.tenant_id AND u.unit_id=su.unit_id
        WHERE s.tenant_id=#{tenantId} AND s.status='ACTIVE' AND p.status='ACTIVE' AND u.status='ACTIVE'
        ORDER BY s.sku_id
        """)
    List<String> listProductPackageRows(@Param("tenantId") String tenantId);

    @Select("""
        SELECT CAST(JSON_OBJECT(
          'priceBookId',CAST(b.price_book_id AS CHAR),'bookCode',b.book_code,
          'versionNo',b.version_no,'scopeType',b.scope_type,
          'storeId',IF(b.store_id IS NULL,NULL,CAST(b.store_id AS CHAR)),
          'skuId',CAST(i.sku_id AS CHAR),'unitId',CAST(i.unit_id AS CHAR),
          'amountMinor',i.amount_minor,'currency',i.currency,
          'effectiveFrom',DATE_FORMAT(i.effective_from,'%Y-%m-%dT%H:%i:%s.%fZ'),
          'effectiveTo',IF(i.effective_to IS NULL,NULL,DATE_FORMAT(i.effective_to,'%Y-%m-%dT%H:%i:%s.%fZ'))
        ) AS CHAR)
        FROM prc_price_item i JOIN prc_price_book b ON b.tenant_id=i.tenant_id AND b.price_book_id=i.price_book_id
        WHERE i.tenant_id=#{tenantId} AND b.state='PUBLISHED'
          AND (b.scope_type='TENANT_BASE' OR b.store_id=#{storeId})
        ORDER BY b.scope_type,b.book_code,b.version_no,i.sku_id,i.unit_id,i.effective_from
        """)
    List<String> listPricePackageRows(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);

    @Insert("""
        INSERT INTO dpk_catalog_package(package_id,tenant_id,store_id,package_version,previous_version,schema_version,
          payload_sha256,signature_algorithm,signing_key_id,object_key,record_count,state,generated_at)
        VALUES(#{packageId},#{tenantId},#{storeId},#{packageVersion},#{previousVersion},#{schemaVersion},#{hash},
          'Ed25519',#{keyId},#{objectKey},#{recordCount},'AVAILABLE',#{generatedAt})
        """)
    int insertPackage(@Param("tenantId") String tenantId, @Param("packageId") Long packageId,
                      @Param("storeId") Long storeId, @Param("packageVersion") long packageVersion,
                      @Param("previousVersion") long previousVersion, @Param("schemaVersion") String schemaVersion,
                      @Param("hash") String hash, @Param("keyId") String keyId,
                      @Param("objectKey") String objectKey, @Param("recordCount") int recordCount,
                      @Param("generatedAt") LocalDateTime generatedAt);

    @Select("""
        SELECT package_id packageId,store_id storeId,package_version packageVersion,previous_version previousVersion,
               schema_version schemaVersion,payload_sha256 payloadSha256,signature_algorithm signatureAlgorithm,
               signing_key_id signingKeyId,object_key objectKey,record_count recordCount,generated_at generatedAt
        FROM dpk_catalog_package WHERE tenant_id=#{tenantId} AND store_id=#{storeId}
        ORDER BY package_version DESC LIMIT 1
        """)
    PackageView findLatestPackage(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);

    @Select("""
        SELECT package_id packageId,store_id storeId,package_version packageVersion,previous_version previousVersion,
               schema_version schemaVersion,payload_sha256 payloadSha256,signature_algorithm signatureAlgorithm,
               signing_key_id signingKeyId,object_key objectKey,record_count recordCount,generated_at generatedAt
        FROM dpk_catalog_package
        WHERE tenant_id=#{tenantId} AND store_id=#{storeId} AND package_version=#{packageVersion}
          AND state='AVAILABLE'
        """)
    PackageView findPackage(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                            @Param("packageVersion") long packageVersion);
}
