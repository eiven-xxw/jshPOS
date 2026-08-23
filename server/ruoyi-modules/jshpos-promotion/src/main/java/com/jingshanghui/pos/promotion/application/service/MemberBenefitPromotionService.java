package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.catalog.application.port.MemberPriceResolutionPort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.member.application.port.MemberEntitlementQueryPort;
import com.jingshanghui.pos.member.application.port.MemberEntitlementQueryPort.EntitlementQuote;
import com.jingshanghui.pos.promotion.application.model.MemberBenefitPromotionModels.*;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.port.MemberBenefitCapabilityPort;
import com.jingshanghui.pos.promotion.application.port.MemberBenefitCapabilityPort.Capability;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.*;
import com.jingshanghui.pos.promotion.domain.MemberBenefitCombinationEngine;
import com.jingshanghui.pos.promotion.domain.MemberBenefitCombinationEngine.MemberLine;
import com.jingshanghui.pos.promotion.domain.MemberBenefitCombinationEngine.Result;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** T2-MEM-003 Promotion Owner：按冻结顺序执行普通促销、会员价、叠加决策和 BEST_PRICE。 */
@Service
@RequiredArgsConstructor
public class MemberBenefitPromotionService {
    private static final String ULID="^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String ZERO_SHA="0".repeat(64);
    private final TrustedTenantContext tenants;
    private final ScopeAuthorizationService authorization;
    private final PromotionPersistencePort persistence;
    private final PromotionRuleDefinitionCodec ruleCodec;
    private final PromotionEngine promotionEngine;
    private final MemberEntitlementQueryPort entitlements;
    private final MemberPriceResolutionPort memberPrices;
    private final MemberBenefitCapabilityPort capabilities;
    private final MemberBenefitCombinationEngine combination;
    private final PromotionIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 报价、行、调整、会员权益绑定、命令、审计和 Outbox 在同一 MySQL 事务写入。 */
    @Transactional
    public MemberQuoteView quote(MemberQuote command) {
        validate(command);
        TrustedPrincipal principal=tenants.requirePrincipal();
        authorization.requireStoreAccess(command.storeId());
        String requestHash=canonicalRequest(command).sha256();
        MemberQuoteView replay=replay(principal.tenantId(),command.pricingRequestId(),requestHash);
        if(replay!=null)return replay;
        PackageView packageView=persistence.findPackage(principal.tenantId(),command.storeId(),command.packageVersion());
        LocalDateTime businessTime=utc(command.businessTime().toInstant());
        if(packageView==null||businessTime.isBefore(packageView.generatedAt())||!businessTime.isBefore(packageView.expiresAt()))
            throw new ServiceException("PRM-MEMBER-008: 规则包不存在、不适用或已过期",409);
        List<RuleVersion> rules=persistence.listPackageRules(principal.tenantId(),command.storeId(),
            command.packageVersion()).stream().map(ruleCodec::fromRow).toList();
        List<BasketLine> baskets=command.lines().stream().map(MemberQuoteLine::basket).toList();
        QuoteResult normal=promotionEngine.quote(new QuoteRequest(command.businessTime(),command.storeId(),
            command.channel(),baskets,rules));
        Capability capability=capabilities.resolve(command.storeId());
        EntitlementQuote entitlement=null;
        Result combined;
        if(!capability.enabled()){
            combined=combination.combine(normal,List.of(),command.entitlementSnapshotId(),false,false);
        }else{
            if(command.entitlementSnapshotId()==null||!command.entitlementSnapshotId().matches(ULID))
                throw new ServiceException("PRM-MEMBER-009: 已启用会员权益时必须提供有效权益快照",409);
            entitlement=entitlements.resolve(command.entitlementSnapshotId(),command.storeId(),
                command.businessTime().toInstant());
            List<MemberLine> lines=new ArrayList<>();
            for(MemberQuoteLine line:command.lines()){
                MemberPriceCandidate candidate=memberPrices.resolve(command.entitlementSnapshotId(),
                    line.basket().skuId(),line.unitId(),command.storeId(),command.businessTime().toInstant());
                lines.add(new MemberLine(line.basket(),line.unitId(),candidate));
            }
            combined=combination.combine(normal,lines,command.entitlementSnapshotId(),entitlement.stackingAllowed(),
                capability.promotionStackingAllowed());
        }
        QuoteResult result=withDecisionChain(combined);
        String resultHash=canonicalResult(result).sha256();
        String explanationHash=CanonicalJson.from(Map.of("path",combined.path().name(),"chain",
            combined.decisionChain().stream().map(e->e.sourceId()+":"+e.code()).toList())).sha256();
        String quoteId=ids.next();
        persistence.insertQuote(new QuoteWrite(principal.tenantId(),quoteId,command.storeId(),command.terminalId(),
            command.pricingRequestId(),requestHash,PromotionEngine.ENGINE_VERSION,command.packageVersion(),businessTime,
            result.grossAmountMinor(),result.discountAmountMinor(),result.payableAmountMinor(),command.currency(),resultHash));
        persistLinesAndAdjustments(principal.tenantId(),quoteId,command.lines(),result,combined.memberPriceVersionIds());
        String benefitVersion=entitlement==null?null:entitlement.benefitVersionId();
        String rightsDigest=entitlement==null?ZERO_SHA:entitlement.rightsDigest();
        String versionsJson=write(combined.memberPriceVersionIds());
        String bindingHash=CanonicalJson.from(Map.of("quoteId",quoteId,"entitlementSnapshotId",
            optional(command.entitlementSnapshotId()),"benefitVersionId",optional(benefitVersion),
            "selectedPath",combined.path().name(),"memberPriceVersionIds",combined.memberPriceVersionIds(),
            "capabilityConfigVersion",capability.configVersion(),"capabilitySha256",capability.contentSha256(),
            "rightsDigest",rightsDigest,"explanationSha256",explanationHash,"resultSha256",resultHash)).sha256();
        persistence.insertMemberBenefitBinding(new MemberBenefitBindingWrite(principal.tenantId(),ids.next(),quoteId,
            command.entitlementSnapshotId(),benefitVersion,combined.path().name(),versionsJson,
            capability.configVersion(),capability.contentSha256(),rightsDigest,explanationHash,bindingHash,
            LocalDateTime.now(clock)));
        MemberQuoteView view=new MemberQuoteView(quoteId,requestHash,resultHash,PromotionEngine.ENGINE_VERSION,
            command.packageVersion(),combined.path(),command.entitlementSnapshotId(),benefitVersion,
            combined.memberPriceVersionIds(),rightsDigest,explanationHash,result);
        persistCommand(principal.tenantId(),command.pricingRequestId(),requestHash,quoteId,view);
        appendFacts(principal,quoteId,command.correlationId(),bindingHash,combined.path().name());
        return view;
    }

