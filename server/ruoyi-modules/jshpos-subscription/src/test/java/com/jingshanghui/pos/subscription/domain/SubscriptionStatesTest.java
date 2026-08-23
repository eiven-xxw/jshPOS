package com.jingshanghui.pos.subscription.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionStatesTest {
    @Test void acceptsCompleteCommercialLifecycle() {
        SubscriptionStates.requireTransition("DRAFT","PENDING_ACTIVATION");
        SubscriptionStates.requireTransition("PENDING_ACTIVATION","ACTIVE");
        SubscriptionStates.requireTransition("ACTIVE","ACTIVE");
        SubscriptionStates.requireTransition("ACTIVE","GRACE_PERIOD");
        SubscriptionStates.requireTransition("ACTIVE","EXPIRED");
        SubscriptionStates.requireTransition("ACTIVE","SUSPENDED");
        SubscriptionStates.requireTransition("ACTIVE","TERMINATION_PENDING");
        SubscriptionStates.requireTransition("GRACE_PERIOD","GRACE_PERIOD");
        SubscriptionStates.requireTransition("GRACE_PERIOD","EXPIRED");
        SubscriptionStates.requireTransition("GRACE_PERIOD","SUSPENDED");
        SubscriptionStates.requireTransition("GRACE_PERIOD","RESTORED");
        SubscriptionStates.requireTransition("SUSPENDED","RESTORED");
        SubscriptionStates.requireTransition("EXPIRED","RESTORED");
        SubscriptionStates.requireTransition("TERMINATION_PENDING","TERMINATED");
        SubscriptionStates.requireTransition("TERMINATED","RESTORED");
        SubscriptionStates.requireTransition("RESTORED","ACTIVE");
    }

    @Test void rejectsBackwardAndUnknownTransitions() {
        assertThrows(ServiceException.class,()->SubscriptionStates.requireTransition("ACTIVE","DRAFT"));
        assertThrows(ServiceException.class,()->SubscriptionStates.requireTransition("UNKNOWN","ACTIVE"));
        assertThrows(ServiceException.class,()->SubscriptionStates.requireTransition("TERMINATED","EXPIRED"));
        assertEquals(SubscriptionStates.State.ACTIVE,SubscriptionStates.parse("ACTIVE"));
    }
}
