package com.jingshanghui.pos.catalog.infrastructure.exception;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import com.jingshanghui.pos.catalog.application.service.CatalogPackageService;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Catalog/DataPackage Owner 自验元数据、对象与摘要；不向异常中心暴露对象键或签名材料。 */
@Component @RequiredArgsConstructor
public class CatalogPackageExceptionOwnerAdapter implements OperationsExceptionOwnerPort {
    private final CatalogPackageService packages; private final ScopeAuthorizationService authorization; private final Clock clock;
    @Override public String ownerCode(){return "DATA_PACKAGE";}
    @Override public List<OwnerObservation> scan(Long storeId, LocalDate businessDate, int limit){
        authorization.requireStoreAccess(storeId);
        PackageView latest=null; String type;
        try { latest=packages.latest(storeId); packages.download(storeId,latest.packageVersion()); return List.of(); }
        catch(RuntimeException failure){ type=latest==null?"DATA_PACKAGE_MISSING":"DATA_PACKAGE_UNAVAILABLE_OR_CORRUPT"; }
        long version=latest==null?0:latest.packageVersion(); String metadataHash=latest==null?"NONE":latest.payloadSha256();
        String hash=CanonicalJson.from(Map.of("storeId",storeId,"type",type,"version",version,"metadataHash",metadataHash)).sha256();
        return List.of(new OwnerObservation(type,"store-"+storeId+"-package",type+"-"+hash.substring(0,24),version,hash,
            "store-"+storeId+"-package",type.endsWith("CORRUPT")?"P0":"P1","dpk-"+hash.substring(0,24),
            LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),"数据包不可验真；未暴露对象键或签名材料","REPUBLISH_NEXT_PACKAGE_VERSION"));
    }
    @Override public OwnerRepairResult repair(OwnerRepairCommand c){return new OwnerRepairResult("UNAVAILABLE",c.sourceFactId(),null,
        "必须由Catalog Owner按下一版本发布并通过KMS/对象存储验真；异常中心未直接重写已发布数据包");}
}
