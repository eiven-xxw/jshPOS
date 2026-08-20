package com.jingshanghui.pos.release.application.service;

import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.release.application.port.ReleasePorts.*;
import com.jingshanghui.pos.release.domain.ReleaseIdGenerator;
import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 发布、灰度、终端软件任务和幂等的完整内部合成闭环。 */
class ReleaseGovernanceServiceTest {
    private static final String DEVICE = "01K6A000000000000000000003";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneOffset.UTC);
    private TrustedTenantContext context;
    private MemoryRepository repository;

    @BeforeEach void prepare() {
        context = mock(TrustedTenantContext.class);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A",101L,10L,"synthetic-admin"));
        when(context.requireTenantId()).thenReturn("TENANT_A");
        repository = new MemoryRepository();
    }

    @Test void closesFullSyntheticReleaseRolloutAndTaskLifecycle() {
        ReleaseGovernanceService service = service(new SafetySnapshot(0,0,0,false,false,true,true));
        Release draft = service.create(create("release:create:0001"));
        assertThat(service.create(create("release:create:0001")).releaseId()).isEqualTo(draft.releaseId());
        Release signed = service.verifyAndSign(new ReleaseCommand(draft.releaseId(),"release:verify:0001"));
        assertThat(signed.state()).isEqualTo(ReleaseState.SIGNED);
        assertThat(service.verifyAndSign(new ReleaseCommand(draft.releaseId(),"release:verify:0001")).state())
            .isEqualTo(ReleaseState.SIGNED);
        Release staged = service.stage(new ReleaseCommand(draft.releaseId(),"release:stage:0001"));
        assertThat(staged.state()).isEqualTo(ReleaseState.STAGED);
        Rollout rollout = service.createRollout(new CreateRollout(staged.releaseId(),Set.of(101L),10,"rollout:create:0001"));
        rollout = service.startCanary(rollout.rolloutId(),"rollout:start:0001");
        assertThat(service.startCanary(rollout.rolloutId(),"rollout:start:0001").state())
            .isEqualTo(RolloutState.CANARY);
        TerminalTask task = service.assign(new AssignTerminal(rollout.rolloutId(),DEVICE,"task:assign:0001"));
        task = observe(service,task,ObservationType.DOWNLOAD_STARTED,"task:observe:0001");
        assertThat(observe(service,task,ObservationType.DOWNLOAD_STARTED,"task:observe:0001").state())
            .isEqualTo(TaskState.DOWNLOADING);
        task = observe(service,task,ObservationType.ARTIFACT_VERIFIED,"task:observe:0002");
        task = observe(service,task,ObservationType.INSTALL_STARTED,"task:observe:0003");
        task = observe(service,task,ObservationType.INSTALL_SUCCEEDED,"task:observe:0004");
        task = observe(service,task,ObservationType.HEALTH_PASSED,"task:observe:0005");
        assertThat(task.state()).isEqualTo(TaskState.SUCCEEDED);
        rollout = service.expand(rollout.rolloutId(),"rollout:expand:0001");
        assertThat(rollout.state()).isEqualTo(RolloutState.ROLLING);
        Rollout completed = service.complete(rollout.rolloutId(),"rollout:complete:0001");
        assertThat(completed.state()).isEqualTo(RolloutState.COMPLETED);
        assertThat(service.complete(rollout.rolloutId(),"rollout:complete:0001").state())
            .isEqualTo(RolloutState.COMPLETED);
        assertThat(repository.events).isGreaterThanOrEqualTo(12);
        assertThat(repository.audits).isEqualTo(repository.events);
    }

    @Test void failsClosedForPendingOutboxDigestMismatchAndIdempotencyConflict() {
        ReleaseGovernanceService blocked = service(new SafetySnapshot(1,0,0,false,false,true,true));
        Release draft = blocked.create(create("release:create:0010"));
        blocked.verifyAndSign(new ReleaseCommand(draft.releaseId(),"release:verify:0010"));
        blocked.stage(new ReleaseCommand(draft.releaseId(),"release:stage:0010"));
        Rollout rollout = blocked.createRollout(new CreateRollout(draft.releaseId(),Set.of(101L),10,"rollout:create:0010"));
        blocked.startCanary(rollout.rolloutId(),"rollout:start:0010");
        assertThatThrownBy(() -> blocked.assign(new AssignTerminal(rollout.rolloutId(),DEVICE,"task:assign:0010")))
            .hasMessageContaining("Outbox");
        assertThatThrownBy(() -> blocked.create(new CreateRelease(ArtifactType.APK,"1.9.9",Channel.CANARY,
            "releases/TENANT_A/app.apk",SHA_A,"x".repeat(64),"synthetic-v1","c".repeat(40),SHA_B,
            window(),Set.of(101L),"release:create:0010"))).hasMessageContaining("同幂等键异内容");

        ReleaseGovernanceService safe = service(new SafetySnapshot(0,0,0,false,false,true,true));
        TerminalTask task = safe.assign(new AssignTerminal(rollout.rolloutId(),DEVICE,"task:assign:0011"));
        TerminalTask failed = safe.observe(new RecordObservation(task.taskId(),ObservationType.DIGEST_MISMATCH,SHA_B,
            SHA_B,"task:observe:0010"));
        assertThat(failed.state()).isEqualTo(TaskState.FAILED_CLOSED);
        assertThatThrownBy(() -> safe.expand(rollout.rolloutId(),"rollout:expand:0010")).hasMessageContaining("健康门禁");
    }

    private ReleaseGovernanceService service(SafetySnapshot safety) {
        ArtifactVerifier verifier = release -> new ArtifactObservation(release.artifactSha256(),true,release.keyVersion(),1024);
        TrustedTerminalRegistry terminals = (tenant,device) -> new TrustedTerminal(tenant,device,101L,"ACTIVE",
            "1.2","1.1","1.1","2.0","11.0",SHA_B);
        return new ReleaseGovernanceService(context, stores -> { if (!stores.equals(Set.of(101L))) throw new AssertionError(); },
            repository,verifier,terminals,(terminal,release) -> safety,new ReleaseIdGenerator(clock),clock);
    }
    private static CreateRelease create(String key) { return new CreateRelease(ArtifactType.APK,"1.2.3",Channel.CANARY,
        "releases/TENANT_A/app.apk",SHA_A,"x".repeat(64),"synthetic-v1","c".repeat(40),SHA_B,window(),Set.of(101L),key); }
    private static CompatibilityWindow window() { return new CompatibilityWindow("1.0","2.0","1.0","2.0",
        "1.0","3.0","10.0","14.0",SHA_B); }
    private static TerminalTask observe(ReleaseGovernanceService service, TerminalTask task, ObservationType type, String key) {
        return service.observe(new RecordObservation(task.taskId(),type,SHA_A,SHA_B,key));
    }

    /** 仅用于单元测试的事务内存端口；不作为生产适配器或外部证据。 */
    private static final class MemoryRepository implements Repository {
        private final Map<String,Release> releases = new HashMap<>();
        private final Map<String,Rollout> rollouts = new HashMap<>();
        private final Map<String,TerminalTask> tasks = new HashMap<>();
        private final Map<String,CommandResult> commands = new HashMap<>();
        private int events; private int audits;
        @Override public Optional<Release> findRelease(String tenant,String id) { return Optional.ofNullable(releases.get(id)).filter(v -> v.tenantId().equals(tenant)); }
        @Override public Optional<Rollout> findRollout(String tenant,String id) { return Optional.ofNullable(rollouts.get(id)).filter(v -> v.tenantId().equals(tenant)); }
        @Override public Optional<TerminalTask> findTask(String tenant,String id) { return Optional.ofNullable(tasks.get(id)).filter(v -> v.tenantId().equals(tenant)); }
        @Override public Optional<CommandResult> findCommand(String tenant,String type,String key) { return Optional.ofNullable(commands.get(tenant+type+key)); }
        @Override public void insertRelease(Release value,String sha,long actor,String correlation) { releases.put(value.releaseId(),value); event(); }
        @Override public void transitionRelease(String tenant,String id,ReleaseState from,ReleaseState to,long version,long actor,String correlation,String evidence) {
            Release v=releases.get(id); releases.put(id,new Release(v.releaseId(),v.tenantId(),v.artifactType(),v.version(),v.channel(),v.objectKey(),v.artifactSha256(),v.signatureBase64(),v.keyVersion(),v.buildCommit(),v.sbomSha256(),v.manifestSha256(),v.compatibility(),v.targetStoreIds(),to,v.versionNo()+1,v.createdAt())); event(); }
        @Override public void insertRollout(Rollout value,String sha,long actor,String correlation) { rollouts.put(value.rolloutId(),value); event(); }
        @Override public void transitionRollout(String tenant,String id,RolloutState from,RolloutState to,long version,long actor,String correlation,String evidence) {
            Rollout v=rollouts.get(id); rollouts.put(id,new Rollout(v.rolloutId(),v.tenantId(),v.releaseId(),v.targetStoreIds(),v.canaryPercent(),to,v.versionNo()+1,v.createdAt())); event(); }
        @Override public void insertTask(TerminalTask value,String sha,long actor,String correlation) { tasks.put(value.taskId(),value); event(); }
        @Override public void transitionTask(String tenant,String id,TaskState from,TaskState to,long version,String evidence,long actor,String correlation) {
            TerminalTask v=tasks.get(id); tasks.put(id,new TerminalTask(v.taskId(),v.tenantId(),v.rolloutId(),v.releaseId(),v.deviceId(),v.storeId(),to,evidence,v.versionNo()+1,v.createdAt())); event(); }
        @Override public TaskSummary summarizeTasks(String tenant,String rollout) {
            long success=tasks.values().stream().filter(v->v.rolloutId().equals(rollout)&&v.state()==TaskState.SUCCEEDED).count();
            long active=tasks.values().stream().filter(v->v.rolloutId().equals(rollout)&&Set.of(TaskState.PLANNED,TaskState.DOWNLOADING,TaskState.VERIFIED,TaskState.INSTALLING,TaskState.HEALTH_CHECK).contains(v.state())).count();
            long failed=tasks.values().stream().filter(v->v.rolloutId().equals(rollout)&&Set.of(TaskState.ROLLED_BACK,TaskState.FORWARD_FIX_REQUIRED,TaskState.FAILED_CLOSED).contains(v.state())).count();
            return new TaskSummary(success,active,failed);
        }
        @Override public void appendCommand(String tenant,String type,String key,String sha,String aggregate,String result,long actor,Instant at) { commands.put(tenant+type+key,new CommandResult(sha,aggregate,result)); }
        private void event() { events++; audits++; }
    }
}
