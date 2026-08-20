package com.jingshanghui.pos.release.domain;

import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** T2-UPG-001 状态、摘要、兼容和营业保护固定规则测试。 */
class ReleaseRulesTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final CompatibilityWindow WINDOW = new CompatibilityWindow("1.0.0","2.0.0","1.0","2.0",
        "1.0","3.0","10.0","14.0",SHA_B);

    @Test void createsDeterministicTenantScopedManifestAndRejectsMutationInputs() {
        CreateRelease first = command(Set.of(102L,101L));
        CreateRelease second = command(Set.of(101L,102L));
        ReleaseRules.validateCreate(first,"TENANT_A");
        assertThat(ReleaseRules.manifestSha(first,"TENANT_A")).isEqualTo(ReleaseRules.manifestSha(second,"TENANT_A"));
        assertThatThrownBy(() -> ReleaseRules.validateCreate(new CreateRelease(ArtifactType.APK,"1.2.3",Channel.CANARY,
            "releases/TENANT_B/a.apk",SHA_A,"x".repeat(64),"synthetic-v1","c".repeat(40),SHA_B,WINDOW,
            Set.of(101L),"release:create:0001"),"TENANT_A")).hasMessageContaining("命名空间");
        assertThatThrownBy(() -> ReleaseRules.validateRollout(new CreateRollout("01K6A000000000000000000001",
            Set.of(999L),10,"rollout:create:0001"), release())).hasMessageContaining("越过发布范围");
    }

    @Test void validatesArtifactIdentityAndKeyVersion() {
        ReleaseRules.requireArtifact(release(),new ArtifactObservation(SHA_A,true,"synthetic-v1",123));
        assertThatThrownBy(() -> ReleaseRules.requireArtifact(release(),new ArtifactObservation(SHA_B,true,"synthetic-v1",123)))
            .hasMessageContaining("摘要");
        assertThatThrownBy(() -> ReleaseRules.requireArtifact(release(),new ArtifactObservation(SHA_A,false,"synthetic-v1",123)))
            .hasMessageContaining("签名");
        assertThatThrownBy(() -> ReleaseRules.requireArtifact(release(),new ArtifactObservation(SHA_A,true,"other",123)))
            .hasMessageContaining("密钥版本");
    }

    @Test void blocksUntrustedTerminalPendingFactsAndBusinessHours() {
        Rollout rollout = rollout(RolloutState.CANARY);
        TrustedTerminal terminal = terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B);
        ReleaseRules.requireTerminalReady(release(),rollout,terminal,new SafetySnapshot(0,0,0,false,false,true,true));
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_B",101L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B),safe())).hasMessageContaining("租户");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",101L,"REVOKED","1.2","1.1","2.0","11.0",SHA_B),safe())).hasMessageContaining("吊销");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,terminal,
            new SafetySnapshot(1,0,0,false,false,true,true))).hasMessageContaining("Outbox");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,terminal,
            new SafetySnapshot(0,1,0,false,false,true,true))).hasMessageContaining("UNKNOWN");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,terminal,
            new SafetySnapshot(0,0,0,true,false,true,true))).hasMessageContaining("营业");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,terminal,
            new SafetySnapshot(0,0,0,false,false,false,true))).hasMessageContaining("存储");
    }

    @Test void enforcesReleaseRolloutAndTaskStateMachines() {
        assertThat(ReleaseRules.releaseTransition(ReleaseState.DRAFT,ReleaseState.SIGNED)).isEqualTo(ReleaseState.SIGNED);
        assertThat(ReleaseRules.releaseTransition(ReleaseState.SIGNED,ReleaseState.STAGED)).isEqualTo(ReleaseState.STAGED);
        assertThat(ReleaseRules.releaseTransition(ReleaseState.STAGED,ReleaseState.REVOKED)).isEqualTo(ReleaseState.REVOKED);
        assertThatThrownBy(() -> ReleaseRules.releaseTransition(ReleaseState.DRAFT,ReleaseState.STAGED)).hasMessageContaining("非法");
        assertThat(ReleaseRules.rolloutTransition(RolloutState.PLANNED,RolloutState.CANARY,new TaskSummary(0,0,0)))
            .isEqualTo(RolloutState.CANARY);
        assertThat(ReleaseRules.rolloutTransition(RolloutState.CANARY,RolloutState.ROLLING,new TaskSummary(1,0,0)))
            .isEqualTo(RolloutState.ROLLING);
        assertThatThrownBy(() -> ReleaseRules.rolloutTransition(RolloutState.CANARY,RolloutState.ROLLING,
            new TaskSummary(0,1,0))).hasMessageContaining("健康门禁");

        TaskState state = ReleaseRules.taskTransition(ArtifactType.APK,TaskState.PLANNED,ObservationType.DOWNLOAD_STARTED,SHA_A,SHA_A);
        state = ReleaseRules.taskTransition(ArtifactType.APK,state,ObservationType.ARTIFACT_VERIFIED,SHA_A,SHA_A);
        state = ReleaseRules.taskTransition(ArtifactType.APK,state,ObservationType.INSTALL_STARTED,SHA_A,SHA_A);
        state = ReleaseRules.taskTransition(ArtifactType.APK,state,ObservationType.INSTALL_SUCCEEDED,SHA_A,SHA_A);
        assertThat(ReleaseRules.taskTransition(ArtifactType.APK,state,ObservationType.HEALTH_PASSED,SHA_A,SHA_A))
            .isEqualTo(TaskState.SUCCEEDED);
        assertThat(ReleaseRules.taskTransition(ArtifactType.APK,TaskState.HEALTH_CHECK,ObservationType.HEALTH_FAILED,SHA_A,SHA_A))
            .isEqualTo(TaskState.ROLLED_BACK);
        assertThat(ReleaseRules.taskTransition(ArtifactType.MYSQL_SCHEMA,TaskState.HEALTH_CHECK,ObservationType.HEALTH_FAILED,SHA_A,SHA_A))
            .isEqualTo(TaskState.FORWARD_FIX_REQUIRED);
        assertThat(ReleaseRules.taskTransition(ArtifactType.APK,TaskState.DOWNLOADING,ObservationType.ARTIFACT_VERIFIED,SHA_A,SHA_B))
            .isEqualTo(TaskState.FAILED_CLOSED);
        assertThatThrownBy(() -> ReleaseRules.taskTransition(ArtifactType.APK,TaskState.PLANNED,
            ObservationType.INSTALL_STARTED,SHA_A,SHA_A)).hasMessageContaining("非法任务");
    }

    @Test void rejectsInvalidCompatibilityDigestIdsAndCanaryPercent() {
        CompatibilityWindow bad = new CompatibilityWindow("2.0","1.0","1","2","1","2","10","14","");
        CreateRelease invalid = new CreateRelease(ArtifactType.APK,"1.2.3",Channel.CANARY,
            "releases/TENANT_A/a.apk",SHA_A,"x".repeat(64),"synthetic-v1","c".repeat(40),SHA_B,bad,
            Set.of(101L),"release:create:0001");
        assertThatThrownBy(() -> ReleaseRules.validateCreate(invalid,"TENANT_A")).hasMessageContaining("倒置");
        assertThatThrownBy(() -> ReleaseRules.sha("bad","测试")).hasMessageContaining("摘要");
        assertThatThrownBy(() -> ReleaseRules.ulid("bad","测试")).hasMessageContaining("ULID");
        assertThatThrownBy(() -> ReleaseRules.idempotency("short")).hasMessageContaining("幂等");
        assertThatThrownBy(() -> ReleaseRules.validateRollout(new CreateRollout(release().releaseId(),Set.of(101L),26,
            "rollout:create:0001"),release())).hasMessageContaining("1至25");
    }

    @Test void rejectsEveryMalformedReleaseIdentityAndScopeBoundary() {
        assertThatThrownBy(() -> ReleaseRules.validateCreate(null,"TENANT_A")).hasMessageContaining("类型");
        assertInvalid(with(null,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"类型");
        assertInvalid(with(ArtifactType.APK,null,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"类型");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk",null,"synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"签名");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","short","synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"签名");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(1025),"synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"签名");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),null,
            "c".repeat(40),Set.of(101L),WINDOW),"密钥");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"k".repeat(65),
            "c".repeat(40),Set.of(101L),WINDOW),"密钥");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            null,Set.of(101L),WINDOW),"构建");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "bad",Set.of(101L),WINDOW),"构建");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,null,"x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"命名空间");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/../app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"命名空间");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/"+"x".repeat(500),"x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),WINDOW),"命名空间");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),null,WINDOW),"门店");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(),WINDOW),"门店");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(-1L),WINDOW),"门店");
    }

    @Test void rejectsAllTerminalCompatibilityAndSafetyFailureModes() {
        Rollout rollout=rollout(RolloutState.CANARY);
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,null,safe())).hasMessageContaining("租户");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",null,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B),safe())).hasMessageContaining("门店");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",102L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B),safe())).hasMessageContaining("门店");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",101L,"ACTIVE","2.1","1.1","2.0","11.0",SHA_B),safe())).hasMessageContaining("应用版本");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","4.0","11.0",SHA_B),safe())).hasMessageContaining("Schema版本");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",101L,"ACTIVE","1.2","3.0","2.0","11.0",SHA_B),safe())).hasMessageContaining("协议版本");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","2.0","15.0",SHA_B),safe())).hasMessageContaining("系统版本");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_A),safe())).hasMessageContaining("能力");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,
            terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B),null)).hasMessageContaining("快照");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B),
            new SafetySnapshot(0,0,1,false,false,true,true))).hasMessageContaining("UNKNOWN");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B),
            new SafetySnapshot(0,0,0,false,true,true,true))).hasMessageContaining("营业");
        assertThatThrownBy(() -> ReleaseRules.requireTerminalReady(release(),rollout,terminal("TENANT_A",101L,"ACTIVE","1.2","1.1","2.0","11.0",SHA_B),
            new SafetySnapshot(0,0,0,false,false,true,false))).hasMessageContaining("时钟");
    }

    @Test void coversPauseResumeFailureForwardFixAndRetryTransitions() {
        assertThat(ReleaseRules.releaseTransition(ReleaseState.SIGNED,ReleaseState.REVOKED)).isEqualTo(ReleaseState.REVOKED);
        assertThat(ReleaseRules.rolloutTransition(RolloutState.CANARY,RolloutState.PAUSED,new TaskSummary(0,1,0))).isEqualTo(RolloutState.PAUSED);
        assertThat(ReleaseRules.rolloutTransition(RolloutState.PAUSED,RolloutState.CANARY,new TaskSummary(0,0,0))).isEqualTo(RolloutState.CANARY);
        assertThat(ReleaseRules.rolloutTransition(RolloutState.PAUSED,RolloutState.ROLLING,new TaskSummary(1,0,0))).isEqualTo(RolloutState.ROLLING);
        assertThat(ReleaseRules.rolloutTransition(RolloutState.ROLLING,RolloutState.COMPLETED,new TaskSummary(2,0,0))).isEqualTo(RolloutState.COMPLETED);
        assertThat(ReleaseRules.rolloutTransition(RolloutState.ROLLING,RolloutState.FAILED,new TaskSummary(0,0,1))).isEqualTo(RolloutState.FAILED);
        assertThatThrownBy(() -> ReleaseRules.rolloutTransition(RolloutState.ROLLING,RolloutState.COMPLETED,null)).hasMessageContaining("健康门禁");
        assertThat(ReleaseRules.taskTransition(ArtifactType.APK,TaskState.DOWNLOADING,ObservationType.DOWNLOAD_RESUMED,SHA_A,SHA_A)).isEqualTo(TaskState.DOWNLOADING);
        assertThat(ReleaseRules.taskTransition(ArtifactType.APK,TaskState.INSTALLING,ObservationType.MIGRATION_FAILED,SHA_A,SHA_A)).isEqualTo(TaskState.FORWARD_FIX_REQUIRED);
        assertThat(ReleaseRules.taskTransition(ArtifactType.SQLITE_SCHEMA,TaskState.HEALTH_CHECK,ObservationType.FORWARD_FIX_REQUIRED,SHA_A,SHA_A)).isEqualTo(TaskState.FORWARD_FIX_REQUIRED);
        assertThat(ReleaseRules.taskTransition(ArtifactType.APK,TaskState.ROLLED_BACK,ObservationType.ROLLBACK_SUCCEEDED,SHA_A,SHA_A)).isEqualTo(TaskState.ROLLED_BACK);
        assertThat(ReleaseRules.taskTransition(ArtifactType.APK,TaskState.PLANNED,ObservationType.SIGNATURE_INVALID,SHA_A,SHA_A)).isEqualTo(TaskState.FAILED_CLOSED);
    }

    @Test void rejectsNullArtifactRolloutAndCompatibilityVariants() {
        assertThatThrownBy(() -> ReleaseRules.requireArtifact(release(),null)).hasMessageContaining("不可用");
        assertThatThrownBy(() -> ReleaseRules.requireArtifact(release(),new ArtifactObservation(SHA_A,true,"synthetic-v1",0))).hasMessageContaining("不可用");
        assertThatThrownBy(() -> ReleaseRules.validateRollout(null,release())).hasMessageContaining("范围");
        assertThatThrownBy(() -> ReleaseRules.validateRollout(new CreateRollout(release().releaseId(),null,10,"rollout:create:0001"),release())).hasMessageContaining("范围");
        assertThatThrownBy(() -> ReleaseRules.validateRollout(new CreateRollout(release().releaseId(),Set.of(),10,"rollout:create:0001"),release())).hasMessageContaining("范围");
        assertThatThrownBy(() -> ReleaseRules.validateRollout(new CreateRollout(release().releaseId(),Set.of(101L),0,"rollout:create:0001"),release())).hasMessageContaining("1至25");
        CreateRelease nullCompatibility=with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),null);
        assertInvalid(nullCompatibility,"兼容窗口");
        CompatibilityWindow malformed=new CompatibilityWindow("bad version","2","1","2","1","2","10","14",null);
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),malformed),"格式");
        CompatibilityWindow badCapability=new CompatibilityWindow("1","2","1","2","1","2","10","14","bad");
        assertInvalid(with(ArtifactType.APK,Channel.CANARY,"releases/TENANT_A/app.apk","x".repeat(64),"synthetic-v1",
            "c".repeat(40),Set.of(101L),badCapability),"摘要");
        assertThat(ReleaseRules.requestSha("a",null,1)).hasSize(64);
        assertThatThrownBy(() -> ReleaseRules.ulid(null,"测试")).hasMessageContaining("ULID");
        assertThatThrownBy(() -> ReleaseRules.idempotency(null)).hasMessageContaining("幂等");
        assertThatThrownBy(() -> ReleaseRules.sha(null,"测试")).hasMessageContaining("摘要");
    }

    private static CreateRelease command(Set<Long> stores) { return new CreateRelease(ArtifactType.APK,"1.2.3",Channel.CANARY,
        "releases/TENANT_A/app.apk",SHA_A,"x".repeat(64),"synthetic-v1","c".repeat(40),SHA_B,WINDOW,stores,
        "release:create:0001"); }
    private static Release release() { return new Release("01K6A000000000000000000001","TENANT_A",ArtifactType.APK,"1.2.3",
        Channel.CANARY,"releases/TENANT_A/app.apk",SHA_A,"x".repeat(64),"synthetic-v1","c".repeat(40),SHA_B,
        SHA_A,WINDOW,Set.of(101L,102L),ReleaseState.STAGED,2,Instant.EPOCH); }
    private static Rollout rollout(RolloutState state) { return new Rollout("01K6A000000000000000000002","TENANT_A",
        release().releaseId(),Set.of(101L),10,state,1,Instant.EPOCH); }
    private static TrustedTerminal terminal(String tenant,Long store,String status,String app,String protocol,String schema,
                                             String system,String capability) {
        return new TrustedTerminal(tenant,"01K6A000000000000000000003",store,status,app,protocol,protocol,schema,system,capability);
    }
    private static SafetySnapshot safe() { return new SafetySnapshot(0,0,0,false,false,true,true); }
    private static CreateRelease with(ArtifactType type,Channel channel,String objectKey,String signature,String keyVersion,
                                      String commit,Set<Long> stores,CompatibilityWindow compatibility) {
        return new CreateRelease(type,"1.2.3",channel,objectKey,SHA_A,signature,keyVersion,commit,SHA_B,compatibility,
            stores,"release:create:0001");
    }
    private static void assertInvalid(CreateRelease value,String message) {
        assertThatThrownBy(() -> ReleaseRules.validateCreate(value,"TENANT_A")).hasMessageContaining(message);
    }
}