    private void persistLinesAndAdjustments(String tenantId,String quoteId,List<MemberQuoteLine> inputs,
                                            QuoteResult result,List<String> memberVersions){
        Map<String,MemberQuoteLine> byId=new HashMap<>();inputs.forEach(i->byId.put(i.basket().lineId(),i));
        for(QuoteLine line:result.lines()){
            BasketLine input=byId.get(line.lineId()).basket();
            persistence.insertQuoteLine(new QuoteLineWrite(tenantId,ids.next(),quoteId,line.lineId(),input.lineNo(),
                input.skuId(),input.quantity(),input.unitPriceMinor(),line.grossAmountMinor(),
                line.discountAmountMinor(),line.payableAmountMinor()));
        }
        int ordinal=0;
        for(AppliedAdjustment adjustment:result.adjustments())for(Map.Entry<String,Long> allocation:
            new TreeMap<>(adjustment.lineAllocations()).entrySet())persistence.insertAdjustment(new AdjustmentWrite(
                tenantId,ids.next(),quoteId,allocation.getKey(),memberVersions.contains(adjustment.sourceId())
                ?"MEMBER_PRICE":"RULE",adjustment.sourceId(),"MEMBER_BENEFIT",allocation.getValue(),
                "APPLIED",true,++ordinal));
        for(Explanation explanation:result.explanations())persistence.insertAdjustment(new AdjustmentWrite(tenantId,
            ids.next(),quoteId,null,"EXPLANATION",explanation.sourceId(),"MEMBER_BENEFIT",0,
            explanation.code(),false,++ordinal));
    }

