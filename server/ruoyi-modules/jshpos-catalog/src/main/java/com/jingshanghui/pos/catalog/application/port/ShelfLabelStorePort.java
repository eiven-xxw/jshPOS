package com.jingshanghui.pos.catalog.application.port;

import java.util.List;

/** ShelfLabel Owner 读取 Foundation 门店目录与数据范围的只读端口。 */
public interface ShelfLabelStorePort {

    /** 返回当前可信操作者可访问的有效门店。 */
    List<StoreSnapshot> listAccessibleActiveStores();

    /** 返回指定且已授权门店。 */
    StoreSnapshot requireAccessibleStore(Long storeId);

    /** @param storeId 门店主键 @param storeCode 门店编码 @param storeName 门店名称 */
    record StoreSnapshot(Long storeId, String storeCode, String storeName) {
    }
}
