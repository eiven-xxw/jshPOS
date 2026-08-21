package com.jingshanghui.pos.sync.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.Set;
import java.util.regex.Pattern;

public final class SyncRules {

    public static final int MAX_BATCH_EVENTS = 100;
    public static final int MAX_BATCH_BYTES = 2 * 1024 * 1024;
    public static final int MAX_EVENT_BYTES = 256 * 1024;
    public static final int MAX_PULL_EVENTS = 500;
    public static final int MAX_PROCESSING_ATTEMPTS = 12;

    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern STREAM = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9.-]{0,95}\\.v[1-9][0-9]*$");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> SUPPORTED_POS_FACTS = Set.of(
        "shift.opened.v1", "shift.difference-approved.v1", "shift.closed.v1",
        "shift.cash-movement.recorded.v1", "shift.drawer-requested.v1",
        "order.suspended.v1", "order.resumed.v1", "order.submitted.v1",
        "cash.received.v1", "order.completed.v1", "order.submitted.v2", "order.completed.v2"
    );

    private SyncRules() {
    }

    public static String requireUlid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new ServiceException("SYNC_INPUT_INVALID: " + field + " must be a canonical ULID", 400);
        }
        return value;
    }

    public static String requireStream(String value) {
        if (value == null || !STREAM.matcher(value).matches()) {
            throw new ServiceException("SYNC_INPUT_INVALID: invalid stream", 400);
        }
        return value;
    }

    public static String requireEventType(String value) {
        if (value == null || !EVENT_TYPE.matcher(value).matches()) {
            throw new ServiceException("SYNC_INPUT_INVALID: invalid eventType", 400);
        }
        return value;
    }

    public static String requireHash(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new ServiceException("SYNC_INPUT_INVALID: " + field + " must be lowercase SHA-256", 400);
        }
        return value;
    }

    public static void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new ServiceException("SYNC_INPUT_INVALID: " + field + " must be positive", 400);
        }
    }

    public static boolean supportsPosFact(String eventType) {
        return SUPPORTED_POS_FACTS.contains(eventType);
    }

    public static int clampPullLimit(int requested) {
        return Math.max(1, Math.min(requested, MAX_PULL_EVENTS));
    }

    public static boolean isSuccessfulAck(String status) {
        return "ACCEPTED".equals(status) || "ACCEPTED_PENDING".equals(status) || "DUPLICATE".equals(status);
    }
}
