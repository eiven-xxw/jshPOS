package com.jingshanghui.pos.service.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.service.application.model.ServiceModels.*;
import com.jingshanghui.pos.service.application.service.ServiceApplicationService;
import com.jingshanghui.pos.service.interfaces.rest.dto.ServiceRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** 服务运营 HTTP 协议边界；不计算状态、租约、职责分离、对象键或授权结论。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/service")
public class ServiceOperationsController {
    private static final String ULID="^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SAFE="^[A-Za-z0-9._:-]+$";
    private final ServiceApplicationService service;

    @PostMapping("/catalogs") @SaCheckPermission("service:catalog:manage")
    @Log(title="创建服务目录",businessType=BusinessType.INSERT)
    public R<CatalogDetail> createCatalog(@Valid @RequestBody ServiceRequests.CreateCatalog request,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation){
        return R.ok(service.createCatalog(new CreateCatalog(request.catalogCode(),request.versionNo(),request.industryTemplate(),request.name(),
            request.items().stream().map(v->new CatalogItemInput(v.itemCode(),v.itemName(),v.mandatory(),v.sequenceNo())).toList(),key,correlation)));}

    @PostMapping("/catalogs/{catalogId}/publish") @SaCheckPermission("service:catalog:manage")
    @Log(title="发布服务目录",businessType=BusinessType.UPDATE)
    public R<CatalogDetail> publishCatalog(@PathVariable @Pattern(regexp=ULID) String catalogId,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation){return R.ok(service.publishCatalog(new CatalogCommand(catalogId,key,correlation)));}

    @GetMapping("/projects") @SaCheckPermission("service:project:read")
    public R<List<ProjectRecord>> listProjects(@RequestParam @Positive Long storeId,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return R.ok(service.listProjects(storeId,limit));}
    @GetMapping("/projects/{projectId}") @SaCheckPermission("service:project:read")
    public R<ProjectDetail> project(@PathVariable @Pattern(regexp=ULID) String projectId){return R.ok(service.project(projectId));}
    @PostMapping("/projects") @SaCheckPermission("service:project:create")
    @Log(title="创建实施项目",businessType=BusinessType.INSERT)
    public R<ProjectDetail> createProject(@Valid @RequestBody ServiceRequests.CreateProject request,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation){return R.ok(service.createProject(new CreateProject(request.storeId(),request.catalogId(),request.targetDate(),request.ownerUserId(),key,correlation)));}
    @PostMapping("/projects/{projectId}/commands") @SaCheckPermission("service:project:operate")
    @Log(title="执行实施项目命令",businessType=BusinessType.UPDATE)
    public R<ProjectDetail> commandProject(@PathVariable @Pattern(regexp=ULID) String projectId,
        @RequestHeader("If-Match-Version") @PositiveOrZero Integer expectedVersion,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody ServiceRequests.StateCommand request){return R.ok(service.commandProject(new ProjectCommand(projectId,request.command(),request.reason(),expectedVersion,key,correlation)));}
    @PostMapping("/projects/{projectId}/checks/{checkId}/complete") @SaCheckPermission("service:project:operate")
    @Log(title="完成实施检查项",businessType=BusinessType.UPDATE)
    public R<ProjectDetail> completeCheck(@PathVariable @Pattern(regexp=ULID) String projectId,
        @PathVariable @Pattern(regexp=ULID) String checkId,@RequestHeader("If-Match-Version") @PositiveOrZero Integer expectedVersion,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody ServiceRequests.Reason request){return R.ok(service.completeCheck(new CompleteCheck(projectId,checkId,request.reason(),expectedVersion,key,correlation)));}

    @GetMapping("/tickets") @SaCheckPermission("service:ticket:read")
    public R<List<TicketRecord>> listTickets(@RequestParam @Positive Long storeId,@RequestParam(required=false) String state,
        @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){return R.ok(service.listTickets(storeId,state,limit));}
    @GetMapping("/tickets/{ticketId}") @SaCheckPermission("service:ticket:read")
    public R<TicketDetail> ticket(@PathVariable @Pattern(regexp=ULID) String ticketId){return R.ok(service.ticket(ticketId));}
    @PostMapping("/tickets") @SaCheckPermission("service:ticket:create")
    @Log(title="创建服务工单",businessType=BusinessType.INSERT)
    public R<TicketDetail> createTicket(@Valid @RequestBody ServiceRequests.CreateTicket request,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation){return R.ok(service.createTicket(new CreateTicket(request.storeId(),request.projectId(),request.serviceType(),request.priority(),request.subject(),request.description(),request.internalTargetMinutes(),key,correlation)));}
    @PostMapping("/tickets/{ticketId}/commands") @SaCheckPermission("service:ticket:operate")
    @Log(title="执行服务工单命令",businessType=BusinessType.UPDATE)
    public R<TicketDetail> commandTicket(@PathVariable @Pattern(regexp=ULID) String ticketId,
        @RequestHeader("If-Match-Version") @PositiveOrZero Integer expectedVersion,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody ServiceRequests.TicketCommand request){return R.ok(service.commandTicket(new TicketCommand(ticketId,request.command(),request.assigneeUserId(),request.leaseMinutes(),request.reason(),request.resolutionSummary(),expectedVersion,key,correlation)));}

    @PostMapping("/tickets/{ticketId}/attachments") @SaCheckPermission("service:attachment:upload")
    @Log(title="上传服务工单附件",businessType=BusinessType.INSERT)
    public R<AttachmentRecord> upload(@PathVariable @Pattern(regexp=ULID) String ticketId,
        @RequestParam("file") MultipartFile file,@RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation){
        try{return R.ok(service.uploadAttachment(ticketId,file.getOriginalFilename(),file.getContentType(),file.getBytes(),key,correlation));}
        catch(IOException e){throw new ServiceException("SVC-ATT-003: 附件读取失败",503);}}
    @PostMapping("/tickets/{ticketId}/attachments/{attachmentId}/download") @SaCheckPermission("service:attachment:download")
    public R<AttachmentDownload> download(@PathVariable @Pattern(regexp=ULID) String ticketId,@PathVariable @Pattern(regexp=ULID) String attachmentId){return R.ok(service.issueDownload(ticketId,attachmentId));}
    @PostMapping("/tickets/{ticketId}/attachments/{attachmentId}/cleanup") @SaCheckPermission("service:attachment:cleanup")
    @Log(title="清理服务工单附件",businessType=BusinessType.DELETE)
    public R<AttachmentRecord> cleanup(@PathVariable @Pattern(regexp=ULID) String ticketId,@PathVariable @Pattern(regexp=ULID) String attachmentId,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation){return R.ok(service.cleanAttachment(ticketId,attachmentId,key,correlation));}
}
