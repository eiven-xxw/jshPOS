package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.*;
import com.jingshanghui.pos.catalog.application.port.*;
import com.jingshanghui.pos.catalog.application.port.MemberPricePersistencePort.*;
import com.jingshanghui.pos.catalog.domain.MemberPriceRules;
import com.jingshanghui.pos.catalog.domain.MemberPriceRules.State;
import com.jingshanghui.pos.catalog.infrastructure.id.MemberPriceIdGenerator;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.member.application.port.MemberEntitlementQueryPort;
import com.jingshanghui.pos.member.application.port.MemberEntitlementQueryPort.EntitlementQuote;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** T2-MEM-003 Pricing Owner：发布不可变会员价版本并返回无 PII 候选。 */
@Service
@RequiredArgsConstructor
public class MemberPriceService implements MemberPriceResolutionPort {
    private static final String ULID="^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SHA256="^[a-f0-9]{64}$";
    private final TrustedTenantContext tenants;
    private final ScopeAuthorizationService authorization;
    private final DomainAuditService audit;
    private final CatalogMapper catalog;
    private final MemberEntitlementQueryPort entitlements;
    private final MemberPricePersistencePort persistence;
    private final MemberPriceIdGenerator ids;
    private final Clock clock;

    /** 草稿和明细同事务写入；金额一律是最小货币单位整数。 */
    @Transactional
    public VersionView create(CreateVersion command){
        requireCreate(command); authorization.requireTenantAdministrator();
        TrustedPrincipal principal=tenants.requirePrincipal();
        if(command.storeId()!=null) authorization.requireStoreAccess(command.storeId());
        List<ItemDraft> items=MemberPriceRules.requireItems(command.items());
        for(ItemDraft item:items){
            if(catalog.findProduct(principal.tenantId(),item.skuId())==null || catalog.findUnit(principal.tenantId(),item.unitId())==null)
                throw new ServiceException("PRC-MEMBER-005: 商品或单位不存在",404);
        }
        List<Map<String,Object>> canonicalItems=items.stream().map(i->Map.<String,Object>of("itemId",i.itemId(),
            "levelCode",i.levelCode(),"skuId",i.skuId(),"unitId",i.unitId(),"amountMinor",i.amountMinor())).toList();
        String contentHash=CanonicalJson.from(Map.of("bookCode",command.bookCode(),"versionNo",command.versionNo(),
            "storeId",command.storeId()==null?"TENANT":command.storeId(),"currency","CNY","items",canonicalItems)).sha256();
        String requestHash=hash(Map.of("versionId",command.versionId(),"contentSha256",contentHash));
        StoredCommand replay=replay(principal.tenantId(),"CREATE_MEMBER_PRICE",command.commandId(),requestHash);
        if(replay!=null)return requireVersion(principal.tenantId(),command.versionId());
        LocalDateTime now=now();
        persistence.insertVersion(new VersionWrite(principal.tenantId(),command.versionId(),command.bookCode(),
            command.versionNo(),command.storeId(),contentHash,principal.userId(),now));
        persistence.insertItems(items.stream().map(i->new ItemWrite(principal.tenantId(),i.itemId(),command.versionId(),
            i.levelCode(),i.skuId(),i.unitId(),i.amountMinor(),hash(Map.of("versionId",command.versionId(),
                "levelCode",i.levelCode(),"skuId",i.skuId(),"unitId",i.unitId(),"amountMinor",i.amountMinor())),now)).toList());
        appendFacts(principal,"pricing.member-price.version.changed.v1",command.versionId(),0,contentHash,
            command.correlationId(),Map.of("state","DRAFT","itemCount",items.size()));
        writeCommand(principal.tenantId(),"CREATE_MEMBER_PRICE",command.commandId(),requestHash,command.versionId(),contentHash);
        VersionView result=requireVersion(principal.tenantId(),command.versionId());
        audit.append("MEMBER_PRICE_DRAFT_CREATED","MEMBER_PRICE_VERSION",command.versionId(),null,result,
            Map.of("itemCount",items.size(),"contentSha256",contentHash));
        return result;
    }

    @Transactional public VersionView validate(VersionAction c){return transition(c,State.DRAFT,State.VALIDATED,false,false);}
    @Transactional public VersionView approve(VersionAction c){return transition(c,State.VALIDATED,State.APPROVED,true,false);}
    @Transactional public VersionView publish(VersionAction c){
        requireAction(c,true);LocalDateTime from=utc(c.effectiveAt()),to=utc(c.expiresAt());
        MemberPriceRules.requireWindow(from,to);VersionView current=lock(c.versionId());
        if(persistence.countPublishingConflicts(tenants.requireTenantId(),c.versionId(),current.storeId(),from,to)>0)
            throw new ServiceException("PRC-MEMBER-006: 会员价生效窗口冲突",409);
        State target=from.isAfter(now())?State.SCHEDULED:State.ACTIVE;
        return transitionLocked(c,current,State.APPROVED,target,false,true);
    }

    /** 先向 Member Owner 校验权益快照，再按门店优先解析会员价候选。 */
    @Override
    @Transactional(readOnly=true)
    public MemberPriceCandidate resolve(String snapshotId,Long skuId,Long unitId,Long storeId,Instant at){
        if(skuId==null||skuId<=0||unitId==null||unitId<=0||storeId==null||storeId<=0||at==null)
            throw new ServiceException("PRC-MEMBER-007: 会员价解析参数无效",400);
        authorization.requireStoreAccess(storeId);
        EntitlementQuote entitlement=entitlements.resolve(snapshotId,storeId,at);
        if(!entitlement.memberPriceEligible()) return null;
        return persistence.findCandidate(new CandidateLookup(tenants.requireTenantId(),entitlement.levelCode(),skuId,
            unitId,storeId,utc(at),snapshotId));
    }

