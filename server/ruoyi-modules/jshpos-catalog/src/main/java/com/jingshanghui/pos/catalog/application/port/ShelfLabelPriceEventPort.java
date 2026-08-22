package com.jingshanghui.pos.catalog.application.port;

import com.jingshanghui.pos.catalog.application.model.ShelfLabelModels.PriceBookEvent;

import java.util.List;

/** Pricing Owner 在同一事务中通知 ShelfLabel Owner 的进程内端口。 */
public interface ShelfLabelPriceEventPort {

    /** 处理已发布或已停用价格版本；重放必须返回原任务。 */
    List<Long> handle(PriceBookEvent event);
}
