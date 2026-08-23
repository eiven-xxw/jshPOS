package com.jingshanghui.pos.subscription.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionRulesTest {
    @Test void validatesTermZoneReferencesKeysAndHashes() {
        SubscriptionRules.term(Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2027-02-01T00:00:00Z"));
        SubscriptionRules.effectiveAt(Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2026-08-23T00:00:00Z"));
        SubscriptionRules.renewalWindow(Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2028-01-01T00:00:00Z"));
        assertEquals("Asia/Shanghai",SubscriptionRules.zone("Asia/Shanghai"));
        assertEquals("CONTRACT-1",SubscriptionRules.reference("CONTRACT-1","contractRef"));
        assertEquals("idem-key-001",SubscriptionRules.key("idem-key-001"));
        String hash="a".repeat(64);assertEquals(hash,SubscriptionRules.hash(hash.toUpperCase()));
        assertEquals("RECOVERY-V1",SubscriptionRules.degradationPolicy(" recovery-v1 "));
        SubscriptionRules.sameHash(hash,hash);
    }

    @Test void rejectsInvalidInputs() {
        assertThrows(ServiceException.class,()->SubscriptionRules.term(null,Instant.now(),Instant.now()));
        assertThrows(ServiceException.class,()->SubscriptionRules.term(Instant.now(),null,Instant.now()));
        assertThrows(ServiceException.class,()->SubscriptionRules.term(Instant.now(),Instant.now(),null));
        assertThrows(ServiceException.class,()->SubscriptionRules.term(Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-02-01T00:00:00Z")));
        assertThrows(ServiceException.class,()->SubscriptionRules.term(Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2026-12-31T00:00:00Z")));
        assertThrows(ServiceException.class,()->SubscriptionRules.effectiveAt(null,Instant.now(),Instant.now()));
        assertThrows(ServiceException.class,()->SubscriptionRules.effectiveAt(Instant.now(),null,Instant.now()));
        assertThrows(ServiceException.class,()->SubscriptionRules.effectiveAt(Instant.now(),Instant.now(),null));
        assertThrows(ServiceException.class,()->SubscriptionRules.effectiveAt(Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2028-01-01T00:00:00Z"),Instant.parse("2026-01-01T00:00:00Z")));
        assertThrows(ServiceException.class,()->SubscriptionRules.effectiveAt(Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z")));
        assertThrows(ServiceException.class,()->SubscriptionRules.renewalWindow(null,Instant.now(),Instant.now()));
        assertThrows(ServiceException.class,()->SubscriptionRules.renewalWindow(Instant.now(),null,Instant.now()));
        assertThrows(ServiceException.class,()->SubscriptionRules.renewalWindow(Instant.now(),Instant.now(),null));
        assertThrows(ServiceException.class,()->SubscriptionRules.renewalWindow(Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2027-02-01T00:00:00Z"),Instant.parse("2028-01-01T00:00:00Z")));
        assertThrows(ServiceException.class,()->SubscriptionRules.renewalWindow(Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2027-01-01T00:00:00Z"),Instant.parse("2026-12-31T00:00:00Z")));
        assertThrows(ServiceException.class,()->SubscriptionRules.zone("Bad/Zone"));
        assertThrows(ServiceException.class,()->SubscriptionRules.reference("bad reference","ref"));
        assertThrows(ServiceException.class,()->SubscriptionRules.key("short"));
        assertThrows(ServiceException.class,()->SubscriptionRules.hash("bad"));
        assertThrows(ServiceException.class,()->SubscriptionRules.degradationPolicy("RECOVERY-V2"));
        assertThrows(ServiceException.class,()->SubscriptionRules.required(" ","value"));
        assertThrows(ServiceException.class,()->SubscriptionRules.sameHash("a".repeat(64),"b".repeat(64)));
    }

    @Test void mapsOnlyEffectiveStatesToAccessModes() {
        assertEquals("NORMAL",SubscriptionRules.accessModeFor("ACTIVE"));
        assertEquals("GRACE",SubscriptionRules.accessModeFor("GRACE_PERIOD"));
        assertEquals("RECOVERY_ONLY",SubscriptionRules.accessModeFor("SUSPENDED"));
        assertEquals("RECOVERY_ONLY",SubscriptionRules.accessModeFor("EXPIRED"));
        assertEquals("TERMINATED_RECOVERY",SubscriptionRules.accessModeFor("TERMINATED"));
        assertThrows(ServiceException.class,()->SubscriptionRules.accessModeFor("DRAFT"));
    }
}
