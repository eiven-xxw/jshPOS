package com.jingshanghui.pos.migration.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.migration.application.model.MigrationModels.*;
import com.jingshanghui.pos.migration.application.service.BusinessMigrationService;
import com.jingshanghui.pos.migration.domain.MigrationRules;
import com.jingshanghui.pos.migration.interfaces.rest.dto.BusinessMigrationRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 开业资料迁移 REST 边界；原文件只在上传请求内检查，禁止写日志或普通制品。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/business-migrations")
public class BusinessMigrationController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final BusinessMigrationService service;

    @PostMapping
    @SaCheckPermission("migration:upload")
    @Log(title="创建开业资料迁移",businessType=BusinessType.INSERT)
    public R<BatchView> create(@Valid @RequestBody BusinessMigrationRequests.Create request) {
        return R.ok(service.create(new CreateBatch(request.dataTypes(), request.idempotencyKey(),
            request.correlationId())));
    }

    /** 不使用通用操作日志记录 MultipartFile，避免原始文件或 PII 被参数序列化。 */
    @PostMapping("/{batchId}/files")
    @SaCheckPermission("migration:upload")
    public R<UploadResult> upload(@PathVariable @Pattern(regexp=ULID) String batchId,
                                  @RequestPart("metadata") @Valid BusinessMigrationRequests.UploadMetadata metadata,
                                  @RequestPart("file") MultipartFile file) {
        // Multipart 容器限制只是第一道门禁；业务边界必须在分配原文件字节前独立失败关闭。
        if (file.getSize() > MigrationRules.MAX_FILE_BYTES) {
            throw new ServiceException("DMT-FILE-001: 文件超过 64 MiB", 400);
        }
        try {
            return R.ok(service.upload(new UploadFile(batchId, metadata.dataType(), metadata.mappingVersion(),
                file.getOriginalFilename(), metadata.charset(), metadata.sourceSystem(), metadata.custodyReference(),
                metadata.declaredSha256(), file.getBytes(), metadata.correlationId())));
        } catch (IOException exception) {
            throw new ServiceException("DMT-FILE-019: 上传文件读取失败", 400);
        }
    }

    @GetMapping("/{batchId}")
    @SaCheckPermission("migration:read")
    public R<BatchDetail> detail(@PathVariable @Pattern(regexp=ULID) String batchId) {
        return R.ok(service.detail(batchId));
    }

    @GetMapping("/{batchId}/errors")
    @SaCheckPermission("migration:read")
    public R<PreflightErrorPage> errors(@PathVariable @Pattern(regexp=ULID) String batchId,
                                        @RequestParam(defaultValue="1") int page,
                                        @RequestParam(defaultValue="200") int pageSize) {
        return R.ok(service.errors(batchId, page, pageSize));
    }

    @PostMapping("/{batchId}/approvals")
    @SaCheckPermission("migration:approve")
    @Log(title="审批开业资料迁移",businessType=BusinessType.UPDATE)
    public R<BatchDetail> approve(@PathVariable @Pattern(regexp=ULID) String batchId,
                                  @Valid @RequestBody BusinessMigrationRequests.Action request) {
        return R.ok(service.approve(command(batchId,request)));
    }

    @PostMapping("/{batchId}/resume")
    @SaCheckPermission("migration:execute")
    @Log(title="推进开业资料迁移",businessType=BusinessType.UPDATE)
    public R<BatchDetail> resume(@PathVariable @Pattern(regexp=ULID) String batchId,
                                 @Valid @RequestBody BusinessMigrationRequests.Run request) {
        return R.ok(service.resume(command(batchId,request)));
    }

    @PostMapping("/{batchId}/reconcile")
    @SaCheckPermission("migration:activate")
    @Log(title="对账开业资料迁移",businessType=BusinessType.UPDATE)
    public R<ReconciliationResult> reconcile(@PathVariable @Pattern(regexp=ULID) String batchId,
                                              @Valid @RequestBody BusinessMigrationRequests.Run request) {
        return R.ok(service.reconcile(command(batchId,request)));
    }

    @PostMapping("/{batchId}/activate")
    @SaCheckPermission("migration:activate")
    @Log(title="激活开业资料迁移",businessType=BusinessType.UPDATE)
    public R<BatchDetail> activate(@PathVariable @Pattern(regexp=ULID) String batchId,
                                   @Valid @RequestBody BusinessMigrationRequests.Run request) {
        return R.ok(service.activate(command(batchId,request)));
    }

    @PostMapping("/{batchId}/cleanup")
    @SaCheckPermission("migration:activate")
    @Log(title="清理迁移加密暂存",businessType=BusinessType.CLEAN)
    public R<BatchDetail> cleanup(@PathVariable @Pattern(regexp=ULID) String batchId,
                                  @Valid @RequestBody BusinessMigrationRequests.Run request) {
        return R.ok(service.cleanup(command(batchId,request)));
    }

    private BatchCommand command(String batchId, BusinessMigrationRequests.Action value) {
        return new BatchCommand(batchId,value.idempotencyKey(),value.reason(),value.correlationId());
    }
    private BatchCommand command(String batchId, BusinessMigrationRequests.Run value) {
        return new BatchCommand(batchId,value.idempotencyKey(),value.reason(),value.correlationId());
    }
}
