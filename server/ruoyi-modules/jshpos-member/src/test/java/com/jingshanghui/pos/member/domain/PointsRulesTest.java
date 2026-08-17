package com.jingshanghui.pos.member.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

/** 覆盖六位精度、债务、冻结、消费、解冻、到期和人工调整不变量。 */
class PointsRulesTest {
    private static BigDecimal d(String value){return new BigDecimal(value);}
    private static PointsRules.Balance balance(String available,String frozen,String debt){
        return new PointsRules.Balance(d(available),d(frozen),d(debt),2);
    }

    @Test void enforcesExactScaleAndPositiveAmount() {
        assertThat(PointsRules.exact(d("1.2"),"积分")).isEqualByComparingTo("1.200000");
        assertThatThrownBy(() -> PointsRules.exact(null,"积分")).hasMessageContaining("MEM-POINTS-001");
        assertThatThrownBy(() -> PointsRules.exact(d("1.0000001"),"积分")).hasMessageContaining("MEM-POINTS-002");
        assertThat(PointsRules.positive(d("0.000001"),"积分")).isEqualByComparingTo("0.000001");
        assertThatThrownBy(() -> PointsRules.positive(BigDecimal.ZERO,"积分")).hasMessageContaining("MEM-POINTS-003");
        assertThatThrownBy(() -> PointsRules.positive(d("-1"),"积分")).hasMessageContaining("MEM-POINTS-003");
    }

    @Test void earnRepaysDebtBeforeIncreasingAvailable() {
        var partial=PointsRules.earn(balance("0","0","10"),d("4"));
        assertThat(partial.available()).isEqualByComparingTo("0");
        assertThat(partial.debt()).isEqualByComparingTo("-4");
        var excess=PointsRules.earn(balance("0","0","3"),d("5"));
        assertThat(excess.available()).isEqualByComparingTo("2");
        assertThat(excess.debt()).isEqualByComparingTo("-3");
    }

    @Test void buildsDeclaredDeltasAndAppliesWithoutNegativeComponents() {
        assertThat(PointsRules.freeze(d("3")).available()).isEqualByComparingTo("-3");
        assertThat(PointsRules.freeze(d("3")).frozen()).isEqualByComparingTo("3");
        assertThat(PointsRules.spendFrozen(d("2")).frozen()).isEqualByComparingTo("-2");
        assertThat(PointsRules.unfreeze(d("2")).available()).isEqualByComparingTo("2");
        assertThat(PointsRules.expire(d("1")).available()).isEqualByComparingTo("-1");
        var next=PointsRules.apply(balance("5","2","0"),PointsRules.unfreeze(d("2")));
        assertThat(next.available()).isEqualByComparingTo("7");
        assertThat(next.frozen()).isZero(); assertThat(next.version()).isEqualTo(3);
        assertThatThrownBy(() -> PointsRules.apply(null,PointsRules.freeze(d("1"))))
            .hasMessageContaining("MEM-POINTS-004");
        assertThatThrownBy(() -> PointsRules.apply(balance("0","0","0"),PointsRules.expire(d("1"))))
            .hasMessageContaining("MEM-POINTS-005");
        assertThatThrownBy(() -> PointsRules.apply(balance("0","0","0"),PointsRules.spendFrozen(d("1"))))
            .hasMessageContaining("MEM-POINTS-005");
        assertThatThrownBy(() -> PointsRules.apply(balance("0","0","0"),
            new PointsRules.Delta(PointsRules.ZERO,PointsRules.ZERO,d("-1"))))
            .hasMessageContaining("MEM-POINTS-005");
    }

    @Test void reversalsAndManualAdjustmentsKeepDebtExplicit() {
        var returned=PointsRules.reverseEarn(d("2"),d("5"));
        assertThat(returned.available()).isEqualByComparingTo("-2");
        assertThat(returned.debt()).isEqualByComparingTo("3");
        var restored=PointsRules.reverseSpend(balance("0","0","2"),d("5"));
        assertThat(restored.available()).isEqualByComparingTo("3");
        assertThat(restored.debt()).isEqualByComparingTo("-2");
        var debit=PointsRules.manual(balance("2","0","0"),d("-5"));
        assertThat(debit.available()).isEqualByComparingTo("-2");
        assertThat(debit.debt()).isEqualByComparingTo("3");
        assertThat(PointsRules.manual(balance("0","0","2"),d("5")).available()).isEqualByComparingTo("3");
        assertThatThrownBy(() -> PointsRules.manual(balance("0","0","0"),BigDecimal.ZERO))
            .hasMessageContaining("MEM-POINTS-006");
    }
}
