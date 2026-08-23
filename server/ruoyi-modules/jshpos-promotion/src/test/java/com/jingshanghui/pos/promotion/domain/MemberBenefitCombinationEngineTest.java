package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.promotion.domain.MemberBenefitCombinationEngine.*;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 覆盖 BEST_PRICE、同额普通路径、双向叠加和金额守恒。 */
class MemberBenefitCombinationEngineTest {
    private static final String LINE="01K5E000000000000000000001";
    private static final String SNAP="01K5E000000000000000000002";
    private static final String VERSION="01K5E000000000000000000003";
    private final MemberBenefitCombinationEngine engine=new MemberBenefitCombinationEngine();

    @Test void memberPathWinsWhenItHasLowerPayable(){
        Result result=engine.combine(normal(10),List.of(member(80)),SNAP,false,false);
        assertThat(result.path()).isEqualTo(Path.MEMBER_PATH);
        assertThat(result.quote().discountAmountMinor()).isEqualTo(20);
        assertThat(result.quote().payableAmountMinor()).isEqualTo(80);
    }
    @Test void normalWinsAndBreaksTieDeterministically(){
        assertThat(engine.combine(normal(30),List.of(member(80)),SNAP,false,false).path()).isEqualTo(Path.NORMAL_PATH);
        assertThat(engine.combine(normal(20),List.of(member(80)),SNAP,false,false).decisionChain().get(0).code())
            .isEqualTo("BEST_PRICE_TIE_NORMAL_SELECTED");
        assertThat(engine.combine(normal(0),List.of(),SNAP,false,false).path()).isEqualTo(Path.NORMAL_PATH);
    }
    @Test void stackingRequiresBothSidesAndCapsAtGross(){
        assertThat(engine.combine(normal(30),List.of(member(50)),SNAP,true,false).path()).isEqualTo(Path.MEMBER_PATH);
        Result stacked=engine.combine(normal(60),List.of(member(30)),SNAP,true,true);
        assertThat(stacked.path()).isEqualTo(Path.STACKED_MEMBER_PATH);
        assertThat(stacked.quote().discountAmountMinor()).isEqualTo(100);
        assertThat(stacked.quote().payableAmountMinor()).isZero();
        assertThat(stacked.quote().adjustments()).extracting(AppliedAdjustment::amountMinor)
            .containsExactly(60L,40L);
        assertThat(stacked.quote().adjustments()).extracting(AppliedAdjustment::amountMinor)
            .satisfies(values -> assertThat(values.stream().mapToLong(Long::longValue).sum()).isEqualTo(100));
    }
    @Test void exactQuantityUsesHalfEvenAndRejectsTampering(){
        assertThat(engine.exactLineAmount(101,new BigDecimal("0.500000"))).isEqualTo(50);
        assertThatThrownBy(()->engine.exactLineAmount(-1,BigDecimal.ONE)).hasMessageContaining("PRM-MEMBER-004");
        MemberPriceCandidate bad=new MemberPriceCandidate(VERSION,"01K5E000000000000000000004",SNAP,"GOLD",
            99L,12L,1L,80,"CNY","a".repeat(64),Instant.EPOCH,null);
        assertThatThrownBy(()->engine.combine(normal(0),List.of(new MemberLine(basket(),12L,bad)),SNAP,false,false))
            .hasMessageContaining("PRM-MEMBER-003");
    }
    @Test void malformedInputsAndAllocationDriftFailClosed(){
        assertThatThrownBy(()->engine.combine(null,List.of(),null,false,false))
            .hasMessageContaining("PRM-MEMBER-006");
        assertThat(engine.combine(normal(0),null,SNAP,false,false).path()).isEqualTo(Path.NORMAL_PATH);
        assertThat(engine.combine(normal(0),List.of(member(80)),null,false,false).path()).isEqualTo(Path.NORMAL_PATH);
        assertThatThrownBy(()->engine.exactLineAmount(1,null)).hasMessageContaining("PRM-MEMBER-004");
        assertThatThrownBy(()->engine.exactLineAmount(1,BigDecimal.ZERO)).hasMessageContaining("PRM-MEMBER-004");
        assertThatThrownBy(()->engine.exactLineAmount(1,new BigDecimal("0.0000001")))
            .hasMessageContaining("PRM-MEMBER-004");
        assertThatThrownBy(()->engine.combine(normal(0),List.of(new MemberLine(basket(),0L,null)),SNAP,false,false))
            .hasMessageContaining("PRM-MEMBER-001");
        assertThatThrownBy(()->engine.combine(normal(0),Collections.singletonList(null),SNAP,false,false))
            .hasMessageContaining("PRM-MEMBER-001");
        BasketLine unknown=new BasketLine("01K5E000000000000000000009",1,11L,null,null,BigDecimal.ONE,100);
        assertThatThrownBy(()->engine.combine(normal(0),List.of(new MemberLine(unknown,12L,null)),SNAP,false,false))
            .hasMessageContaining("PRM-MEMBER-002");
        QuoteLine line=new QuoteLine(LINE,100,60,40);
        QuoteResult drifted=new QuoteResult(100,60,40,List.of(line),List.of("NORMAL"),List.of(),
            List.of(new AppliedAdjustment("NORMAL",59,Map.of(LINE,59L))));
        assertThatThrownBy(()->engine.combine(drifted,List.of(member(30)),SNAP,true,true))
            .hasMessageContaining("PRM-MEMBER-005");
    }
    private QuoteResult normal(long discount){
        QuoteLine line=new QuoteLine(LINE,100,discount,100-discount);
        AppliedAdjustment adj=new AppliedAdjustment("NORMAL",discount,Map.of(LINE,discount));
        return new QuoteResult(100,discount,100-discount,List.of(line),discount==0?List.of():List.of("NORMAL"),
            List.of(),discount==0?List.of():List.of(adj));
    }
    private MemberLine member(long amount){
        return new MemberLine(basket(),12L,new MemberPriceCandidate(VERSION,"01K5E000000000000000000004",
            SNAP,"GOLD",11L,12L,1L,amount,"CNY","a".repeat(64),Instant.EPOCH,null));
    }
    private BasketLine basket(){return new BasketLine(LINE,1,11L,null,null,BigDecimal.ONE,100);}
}
