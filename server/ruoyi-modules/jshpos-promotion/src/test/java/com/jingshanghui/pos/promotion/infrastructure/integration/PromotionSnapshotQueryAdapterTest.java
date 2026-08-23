package com.jingshanghui.pos.promotion.infrastructure.integration;

import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredMemberBenefitBinding;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredQuote;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredSnapshot;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredSnapshotLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 验证跨 Owner 只读端口传递原会员权益绑定而不暴露 Mapper 或 PII。 */
class PromotionSnapshotQueryAdapterTest {
    @Test void returnsFrozenMemberBenefitFromOriginalQuote(){
        PromotionPersistencePort persistence=mock(PromotionPersistencePort.class);
        String tenant="TENANT_A", snapshotId="01K5E000000000000000000001",
            orderId="01K5E000000000000000000002", quoteId="01K5E000000000000000000003";
        when(persistence.lockSnapshot(tenant,snapshotId)).thenReturn(new StoredSnapshot(snapshotId,orderId,quoteId,
            1101L,"01K5E000000000000000000004",LocalDate.parse("2026-08-23"),"CNY","a".repeat(64),
            "b".repeat(64),100,20,80));
        when(persistence.findQuote(tenant,quoteId)).thenReturn(new StoredQuote(quoteId,1101L,
            "01K5E000000000000000000004", LocalDateTime.parse("2026-08-23T05:00:00"),"CNY",
            "c".repeat(64),"a".repeat(64),"promotion-engine-1.0.0",3,100,20,80));
        when(persistence.listSnapshotLines(tenant,snapshotId)).thenReturn(List.of(new StoredSnapshotLine(
            "01K5E000000000000000000005",1,11L,BigDecimal.ONE,100,20,80,"{}","d".repeat(64))));
        when(persistence.findMemberBenefitBinding(tenant,quoteId)).thenReturn(new StoredMemberBenefitBinding(quoteId,
            "01K5E000000000000000000006","01K5E000000000000000000007","MEMBER_PATH",
            "[\"01K5E000000000000000000008\"]",9,"e".repeat(64),"f".repeat(64),"1".repeat(64),
            "2".repeat(64)));

        var result=new PromotionSnapshotQueryAdapter(persistence).requireSnapshot(tenant,snapshotId);

        assertThat(result.memberBenefit()).isNotNull();
        assertThat(result.memberBenefit().selectedPath()).isEqualTo("MEMBER_PATH");
        assertThat(result.memberBenefit().contentSha256()).isEqualTo("2".repeat(64));
        verify(persistence).findMemberBenefitBinding(tenant,quoteId);
    }
}
