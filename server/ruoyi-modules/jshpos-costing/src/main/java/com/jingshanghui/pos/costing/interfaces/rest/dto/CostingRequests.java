package com.jingshanghui.pos.costing.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/** 成本治理请求；成本、数量、tenant_id 和采购价格均不得由外部请求提交。 */
public final class CostingRequests {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private CostingRequests() {
    }

    public record PublishPolicy(@Pattern(regexp = ULID) String policyVersionId,
                                @NotBlank String storeId,
                                @Pattern(regexp = ULID) String warehouseId,
                                Instant effectiveFrom,
                                @NotBlank String correlationId) {
    }

    public record Rebuild(@Pattern(regexp = ULID) String rebuildId,
                          @NotBlank String correlationId) {
    }
}
