package com.jingshanghui.pos.member.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** 积分精度、账户分量和流水增量不变量。 */
public final class PointsRules {
    public static final int SCALE=6;
    public static final BigDecimal ZERO=BigDecimal.ZERO.setScale(SCALE);
    public static final Set<String> TYPES=Set.of("EARN","FREEZE","UNFREEZE","SPEND","EXPIRE",
        "RETURN_EARN_REVERSAL","RETURN_SPEND_REVERSAL","MANUAL_ADJUST");

    public record Balance(BigDecimal available,BigDecimal frozen,BigDecimal debt,int version) { }
    public record Delta(BigDecimal available,BigDecimal frozen,BigDecimal debt) { }

    private PointsRules() { }

    /** 小数位超过六位直接拒绝，禁止静默舍入积分。 */
    public static BigDecimal exact(BigDecimal value,String field) {
        if(value==null) throw new ServiceException("MEM-POINTS-001: "+field+"缺失",400);
        try { return value.setScale(SCALE, RoundingMode.UNNECESSARY); }
        catch(ArithmeticException exception) { throw new ServiceException("MEM-POINTS-002: "+field+"精度超过6位",400); }
    }

    public static BigDecimal positive(BigDecimal value,String field) {
        BigDecimal exact=exact(value,field);
        if(exact.signum()<=0) throw new ServiceException("MEM-POINTS-003: "+field+"必须大于0",400);
        return exact;
    }

    /** 应用流水增量；任一投影分量小于零都表示规则或并发错误。 */
    public static Balance apply(Balance current,Delta delta) {
        if(current==null || delta==null) throw new ServiceException("MEM-POINTS-004: 账户或增量缺失",400);
        BigDecimal available=exact(current.available(),"可用积分").add(exact(delta.available(),"可用增量"));
        BigDecimal frozen=exact(current.frozen(),"冻结积分").add(exact(delta.frozen(),"冻结增量"));
        BigDecimal debt=exact(current.debt(),"积分债务").add(exact(delta.debt(),"债务增量"));
        if(available.signum()<0 || frozen.signum()<0 || debt.signum()<0)
            throw new ServiceException("MEM-POINTS-005: 积分账户不得出现无法解释的负分量",409);
        return new Balance(available,frozen,debt,current.version()+1);
    }

    /** 正向积分先偿还退货形成的显式债务，余数进入可用积分。 */
    public static Delta earn(Balance current,BigDecimal amount) {
        BigDecimal value=positive(amount,"获赠积分"); BigDecimal repay=current.debt().min(value);
        return new Delta(value.subtract(repay),ZERO,repay.negate());
    }

    public static Delta freeze(BigDecimal amount) {
        BigDecimal value=positive(amount,"冻结积分"); return new Delta(value.negate(),value,ZERO);
    }
    public static Delta spendFrozen(BigDecimal amount) {
        BigDecimal value=positive(amount,"消费积分"); return new Delta(ZERO,value.negate(),ZERO);
    }
    public static Delta unfreeze(BigDecimal amount) {
        BigDecimal value=positive(amount,"解冻积分"); return new Delta(value,value.negate(),ZERO);
    }
    public static Delta expire(BigDecimal amount) {
        BigDecimal value=positive(amount,"到期积分"); return new Delta(value.negate(),ZERO,ZERO);
    }
    public static Delta reverseEarn(BigDecimal availableFromOriginalLot,BigDecimal amount) {
        BigDecimal value=positive(amount,"退货扣回积分");
        BigDecimal available=exact(availableFromOriginalLot,"原批次可用积分").min(value);
        return new Delta(available.negate(),ZERO,value.subtract(available));
    }
    public static Delta reverseSpend(Balance current,BigDecimal amount) { return earn(current,amount); }

    /** 人工调整同样显式处理债务；负向不足部分转为债务而非负余额。 */
    public static Delta manual(Balance current,BigDecimal signedAmount) {
        BigDecimal value=exact(signedAmount,"人工调整积分");
        if(value.signum()==0) throw new ServiceException("MEM-POINTS-006: 人工调整不得为0",400);
        if(value.signum()>0) return earn(current,value);
        BigDecimal debit=value.abs(); BigDecimal fromAvailable=current.available().min(debit);
        return new Delta(fromAvailable.negate(),ZERO,debit.subtract(fromAvailable));
    }
}
