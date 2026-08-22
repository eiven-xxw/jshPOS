package com.jingshanghui.pos.catalog.application.port;

/** 真实打印尚未解阻时必须失败关闭的设备边界。 */
public interface ShelfLabelPrintPort {

    /**
     * 请求打印预览制品。
     *
     * @param taskId 价签任务
     * @param previewSha256 服务端预览摘要
     * @return 设备执行结果；当前正式适配器只能返回不可用
     */
    DispatchResult dispatch(Long taskId, String previewSha256);

    /** @param accepted 是否接受命令 @param code 稳定结果码 @param message 用户可见说明 */
    record DispatchResult(boolean accepted, String code, String message) {
    }
}
