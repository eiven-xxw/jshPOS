package com.jingshanghui.pos.catalog.domain;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.ItemDraft;
import org.dromara.common.core.exception.ServiceException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** T2-MEM-003 会员价版本和候选的精确金额不变量。 */
public final class MemberPriceRules {
    public enum State { DRAFT, VALIDATED, APPROVED, SCHEDULED, ACTIVE, RETIRED }
    public static List<ItemDraft> requireItems(List<ItemDraft> items) {
        if(items==null || items.isEmpty() || items.size()>100_000)
            throw new ServiceException("PRC-MEMBER-001: 会员价明细数量无效",400);
        Set<String> keys=new HashSet<>();
        for(ItemDraft item:items){
            if(item==null || item.itemId()==null || !item.itemId().matches("^[0-9A-HJKMNP-TV-Z]{26}$")
                || item.levelCode()==null || !item.levelCode().matches("^[A-Z0-9_-]{1,32}$")
                || item.skuId()==null || item.skuId()<=0 || item.unitId()==null || item.unitId()<=0
                || item.amountMinor()==null || item.amountMinor()<0)
                throw new ServiceException("PRC-MEMBER-002: 会员价明细无效",400);
            if(!keys.add(item.levelCode()+"|"+item.skuId()+"|"+item.unitId()))
                throw new ServiceException("PRC-MEMBER-003: 会员价明细重复",409);
        }
        return List.copyOf(items);
    }
    public static void requireWindow(LocalDateTime from,LocalDateTime to){
        if(from==null || (to!=null && !to.isAfter(from)))
            throw new ServiceException("PRC-MEMBER-004: 会员价生效窗口无效",400);
    }
    public static boolean canTransition(State from,State to){
        return switch(from){
            case DRAFT -> to==State.VALIDATED;
            case VALIDATED -> to==State.APPROVED;
            case APPROVED -> Set.of(State.SCHEDULED,State.ACTIVE).contains(to);
            case SCHEDULED -> to==State.ACTIVE;
            case ACTIVE -> to==State.RETIRED;
            case RETIRED -> false;
        };
    }
    private MemberPriceRules(){ }
}