    private VersionView transition(VersionAction c,State from,State to,boolean separate,boolean publish){
        requireAction(c,publish);VersionView current=lock(c.versionId());
        if(!from.name().equals(current.state()))throw invalidState();
        return transitionLocked(c,current,from,to,separate,publish);
    }
    private VersionView transitionLocked(VersionAction c,VersionView current,State from,State to,
                                         boolean separate,boolean publish){
        if(!MemberPriceRules.canTransition(from,to))throw invalidState();
        TrustedPrincipal principal=tenants.requirePrincipal();
        if(!current.contentSha256().equals(c.contentSha256()))throw new ServiceException("PRC-MEMBER-008: 会员价版本摘要不匹配",409);
        if(separate&&Objects.equals(current.createdBy(),principal.userId()))throw new ServiceException("PRC-MEMBER-009: 创建人与批准人必须分离",409);
        String requestHash=hash(Map.of("versionId",c.versionId(),"from",from.name(),"to",to.name(),
            "contentSha256",c.contentSha256(),"effectiveAt",optional(c.effectiveAt()),"expiresAt",optional(c.expiresAt())));
        StoredCommand replay=replay(principal.tenantId(),"MEMBER_PRICE_"+to.name(),c.commandId(),requestHash);
        if(replay!=null)return requireVersion(principal.tenantId(),c.versionId());
        Long approved=to==State.APPROVED?principal.userId():current.approvedBy();
        if(persistence.transition(new Transition(principal.tenantId(),c.versionId(),from.name(),to.name(),current.version(),
            approved,publish?utc(c.effectiveAt()):current.effectiveAt(),publish?utc(c.expiresAt()):current.expiresAt(),now()))!=1)
            throw new ServiceException("PRC-MEMBER-010: 会员价版本并发冲突",409);
        appendFacts(principal,"pricing.member-price.version.changed.v1",c.versionId(),current.version()+1,
            current.contentSha256(),c.correlationId(),Map.of("from",from.name(),"to",to.name()));
        writeCommand(principal.tenantId(),"MEMBER_PRICE_"+to.name(),c.commandId(),requestHash,c.versionId(),current.contentSha256());
        VersionView result=requireVersion(principal.tenantId(),c.versionId());
        audit.append("MEMBER_PRICE_"+to.name(),"MEMBER_PRICE_VERSION",c.versionId(),current,result,
            Map.of("contentSha256",current.contentSha256()));
        return result;
    }

    private void appendFacts(TrustedPrincipal p,String type,String aggregate,int version,String content,String correlation,Map<String,Object> details){
        String eventId=ids.next();LocalDateTime at=now();Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("eventId",eventId);payload.put("eventType",type);payload.put("schemaVersion","1.0");
        payload.put("tenantContext",Map.of("tenantId",p.tenantId()));payload.put("aggregateId",aggregate);
        payload.put("aggregateVersion",version);payload.put("contentSha256",content);payload.put("correlationId",correlation);
        payload.put("details",details);payload.put("occurredAt",at+"Z");CanonicalJson.Result json=CanonicalJson.from(payload);
        persistence.insertOutbox(new OutboxWrite(p.tenantId(),eventId,type,aggregate,version,json.json(),json.sha256(),at));
    }
    private StoredCommand replay(String t,String type,String key,String hash){StoredCommand c=persistence.findCommand(t,type,key);
        if(c!=null&&!c.requestSha256().equals(hash))throw new ServiceException("PRC-MEMBER-011: 同幂等键异内容",409);return c;}
    private void writeCommand(String t,String type,String key,String request,String aggregate,String result){
        persistence.insertCommand(new CommandWrite(t,ids.next(),type,key,request,aggregate,result,now()));}
    private VersionView lock(String id){authorization.requireTenantAdministrator();VersionView v=persistence.lockVersion(tenants.requireTenantId(),id);
        if(v==null)throw new ServiceException("PRC-MEMBER-012: 会员价版本不存在",404);return v;}
    private VersionView requireVersion(String t,String id){VersionView v=persistence.findVersion(t,id);
        if(v==null)throw new ServiceException("PRC-MEMBER-012: 会员价版本不存在",404);return v;}
    private void requireCreate(CreateVersion c){if(c==null||c.commandId()==null||!c.commandId().matches(ULID)||c.versionId()==null
        ||!c.versionId().matches(ULID)||c.correlationId()==null||!c.correlationId().matches(ULID)||c.bookCode()==null
        ||!c.bookCode().matches("^[A-Z0-9_-]{1,64}$")||c.versionNo()<=0)throw new ServiceException("PRC-MEMBER-013: 会员价创建命令无效",400);}
    private void requireAction(VersionAction c,boolean publish){if(c==null||c.commandId()==null||!c.commandId().matches(ULID)
        ||c.versionId()==null||!c.versionId().matches(ULID)||c.correlationId()==null||!c.correlationId().matches(ULID)
        ||c.contentSha256()==null||!c.contentSha256().matches(SHA256)||(publish&&c.effectiveAt()==null))
        throw new ServiceException("PRC-MEMBER-014: 会员价版本命令无效",400);}
    private ServiceException invalidState(){return new ServiceException("PRC-MEMBER-015: 会员价状态迁移无效",409);}
    private String hash(Map<String,Object> v){return CanonicalJson.from(v).sha256();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private LocalDateTime utc(Instant v){return v==null?null:LocalDateTime.ofInstant(v,ZoneOffset.UTC);}
    private String optional(Instant v){return v==null?"NONE":v.toString();}
}
