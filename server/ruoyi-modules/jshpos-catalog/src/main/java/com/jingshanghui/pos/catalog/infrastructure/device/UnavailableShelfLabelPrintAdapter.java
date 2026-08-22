package com.jingshanghui.pos.catalog.infrastructure.device;

import com.jingshanghui.pos.catalog.application.port.ShelfLabelPrintPort;
import org.springframework.stereotype.Component;

/** T2-PRN-001 未解阻前的正式失败关闭适配器，不包含任何设备 SDK 或网络能力。 */
@Component
public class UnavailableShelfLabelPrintAdapter implements ShelfLabelPrintPort {

    @Override
    public DispatchResult dispatch(Long taskId, String previewSha256) {
        return new DispatchResult(false, "PRINTER_UNAVAILABLE",
            "T2-PRN-001 仍为 BLOCKED；当前只能预览，禁止把软件结果标记为真实打印成功");
    }
}
