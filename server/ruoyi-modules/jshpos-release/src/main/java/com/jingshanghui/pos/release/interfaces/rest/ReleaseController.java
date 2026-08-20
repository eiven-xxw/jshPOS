package com.jingshanghui.pos.release.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.release.application.service.ReleaseGovernanceService;
import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import com.jingshanghui.pos.release.interfaces.rest.dto.ReleaseRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 发布治理管理API；仅生成软件任务，不发送厂商安装、固件、重启或远程控制命令。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/releases")
public class ReleaseController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final ReleaseGovernanceService service;

    @PostMapping
    @SaCheckPermission("release:create")
    @Log(title="创建发布草稿",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<ReleaseSummary> create(@RequestHeader("X-Idempotency-Key")
                                    @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key,
                                    @Valid @RequestBody ReleaseRequests.Create request) {
        Release value = service.create(new CreateRelease(request.artifactType(), request.version(), request.channel(),
            request.objectKey(), request.artifactSha256(), request.signatureBase64(), request.keyVersion(),
            request.buildCommit(), request.sbomSha256(), request.compatibility().toDomain(),
            request.targetStoreIds(), key));
        return R.ok(summary(value));
    }

    @PostMapping("/{releaseId}/verify")
    @SaCheckPermission("release:verify")
    @Log(title="校验并冻结发布物",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public R<ReleaseSummary> verify(@PathVariable @Pattern(regexp=ULID) String releaseId,
                                    @RequestHeader("X-Idempotency-Key") @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key) {
        return R.ok(summary(service.verifyAndSign(new ReleaseCommand(releaseId,key))));
    }

    @PostMapping("/{releaseId}/stage")
    @SaCheckPermission("release:rollout")
    public R<ReleaseSummary> stage(@PathVariable @Pattern(regexp=ULID) String releaseId,
                                   @RequestHeader("X-Idempotency-Key") @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key) {
        return R.ok(summary(service.stage(new ReleaseCommand(releaseId,key))));
    }

    @PostMapping("/{releaseId}/revoke")
    @SaCheckPermission("release:revoke")
    @Log(title="吊销发布物",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public R<ReleaseSummary> revoke(@PathVariable @Pattern(regexp=ULID) String releaseId,
                                    @RequestHeader("X-Idempotency-Key") @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key) {
        return R.ok(summary(service.revoke(new ReleaseCommand(releaseId,key))));
    }

    @PostMapping("/{releaseId}/rollouts")
    @SaCheckPermission("release:rollout")
    @Log(title="创建发布灰度",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<RolloutSummary> rollout(@PathVariable @Pattern(regexp=ULID) String releaseId,
                                     @RequestHeader("X-Idempotency-Key") @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key,
                                     @Valid @RequestBody ReleaseRequests.Rollout request) {
        return R.ok(rolloutSummary(service.createRollout(new CreateRollout(releaseId,request.targetStoreIds(),
            request.canaryPercent(),key))));
    }

    @PostMapping("/rollouts/{rolloutId}/{action:start-canary|expand|pause|complete}")
    @SaCheckPermission("release:rollout")
    @Log(title="变更发布灰度状态",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public R<RolloutSummary> rolloutAction(@PathVariable @Pattern(regexp=ULID) String rolloutId,
                                           @PathVariable String action,
                                           @RequestHeader("X-Idempotency-Key") @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key) {
        Rollout value = switch (action) {
            case "start-canary" -> service.startCanary(rolloutId,key);
            case "expand" -> service.expand(rolloutId,key);
            case "pause" -> service.pause(rolloutId,key);
            case "complete" -> service.complete(rolloutId,key);
            default -> throw new IllegalArgumentException("unsupported rollout action");
        };
        return R.ok(rolloutSummary(value));
    }

    @PostMapping("/rollouts/{rolloutId}/tasks")
    @SaCheckPermission("release:rollout")
    public R<TaskSummaryView> assign(@PathVariable @Pattern(regexp=ULID) String rolloutId,
                                     @RequestHeader("X-Idempotency-Key") @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key,
                                     @Valid @RequestBody ReleaseRequests.Assign request) {
        return R.ok(taskSummary(service.assign(new AssignTerminal(rolloutId,request.deviceId(),key))));
    }

    @PostMapping("/tasks/{taskId}/observations")
    @SaCheckPermission("release:task:observe")
    @Log(title="记录发布软件执行证据",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public R<TaskSummaryView> observe(@PathVariable @Pattern(regexp=ULID) String taskId,
                                      @RequestHeader("X-Idempotency-Key") @Pattern(regexp=ReleaseRequests.IDEMPOTENCY) String key,
                                      @Valid @RequestBody ReleaseRequests.Observe request) {
        return R.ok(taskSummary(service.observe(new RecordObservation(taskId,request.type(),request.artifactSha256(),
            request.evidenceSha256(),key))));
    }

    @GetMapping("/{releaseId}") @SaCheckPermission("release:read")
    public R<ReleaseSummary> get(@PathVariable @Pattern(regexp=ULID) String releaseId) {
        return R.ok(summary(service.getRelease(releaseId)));
    }
    @GetMapping("/rollouts/{rolloutId}") @SaCheckPermission("release:read")
    public R<RolloutSummary> getRollout(@PathVariable @Pattern(regexp=ULID) String rolloutId) {
        return R.ok(rolloutSummary(service.getRollout(rolloutId)));
    }

    /** 去除签名和对象键的发布摘要。 */
    public record ReleaseSummary(String releaseId,String artifactType,String version,String channel,String state,
                                 String manifestSha256,String buildCommit,String sbomSha256,int targetStoreCount) { }
    /** 灰度摘要。 */
    public record RolloutSummary(String rolloutId,String releaseId,String state,int canaryPercent,int targetStoreCount) { }
    /** 软件任务摘要；不包含终端凭据。 */
    public record TaskSummaryView(String taskId,String rolloutId,String releaseId,String deviceId,Long storeId,
                                  String state,String evidenceSha256) { }
    private static ReleaseSummary summary(Release item) { return new ReleaseSummary(item.releaseId(),item.artifactType().name(),
        item.version(),item.channel().name(),item.state().name(),item.manifestSha256(),item.buildCommit(),item.sbomSha256(),item.targetStoreIds().size()); }
    private static RolloutSummary rolloutSummary(Rollout item) { return new RolloutSummary(item.rolloutId(),item.releaseId(),
        item.state().name(),item.canaryPercent(),item.targetStoreIds().size()); }
    private static TaskSummaryView taskSummary(TerminalTask item) { return new TaskSummaryView(item.taskId(),item.rolloutId(),
        item.releaseId(),item.deviceId(),item.storeId(),item.state().name(),item.lastEvidenceSha256()); }
}
