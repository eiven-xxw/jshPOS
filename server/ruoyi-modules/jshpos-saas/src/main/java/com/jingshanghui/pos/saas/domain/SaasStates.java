package com.jingshanghui.pos.saas.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** SaaS 商户申请、权益版本和租户商业生命周期状态机。 */
public final class SaasStates {
    private SaasStates() { }

    public enum ApplicationState {
        DRAFT, PREFLIGHTING, PREFLIGHT_FAILED, READY, APPROVED, PROVISIONING,
        INITIALIZING, ACTIVE, FAILED, COMPENSATION_REQUIRED, CANCELLED
    }

    public enum EntitlementState {
        DRAFT, VALIDATING, VALIDATION_FAILED, READY, APPROVED, PUBLISHED, EFFECTIVE, SUSPENDED, RETIRED
    }

    public enum LifecycleState {
        PENDING_ACTIVATION, ACTIVE, SUSPENSION_PENDING, SUSPENDED, DEACTIVATION_PENDING,
        DEACTIVATED, RESTORING, TERMINATION_REQUESTED, RETENTION_HOLD, TERMINATED_LOGICAL
    }

    private static final Map<ApplicationState, Set<ApplicationState>> APPLICATION = new EnumMap<>(ApplicationState.class);
    private static final Map<EntitlementState, Set<EntitlementState>> ENTITLEMENT = new EnumMap<>(EntitlementState.class);
    private static final Map<LifecycleState, Set<LifecycleState>> LIFECYCLE = new EnumMap<>(LifecycleState.class);

    static {
        APPLICATION.put(ApplicationState.DRAFT, EnumSet.of(ApplicationState.PREFLIGHTING, ApplicationState.CANCELLED));
        APPLICATION.put(ApplicationState.PREFLIGHTING, EnumSet.of(ApplicationState.READY, ApplicationState.PREFLIGHT_FAILED));
        APPLICATION.put(ApplicationState.PREFLIGHT_FAILED, EnumSet.of(ApplicationState.PREFLIGHTING, ApplicationState.CANCELLED));
        APPLICATION.put(ApplicationState.READY, EnumSet.of(ApplicationState.APPROVED, ApplicationState.CANCELLED));
        APPLICATION.put(ApplicationState.APPROVED, EnumSet.of(ApplicationState.PROVISIONING));
        APPLICATION.put(ApplicationState.PROVISIONING, EnumSet.of(ApplicationState.INITIALIZING, ApplicationState.FAILED, ApplicationState.COMPENSATION_REQUIRED));
        APPLICATION.put(ApplicationState.INITIALIZING, EnumSet.of(ApplicationState.ACTIVE, ApplicationState.FAILED, ApplicationState.COMPENSATION_REQUIRED));
        APPLICATION.put(ApplicationState.FAILED, EnumSet.of(ApplicationState.PROVISIONING, ApplicationState.INITIALIZING, ApplicationState.COMPENSATION_REQUIRED));

        ENTITLEMENT.put(EntitlementState.DRAFT, EnumSet.of(EntitlementState.VALIDATING));
        ENTITLEMENT.put(EntitlementState.VALIDATING, EnumSet.of(EntitlementState.READY, EntitlementState.VALIDATION_FAILED));
        ENTITLEMENT.put(EntitlementState.VALIDATION_FAILED, EnumSet.of(EntitlementState.VALIDATING));
        ENTITLEMENT.put(EntitlementState.READY, EnumSet.of(EntitlementState.APPROVED));
        ENTITLEMENT.put(EntitlementState.APPROVED, EnumSet.of(EntitlementState.PUBLISHED));
        ENTITLEMENT.put(EntitlementState.PUBLISHED, EnumSet.of(EntitlementState.EFFECTIVE, EntitlementState.SUSPENDED));
        ENTITLEMENT.put(EntitlementState.EFFECTIVE, EnumSet.of(EntitlementState.SUSPENDED, EntitlementState.RETIRED));
        ENTITLEMENT.put(EntitlementState.SUSPENDED, EnumSet.of(EntitlementState.EFFECTIVE, EntitlementState.RETIRED));

        LIFECYCLE.put(LifecycleState.PENDING_ACTIVATION, EnumSet.of(LifecycleState.ACTIVE, LifecycleState.DEACTIVATION_PENDING));
        LIFECYCLE.put(LifecycleState.ACTIVE, EnumSet.of(LifecycleState.SUSPENSION_PENDING, LifecycleState.DEACTIVATION_PENDING, LifecycleState.TERMINATION_REQUESTED));
        LIFECYCLE.put(LifecycleState.SUSPENSION_PENDING, EnumSet.of(LifecycleState.SUSPENDED));
        LIFECYCLE.put(LifecycleState.SUSPENDED, EnumSet.of(LifecycleState.RESTORING, LifecycleState.DEACTIVATION_PENDING, LifecycleState.TERMINATION_REQUESTED));
        LIFECYCLE.put(LifecycleState.DEACTIVATION_PENDING, EnumSet.of(LifecycleState.DEACTIVATED));
        LIFECYCLE.put(LifecycleState.DEACTIVATED, EnumSet.of(LifecycleState.RESTORING, LifecycleState.TERMINATION_REQUESTED));
        LIFECYCLE.put(LifecycleState.RESTORING, EnumSet.of(LifecycleState.ACTIVE, LifecycleState.SUSPENDED));
        LIFECYCLE.put(LifecycleState.TERMINATION_REQUESTED, EnumSet.of(LifecycleState.RETENTION_HOLD, LifecycleState.TERMINATED_LOGICAL));
        LIFECYCLE.put(LifecycleState.RETENTION_HOLD, EnumSet.of(LifecycleState.TERMINATION_REQUESTED, LifecycleState.RESTORING));
    }

    public static void require(ApplicationState from, ApplicationState to) { require(APPLICATION, from, to, "SAA-STATE-001"); }
    public static void require(EntitlementState from, EntitlementState to) { require(ENTITLEMENT, from, to, "SAA-STATE-002"); }
    public static void require(LifecycleState from, LifecycleState to) { require(LIFECYCLE, from, to, "SAA-STATE-003"); }

    private static <T extends Enum<T>> void require(Map<T, Set<T>> transitions, T from, T to, String code) {
        if (!transitions.getOrDefault(from, Set.of()).contains(to)) {
            throw new ServiceException(code + ": 非法状态迁移 " + from + " -> " + to, 409);
        }
    }
}
