package com.jingshanghui.pos.catalog.domain;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.ItemDraft;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static com.jingshanghui.pos.catalog.domain.MemberPriceRules.State.*;
import static org.assertj.core.api.Assertions.*;

/** 覆盖会员价精确金额、唯一明细、窗口与状态机。 */
class MemberPriceRulesTest {
    private static final String ITEM="01K5E000000000000000000001";
    @Test void acceptsExactMinorUnitItemsAndWindow(){
        assertThat(MemberPriceRules.requireItems(List.of(new ItemDraft(ITEM,"GOLD",11L,12L,0L)))).hasSize(1);
        assertThatCode(()->MemberPriceRules.requireWindow(LocalDateTime.of(2026,8,23,1,0),null)).doesNotThrowAnyException();
    }
    @Test void rejectsMissingMalformedAndDuplicateItems(){
        assertThatThrownBy(()->MemberPriceRules.requireItems(null)).hasMessageContaining("PRC-MEMBER-001");
        assertThatThrownBy(()->MemberPriceRules.requireItems(List.of())).hasMessageContaining("PRC-MEMBER-001");
        assertThatThrownBy(()->MemberPriceRules.requireItems(List.of(new ItemDraft("bad","GOLD",11L,12L,1L))))
            .hasMessageContaining("PRC-MEMBER-002");
        assertThatThrownBy(()->MemberPriceRules.requireItems(List.of(new ItemDraft(ITEM,"GOLD",11L,12L,-1L))))
            .hasMessageContaining("PRC-MEMBER-002");
        assertThatThrownBy(()->MemberPriceRules.requireItems(List.of(new ItemDraft(ITEM,"GOLD",11L,12L,1L),
            new ItemDraft("01K5E000000000000000000002","GOLD",11L,12L,2L))))
            .hasMessageContaining("PRC-MEMBER-003");
    }
    @Test void rejectsEveryInvalidItemBoundaryWithoutFloatingPointFallback(){
        assertThatThrownBy(()->MemberPriceRules.requireItems(java.util.Collections.nCopies(100_001,
            new ItemDraft(ITEM,"GOLD",11L,12L,1L)))).hasMessageContaining("PRC-MEMBER-001");
        for (ItemDraft invalid : Arrays.asList(
            null,
            new ItemDraft(null,"GOLD",11L,12L,1L),
            new ItemDraft(ITEM,null,11L,12L,1L),
            new ItemDraft(ITEM,"bad space",11L,12L,1L),
            new ItemDraft(ITEM,"GOLD",null,12L,1L),
            new ItemDraft(ITEM,"GOLD",0L,12L,1L),
            new ItemDraft(ITEM,"GOLD",11L,null,1L),
            new ItemDraft(ITEM,"GOLD",11L,0L,1L),
            new ItemDraft(ITEM,"GOLD",11L,12L,null))) {
            assertThatThrownBy(() -> MemberPriceRules.requireItems(Arrays.asList(invalid)))
                .hasMessageContaining("PRC-MEMBER-002");
        }
    }
    @Test void enforcesWindowAndNamedTransitions(){
        LocalDateTime at=LocalDateTime.of(2026,8,23,1,0);
        assertThatThrownBy(()->MemberPriceRules.requireWindow(null,at)).hasMessageContaining("PRC-MEMBER-004");
        assertThatThrownBy(()->MemberPriceRules.requireWindow(at,at)).hasMessageContaining("PRC-MEMBER-004");
        assertThatCode(()->MemberPriceRules.requireWindow(at,at.plusNanos(1))).doesNotThrowAnyException();
        assertThat(MemberPriceRules.canTransition(DRAFT,VALIDATED)).isTrue();
        assertThat(MemberPriceRules.canTransition(VALIDATED,APPROVED)).isTrue();
        assertThat(MemberPriceRules.canTransition(APPROVED,SCHEDULED)).isTrue();
        assertThat(MemberPriceRules.canTransition(APPROVED,ACTIVE)).isTrue();
        assertThat(MemberPriceRules.canTransition(SCHEDULED,ACTIVE)).isTrue();
        assertThat(MemberPriceRules.canTransition(ACTIVE,RETIRED)).isTrue();
        assertThat(MemberPriceRules.canTransition(RETIRED,ACTIVE)).isFalse();
        assertThat(MemberPriceRules.canTransition(DRAFT,ACTIVE)).isFalse();
        assertThat(MemberPriceRules.canTransition(VALIDATED,ACTIVE)).isFalse();
        assertThat(MemberPriceRules.canTransition(APPROVED,RETIRED)).isFalse();
        assertThat(MemberPriceRules.canTransition(SCHEDULED,RETIRED)).isFalse();
        assertThat(MemberPriceRules.canTransition(ACTIVE,APPROVED)).isFalse();
    }
}
