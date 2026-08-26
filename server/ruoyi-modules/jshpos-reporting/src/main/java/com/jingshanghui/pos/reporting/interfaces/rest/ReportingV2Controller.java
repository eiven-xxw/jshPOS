package com.jingshanghui.pos.reporting.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.SalesPageQuery;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.InventoryCostPageQuery;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.SalesPageView;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.InventoryCostPageView;
import com.jingshanghui.pos.reporting.application.service.ReportQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 销售与库存成本版本化分页接口；旧 v1 列表端点继续处于冻结兼容窗口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2")
public class ReportingV2Controller {
    private final ReportQueryService queryService;

    @GetMapping("/reports/sales-daily")
    @SaCheckPermission("report:operation:read")
    public R<SalesPageView> salesPage(@RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
                                      @RequestParam @Positive Long storeId,
                                      @RequestParam(required = false) @Size(max = 64) String terminalId,
                                      @RequestParam(required = false) @Positive Long cashierId,
                                      @RequestParam(required = false) @Size(max = 2048) String cursor,
                                      @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        return R.ok(queryService.salesPage(new SalesPageQuery(fromDate, toDate, storeId, terminalId, cashierId,
            cursor, limit)));
    }

    @GetMapping("/reports/inventory-cost-daily")
    @SaCheckPermission("report:operation:read")
    public R<InventoryCostPageView> inventoryCostPage(
        @RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
        @RequestParam @Positive Long storeId,
        @RequestParam(required = false) @Size(max = 26) String warehouseId,
        @RequestParam(required = false) @Positive Long skuId,
        @RequestParam(required = false) @Size(max = 2048) String cursor,
        @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        return R.ok(queryService.inventoryCostPage(new InventoryCostPageQuery(fromDate, toDate, storeId,
            warehouseId, skuId, cursor, limit)));
    }
}
