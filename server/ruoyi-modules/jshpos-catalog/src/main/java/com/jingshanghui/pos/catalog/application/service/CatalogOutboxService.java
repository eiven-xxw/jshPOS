package com.jingshanghui.pos.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/** 与领域写入同事务追加标准事件；投递器属于后续 Gate，本 Gate 不开放网络出口。 */
@Service
@RequiredArgsConstructor
public class CatalogOutboxService {

    private final CatalogMapper mapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String tenantId, String eventType, String aggregateType, Long aggregateId,
                       long aggregateVersion, String payloadJson) {
        mapper.insertOutbox(tenantId, IdWorker.getId(), eventType, aggregateType, aggregateId, aggregateVersion,
            payloadJson, sha256(payloadJson), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