    private MemberQuoteView replay(String tenantId,String key,String requestHash){
        StoredCommand stored=persistence.findCommand(tenantId,"MEMBER_PROMOTION_QUOTE",key);
        if(stored==null)return null;
        if(!stored.requestSha256().equals(requestHash))throw new ServiceException("PRM-IDEMP-003: 同一命令幂等键对应不同内容",409);
        try{
            MemberQuoteView view=objectMapper.readValue(stored.resultJson(),MemberQuoteView.class);
            if(!view.resultSha256().equals(stored.resultSha256()))throw new ServiceException("PRM-IDEMP-004: 命令结果摘要不一致",500);
            return view;
        }catch(JsonProcessingException e){throw new ServiceException("PRM-IDEMP-004: 命令结果损坏",500);}
    }
    private void persistCommand(String tenantId,String key,String request,String aggregate,MemberQuoteView view){
        persistence.insertCommand(new CommandWrite(tenantId,ids.next(),"MEMBER_PROMOTION_QUOTE",key,request,
            "PROMOTION_QUOTE",aggregate,view.resultSha256(),write(view)));
    }
    private void appendFacts(TrustedPrincipal principal,String quoteId,String correlation,String hash,String path){
        LocalDateTime now=LocalDateTime.now(clock);Map<String,Object> summary=Map.of("quoteId",quoteId,
            "selectedPath",path,"contentSha256",hash);CanonicalJson.Result payload=CanonicalJson.from(summary);
        persistence.insertAudit(new AuditWrite(principal.tenantId(),ids.next(),"MEMBER_BENEFIT_QUOTED",
            "PROMOTION_QUOTE",quoteId,principal.userId(),correlation,null,hash,payload.json(),now));
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(),ids.next(),"promotion.member-benefit.quoted.v1",
            "PROMOTION_QUOTE",quoteId,1,payload.json(),payload.sha256(),now));
    }
    private QuoteResult withDecisionChain(Result combined){
        List<Explanation> explanations=new ArrayList<>(combined.quote().explanations());
        explanations.addAll(combined.decisionChain());
        QuoteResult q=combined.quote();return new QuoteResult(q.grossAmountMinor(),q.discountAmountMinor(),
            q.payableAmountMinor(),q.lines(),q.appliedRuleIds(),explanations,q.adjustments());
    }
    private CanonicalJson.Result canonicalResult(QuoteResult value){return CanonicalJson.from(Map.of(
        "engineVersion",PromotionEngine.ENGINE_VERSION,"grossAmountMinor",value.grossAmountMinor(),
        "discountAmountMinor",value.discountAmountMinor(),"payableAmountMinor",value.payableAmountMinor(),
        "lineDiscounts",value.lineDiscounts(),"appliedRuleIds",value.appliedRuleIds()));}
    private CanonicalJson.Result canonicalRequest(MemberQuote value){
        List<Map<String,Object>> lines=value.lines().stream().map(line->{
            Map<String,Object> canonical=new LinkedHashMap<>();
            canonical.put("lineId",line.basket().lineId());
            canonical.put("lineNo",line.basket().lineNo());
            canonical.put("skuId",line.basket().skuId());
            canonical.put("unitId",line.unitId());
            canonical.put("categoryId",line.basket().categoryId()==null?"NONE":line.basket().categoryId());
            canonical.put("brandId",line.basket().brandId()==null?"NONE":line.basket().brandId());
            canonical.put("quantity",line.basket().quantity().toPlainString());
            canonical.put("unitPriceMinor",line.basket().unitPriceMinor());
            return canonical;
        }).toList();
        return CanonicalJson.from(Map.of("storeId",value.storeId(),"terminalId",value.terminalId(),"channel",
            value.channel(),"businessTime",value.businessTime().toString(),"currency",value.currency(),
            "packageVersion",value.packageVersion(),"entitlementSnapshotId",optional(value.entitlementSnapshotId()),
            "lines",lines));
    }
    private void validate(MemberQuote value){if(value==null||value.pricingRequestId()==null||!value.pricingRequestId().matches(ULID)
        ||value.storeId()==null||value.storeId()<=0||value.terminalId()==null||!value.terminalId().matches(ULID)
        ||value.channel()==null||value.channel().isBlank()||value.businessTime()==null||!"CNY".equals(value.currency())
        ||value.packageVersion()<=0||value.correlationId()==null||!value.correlationId().matches(ULID)
        ||value.lines().isEmpty()||value.lines().size()>500)throw new ServiceException("PRM-MEMBER-010: 会员权益询价无效",400);
        Set<String> lineIds=new HashSet<>();for(MemberQuoteLine line:value.lines())if(line==null||line.basket()==null
            ||line.unitId()==null||line.unitId()<=0||!lineIds.add(line.basket().lineId()))
            throw new ServiceException("PRM-MEMBER-010: 会员权益询价无效",400);}
    private String write(Object value){try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){
        throw new ServiceException("PRM-IDEMP-005: 结果无法序列化",500);}}
    private String optional(String value){return value==null?"NONE":value;}
    private LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
}
