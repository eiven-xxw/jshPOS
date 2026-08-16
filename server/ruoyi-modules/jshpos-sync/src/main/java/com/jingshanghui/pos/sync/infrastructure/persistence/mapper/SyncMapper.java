package com.jingshanghui.pos.sync.infrastructure.persistence.mapper;

import com.jingshanghui.pos.sync.application.model.SyncModels.BusinessFactRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.ChangeRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.CursorRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.InboxRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.PullPageRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SyncMapper {

    @Select("""
        SELECT device_id deviceId,tenant_id tenantId,store_id storeId,terminal_id terminalId,
          bound_user_id boundUserId,status,min_protocol_version minProtocolVersion,
          max_protocol_version maxProtocolVersion,record_version recordVersion
        FROM pos_sync_device WHERE tenant_id=#{tenantId} AND device_id=#{deviceId}
        """)
    DeviceRecord findDevice(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId);

    @Update("UPDATE pos_sync_device SET last_seen_at=#{at},record_version=record_version+1 WHERE tenant_id=#{tenantId} AND device_id=#{deviceId} AND status='ACTIVE'")
    int touchDevice(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                    @Param("at") LocalDateTime at);

    @Update("UPDATE pos_sync_device SET status='BLOCKED',blocked_reason=#{reason},record_version=record_version+1 WHERE tenant_id=#{tenantId} AND device_id=#{deviceId} AND status<>'REVOKED'")
    int blockDevice(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                    @Param("reason") String reason);

    @Select("""
        SELECT event_id eventId,tenant_id tenantId,device_id deviceId,event_type eventType,
          aggregate_id aggregateId,aggregate_version aggregateVersion,payload_sha256 payloadHash,
          CAST(payload_json AS CHAR) payloadJson,processing_status processingStatus,result_code resultCode,
          processing_attempts processingAttempts,updated_at updatedAt
        FROM pos_sync_inbox WHERE tenant_id=#{tenantId} AND event_id=#{eventId}
        """)
    InboxRecord findInbox(@Param("tenantId") String tenantId, @Param("eventId") String eventId);

    @Insert("""
        INSERT INTO pos_sync_inbox(event_id,tenant_id,device_id,batch_id,device_sequence,stream_code,
          event_type,event_version,aggregate_id,aggregate_version,idempotency_key,correlation_id,
          occurred_at,payload_json,payload_sha256,processing_status,received_at)
        VALUES(#{eventId},#{tenantId},#{deviceId},#{batchId},#{sequence},#{stream},#{eventType},
          #{eventVersion},#{aggregateId},#{aggregateVersion},#{idempotencyKey},#{correlationId},
          #{occurredAt},CAST(#{payloadJson} AS JSON),#{payloadHash},'RECEIVED',#{receivedAt})
        """)
    int insertInbox(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                    @Param("batchId") String batchId, @Param("eventId") String eventId,
                    @Param("sequence") long sequence, @Param("stream") String stream,
                    @Param("eventType") String eventType, @Param("eventVersion") int eventVersion,
                    @Param("aggregateId") String aggregateId, @Param("aggregateVersion") long aggregateVersion,
                    @Param("idempotencyKey") String idempotencyKey, @Param("correlationId") String correlationId,
                    @Param("occurredAt") LocalDateTime occurredAt, @Param("payloadJson") String payloadJson,
                    @Param("payloadHash") String payloadHash, @Param("receivedAt") LocalDateTime receivedAt);

    @Update("""
        UPDATE pos_sync_inbox SET processing_status=#{status},result_code=#{resultCode},
          processing_attempts=processing_attempts+1,processed_at=#{at}
        WHERE tenant_id=#{tenantId} AND event_id=#{eventId}
        """)
    int updateInboxResult(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
                          @Param("status") String status, @Param("resultCode") String resultCode,
                          @Param("at") LocalDateTime at);

    @Select("""
        SELECT source_event_id sourceEventId,payload_sha256 payloadHash FROM pos_sync_business_fact
        WHERE tenant_id=#{tenantId} AND aggregate_id=#{aggregateId} AND aggregate_version=#{aggregateVersion}
          AND event_type=#{eventType}
        """)
    BusinessFactRecord findBusinessFact(@Param("tenantId") String tenantId,
                                        @Param("aggregateId") String aggregateId,
                                        @Param("aggregateVersion") long aggregateVersion,
                                        @Param("eventType") String eventType);

    @Insert("""
        INSERT INTO pos_sync_business_fact(fact_id,tenant_id,source_event_id,stream_code,event_type,
          aggregate_id,aggregate_version,payload_json,payload_sha256,applied_at)
        VALUES(#{factId},#{tenantId},#{eventId},#{stream},#{eventType},#{aggregateId},
          #{aggregateVersion},CAST(#{payloadJson} AS JSON),#{payloadHash},#{at})
        """)
    int insertBusinessFact(@Param("factId") String factId, @Param("tenantId") String tenantId,
                           @Param("eventId") String eventId, @Param("stream") String stream,
                           @Param("eventType") String eventType, @Param("aggregateId") String aggregateId,
                           @Param("aggregateVersion") long aggregateVersion,
                           @Param("payloadJson") String payloadJson, @Param("payloadHash") String payloadHash,
                           @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO pos_sync_security_event(security_event_id,tenant_id,device_id,event_id,action_code,evidence_sha256,occurred_at)
        VALUES(#{securityId},#{tenantId},#{deviceId},#{eventId},#{action},#{evidenceHash},#{at})
        """)
    int insertSecurityEvent(@Param("securityId") String securityId, @Param("tenantId") String tenantId,
                            @Param("deviceId") String deviceId, @Param("eventId") String eventId,
                            @Param("action") String action, @Param("evidenceHash") String evidenceHash,
                            @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO pos_sync_dead_letter(dead_letter_id,tenant_id,event_id,failure_code,failure_summary,status,created_at)
        VALUES(#{deadLetterId},#{tenantId},#{eventId},#{failureCode},#{summary},'OPEN',#{at})
        ON DUPLICATE KEY UPDATE failure_code=VALUES(failure_code),failure_summary=VALUES(failure_summary)
        """)
    int upsertDeadLetter(@Param("deadLetterId") String deadLetterId, @Param("tenantId") String tenantId,
                         @Param("eventId") String eventId, @Param("failureCode") String failureCode,
                         @Param("summary") String summary, @Param("at") LocalDateTime at);

    @Select("""
        SELECT change_sequence changeSequence,change_id changeId,stream_code streamCode,event_type eventType,
          aggregate_id aggregateId,aggregate_version aggregateVersion,CAST(payload_json AS CHAR) payloadJson,
          payload_sha256 payloadHash,published_at publishedAt
        FROM pos_sync_change_feed WHERE tenant_id=#{tenantId} AND stream_code=#{stream} AND change_sequence>#{after}
        ORDER BY change_sequence LIMIT #{limit}
        """)
    List<ChangeRecord> findChanges(@Param("tenantId") String tenantId, @Param("stream") String stream,
                                   @Param("after") long after, @Param("limit") int limit);

    @Select("SELECT EXISTS(SELECT 1 FROM pos_sync_change_feed WHERE tenant_id=#{tenantId} AND stream_code=#{stream} AND change_sequence>#{after} LIMIT 1)")
    boolean hasChangesAfter(@Param("tenantId") String tenantId, @Param("stream") String stream,
                            @Param("after") long after);

    @Insert("""
        INSERT INTO pos_sync_change_feed(change_id,tenant_id,stream_code,event_type,aggregate_id,
          aggregate_version,payload_json,payload_sha256,published_at)
        VALUES(#{changeId},#{tenantId},#{stream},#{eventType},#{aggregateId},#{aggregateVersion},
          CAST(#{payloadJson} AS JSON),#{payloadHash},#{at})
        """)
    int insertChange(@Param("changeId") String changeId, @Param("tenantId") String tenantId,
                     @Param("stream") String stream, @Param("eventType") String eventType,
                     @Param("aggregateId") String aggregateId, @Param("aggregateVersion") long aggregateVersion,
                     @Param("payloadJson") String payloadJson, @Param("payloadHash") String payloadHash,
                     @Param("at") LocalDateTime at);

    @Insert("""
        INSERT INTO pos_sync_pull_page(cursor_token,tenant_id,device_id,stream_code,from_sequence,to_sequence,
          change_ids_json,page_sha256,status,offered_at)
        VALUES(#{cursor},#{tenantId},#{deviceId},#{stream},#{fromSequence},#{toSequence},
          CAST(#{changeIdsJson} AS JSON),#{pageHash},'OFFERED',#{at})
        """)
    int insertPullPage(@Param("cursor") String cursor, @Param("tenantId") String tenantId,
                       @Param("deviceId") String deviceId, @Param("stream") String stream,
                       @Param("fromSequence") long fromSequence, @Param("toSequence") long toSequence,
                       @Param("changeIdsJson") String changeIdsJson, @Param("pageHash") String pageHash,
                       @Param("at") LocalDateTime at);

    @Select("""
        SELECT cursor_token cursorToken,tenant_id tenantId,device_id deviceId,stream_code streamCode,
          from_sequence fromSequence,to_sequence toSequence,CAST(change_ids_json AS CHAR) changeIdsJson,
          page_sha256 pageSha256,status FROM pos_sync_pull_page
        WHERE tenant_id=#{tenantId} AND device_id=#{deviceId} AND cursor_token=#{cursor}
        """)
    PullPageRecord findPullPage(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                                @Param("cursor") String cursor);

    @Select("""
        SELECT stream_code streamCode,acked_sequence ackedSequence,acked_cursor_token ackedCursorToken,
          page_sha256 pageSha256 FROM pos_sync_cursor
        WHERE tenant_id=#{tenantId} AND device_id=#{deviceId} AND stream_code=#{stream} FOR UPDATE
        """)
    CursorRecord lockCursor(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                            @Param("stream") String stream);

    @Select("""
        SELECT stream_code streamCode,acked_sequence ackedSequence,acked_cursor_token ackedCursorToken,
          page_sha256 pageSha256 FROM pos_sync_cursor
        WHERE tenant_id=#{tenantId} AND device_id=#{deviceId}
        """)
    List<CursorRecord> listCursors(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId);

    @Insert("""
        INSERT INTO pos_sync_cursor(tenant_id,device_id,stream_code,acked_sequence,acked_cursor_token,page_sha256)
        VALUES(#{tenantId},#{deviceId},#{stream},#{sequence},#{cursor},#{pageHash})
        ON DUPLICATE KEY UPDATE acked_sequence=VALUES(acked_sequence),acked_cursor_token=VALUES(acked_cursor_token),
          page_sha256=VALUES(page_sha256)
        """)
    int upsertCursor(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                     @Param("stream") String stream, @Param("sequence") long sequence,
                     @Param("cursor") String cursor, @Param("pageHash") String pageHash);

    @Update("UPDATE pos_sync_pull_page SET status='ACKED',acked_at=#{at} WHERE tenant_id=#{tenantId} AND device_id=#{deviceId} AND cursor_token=#{cursor} AND status IN ('OFFERED','ACKED')")
    int acknowledgePullPage(@Param("tenantId") String tenantId, @Param("deviceId") String deviceId,
                            @Param("cursor") String cursor, @Param("at") LocalDateTime at);

    @Update("UPDATE pos_sync_inbox SET processing_status='RECEIVED',result_code='MANUAL_RETRY',processed_at=NULL WHERE tenant_id=#{tenantId} AND event_id=#{eventId} AND processing_status='DEAD_LETTER'")
    int reopenDeadLetter(@Param("tenantId") String tenantId, @Param("eventId") String eventId);

    @Update("UPDATE pos_sync_dead_letter SET status='RETRYING',repair_attempts=repair_attempts+1 WHERE tenant_id=#{tenantId} AND event_id=#{eventId} AND status='OPEN'")
    int markDeadLetterRetrying(@Param("tenantId") String tenantId, @Param("eventId") String eventId);
}
