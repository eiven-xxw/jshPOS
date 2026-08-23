package com.jingshanghui.pos.subscription.application.service;

import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.ScanResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * RuoYi Job 可调用的具名订阅到期任务入口。
 * 模块不会自行启用 @Scheduled；任务授权、频率与停用由运维显式配置。
 */
@Component("subscriptionExpiryJob")
@RequiredArgsConstructor
public class SubscriptionExpiryJob {
    private final SubscriptionApplicationService service;
    public ScanResult execute(String runnerId) { return service.runExpiryScanAsSystem(runnerId); }
}
