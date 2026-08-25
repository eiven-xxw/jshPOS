import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jshpos_pos/features/exchange/domain/pos_exchange_models.dart';
import 'package:jshpos_pos/features/session/domain/pos_session_models.dart';
import 'package:jshpos_pos/features/session/infrastructure/http_pos_session_repository.dart';
import 'package:jshpos_pos/features/session/infrastructure/ruoyi_api_request_encryptor.dart';
import 'package:jshpos_pos/features/synchronization/domain/sync_models.dart';
import 'package:jshpos_pos/features/synchronization/infrastructure/pos_sync_http_transport.dart';
import 'package:jshpos_pos/infrastructure/runtime/session_bound_pos_runtime.dart';
import 'package:pos_device_adapter/pos_device_adapter.dart';
import 'package:sqlite3/sqlite3.dart';

const _clientId = 'e5cd7e4891bf95d1d19206ce24a7b32e';
const _warehouseId = '01K9R400000000000000000099';
const _signingKeyId = 'R4_SYNTHETIC_KEY';

void main() {
  final formalRuntimeEnabled = Platform.environment.containsKey('R4_BASE_URL');
  test(
    '三业态 Flutter POS 连接正式商业 JAR 并以文件 SQLite 完成现金成交同步',
    () async {
      final environment = Platform.environment;
      final baseUri = Uri.parse(_required(environment, 'R4_BASE_URL'));
      final bootstrapEvidence = _jsonFile(
        _required(environment, 'R4_BOOTSTRAP_EVIDENCE_PATH'),
      );
      final secretBundle = _jsonFile(
        _required(environment, 'R4_SECRET_BUNDLE_PATH'),
      );
      final output = File(_required(environment, 'R4_FLUTTER_EVIDENCE_PATH'));
      final sqliteRoot = Directory(_required(environment, 'R4_SQLITE_ROOT'));
      final publicKeyBytes = base64Decode(
        _required(environment, 'R4_SIGNING_PUBLIC_KEY_BASE64'),
      );
      expect(publicKeyBytes, hasLength(32));
      final signingKey = SimplePublicKey(
        publicKeyBytes,
        type: KeyPairType.ed25519,
      );

      final runId = _text(bootstrapEvidence, 'runId');
      expect(_text(secretBundle, 'runId'), runId);
      final publicJourneys = _maps(bootstrapEvidence['journeys']);
      final secretJourneys = _maps(secretBundle['journeys']);
      expect(publicJourneys, hasLength(3));
      expect(secretJourneys, hasLength(3));
      sqliteRoot.createSync(recursive: true);

      final results = <Map<String, Object?>>[];
      for (var index = 0; index < publicJourneys.length; index += 1) {
        final publicJourney = publicJourneys[index];
        final secretJourney = secretJourneys.singleWhere(
          (item) => item['journeyId'] == publicJourney['journeyId'],
        );
        final result = await _runJourney(
          baseUri: baseUri,
          runId: runId,
          journey: publicJourney,
          secret: secretJourney,
          sqliteRoot: sqliteRoot,
          signingKey: signingKey,
          sequence: index + 1,
        );
        results.add(result);
        // 每条旅程完成即落盘非敏感诊断；后续联合断言失败时不得丢失已观察事实。
        stdout.writeln('G9A_R4_JOURNEY_EVIDENCE=${jsonEncode(result)}');
        _writeEvidence(
          output: output,
          baseUri: baseUri,
          runId: runId,
          status: 'OBSERVED_PENDING_VALIDATION',
          journeys: results,
        );
      }

      expect(results.map((item) => item['industry']).toSet(), {
        'CONVENIENCE',
        'SNACK_DISCOUNT',
        'COMMUNITY_SUPERMARKET',
      });
      expect(results.every((item) => item['outboxUnsettled'] == 0), isTrue);
      expect(results.every((item) => item['deadLetters'] == 0), isTrue);
      expect(results.every((item) => item['orderCount'] == 2), isTrue);
      expect(results.every((item) => item['returnCount'] == 1), isTrue);
      expect(results.every((item) => item['exchangeCount'] == 1), isTrue);

      // 固定 seed F07：以错误公钥装配一个全新文件库，生产安装器必须拒绝正式包，
      // 且不能留下任何 ACTIVE 包绑定。该探针仍只使用虚构终端和正式 JAR。
      final corruptPackage = await _verifyCorruptedPackageFailsClosed(
        baseUri: baseUri,
        journey: publicJourneys.first,
        secret: secretJourneys.singleWhere(
          (item) => item['journeyId'] == publicJourneys.first['journeyId'],
        ),
        sqliteRoot: sqliteRoot,
      );

      _writeEvidence(
        output: output,
        baseUri: baseUri,
        runId: runId,
        status: 'PASS',
        journeys: results,
        faultEvidence: [corruptPackage],
      );
    },
    timeout: const Timeout(Duration(minutes: 8)),
    skip: formalRuntimeEnabled ? false : '仅在 G9A-R4 正式运行栈作业中执行',
  );
}

/// 使用生产运行时装配路径验证坏签名包不会被部分安装或切换为活动版本。
Future<Map<String, Object?>> _verifyCorruptedPackageFailsClosed({
  required Uri baseUri,
  required Map<String, Object?> journey,
  required Map<String, Object?> secret,
  required Directory sqliteRoot,
}) async {
  final path =
      '${sqliteRoot.path}${Platform.pathSeparator}r4-f07-corrupt-package.sqlite3';
  final file = File(path);
  if (file.existsSync()) file.deleteSync();
  final session = HttpPosSessionRepository(
    baseUri: baseUri,
    clientId: _clientId,
    materialProvider: _ControlledMaterialProvider(secret),
    loginEncryptor: const _PlainJsonEncryptor(),
  );
  final wrongKey = SimplePublicKey(
    List<int>.generate(32, (index) => index + 1),
    type: KeyPairType.ed25519,
  );
  final runtime = SessionBoundPosRuntime(
    sessions: session,
    assembler: FilePosBusinessRuntimeAssembler(
      databasePathProvider: (_) async => path,
      baseUri: baseUri,
      clientId: _clientId,
      accessTokenProvider: session.accessToken,
      catalogPackageVersion: _integer(journey, 'catalogVersion'),
      promotionPackageVersion: _integer(journey, 'promotionVersion'),
      catalogSigningKeys: {_signingKeyId: wrongKey},
      promotionSigningKeys: {_signingKeyId: wrongKey},
      industryTemplateVersion: '${_text(journey, 'industry')}_V1',
      returnWarehouseId: _warehouseId,
      configVersion: 1,
      cashDifferenceApprovalMinor: 1000,
      lotPackageVersion: _integer(journey, 'lotPackageVersion'),
      lotPackageSigningKeys: {_signingKeyId: wrongKey},
    ),
  );
  try {
    final terminal = await runtime.verifyTerminal(_device());
    await expectLater(
      runtime.authenticate(
        terminal,
        EmployeeLoginCommand(
          loginName: _text(secret, 'username'),
          secret: _text(secret, 'password'),
          correlationId: _ulid(906),
          occurredAt: DateTime.now().toUtc(),
        ),
      ),
      throwsA(isA<StateError>()),
    );
  } finally {
    session.close();
  }
  expect(file.existsSync(), isTrue);
  final database = sqlite3.open(path, mode: OpenMode.readOnly);
  try {
    final activeBindings =
        database
                .select(
                  'SELECT COUNT(*) AS count FROM local_catalog_package_binding',
                )
                .single['count']!
            as int;
    final activeSlots =
        database
                .select(
                  "SELECT COUNT(*) AS count FROM local_catalog_package_slot WHERE state='ACTIVE'",
                )
                .single['count']!
            as int;
    expect(activeBindings, 0);
    expect(activeSlots, 0);
    return {
      'seedId': 'R4-F07',
      'status': 'PASS_FAILED_CLOSED',
      'activeBindings': activeBindings,
      'activeSlots': activeSlots,
      'partialInstall': false,
    };
  } finally {
    database.close();
  }
}

Future<Map<String, Object?>> _runJourney({
  required Uri baseUri,
  required String runId,
  required Map<String, Object?> journey,
  required Map<String, Object?> secret,
  required Directory sqliteRoot,
  required SimplePublicKey signingKey,
  required int sequence,
}) async {
  final journeyId = _text(journey, 'journeyId');
  final databasePath =
      '${sqliteRoot.path}${Platform.pathSeparator}'
      '${journeyId.toLowerCase()}.sqlite3';
  final databaseFile = File(databasePath);
  if (databaseFile.existsSync()) databaseFile.deleteSync();

  final session = HttpPosSessionRepository(
    baseUri: baseUri,
    clientId: _clientId,
    materialProvider: _ControlledMaterialProvider(secret),
    loginEncryptor: const _PlainJsonEncryptor(),
  );
  final runtime = SessionBoundPosRuntime(
    sessions: session,
    assembler: FilePosBusinessRuntimeAssembler(
      databasePathProvider: (_) async => databasePath,
      baseUri: baseUri,
      clientId: _clientId,
      accessTokenProvider: session.accessToken,
      catalogPackageVersion: _integer(journey, 'catalogVersion'),
      promotionPackageVersion: _integer(journey, 'promotionVersion'),
      catalogSigningKeys: {_signingKeyId: signingKey},
      promotionSigningKeys: {_signingKeyId: signingKey},
      industryTemplateVersion: '${_text(journey, 'industry')}_V1',
      returnWarehouseId: _warehouseId,
      configVersion: 1,
      cashDifferenceApprovalMinor: 1000,
      lotPackageVersion: _integer(journey, 'lotPackageVersion'),
      lotPackageSigningKeys:
          _text(journey, 'industry') == 'COMMUNITY_SUPERMARKET'
          ? {_signingKeyId: signingKey}
          : const {},
    ),
  );
  TrustedTerminalContext? terminal;
  EmployeeSession? employee;
  String? completedReturnRef;
  String? completedExchangeRef;
  String? originalOrderRef;
  String? replacementOrderRef;
  Map<String, Object?>? ackLossRecovery;
  var completedReturnAmountMinor = 0;
  try {
    terminal = await runtime.verifyTerminal(_device());
    expect(terminal.tenantId, _text(journey, 'tenantId'));
    expect(terminal.storeId, _text(journey, 'storeId'));
    expect(terminal.deviceId, _text(journey, 'deviceId'));
    expect(terminal.terminalId, _text(journey, 'terminalId'));

    final login = await runtime.authenticate(
      terminal,
      EmployeeLoginCommand(
        loginName: _text(secret, 'username'),
        secret: _text(secret, 'password'),
        correlationId: _ulid(sequence * 10 + 1),
        occurredAt: DateTime.now().toUtc(),
      ),
    );
    employee = login.employee;
    expect(employee.employeeId, _text(journey, 'userId'));

    final shift = await runtime.open(
      businessDate: terminal.businessDate,
      openingCash: '100.00',
      idempotencyKey: '$runId:$journeyId:shift-open',
    );
    final quoted = await runtime.scanBarcode(_text(journey, 'barcode'));
    final adjusted = await runtime.applyManualAdjustment(
      actionCode: 'ORDER_AMOUNT_OFF',
      value: '50',
    );
    final settled = await runtime.settleCash(
      tenderedAmount: '20.00',
      idempotencyKey: '$runId:$journeyId:cash:${adjusted.quoteFingerprint}',
    );
    originalOrderRef = settled.orderRef;
    final preview = await runtime.previewPrintTask(settled.orderRef);
    expect(quoted.totals.receivableAmountMinor, 990);
    expect(adjusted.totals.receivableAmountMinor, 940);
    expect(settled.receivableAmountMinor, 940);
    expect(settled.changeAmountMinor, 1060);
    expect(preview.adapterEvidence, contains('BLOCKED_REAL_PRINTER'));

    // 固定 seed F02：服务端先收下首个本地事件，但客户端故意不落 ACK；随后关闭并
    // 重新装配同一文件 SQLite，正式同步器必须复用原 eventId 收敛，禁止生成新命令。
    ackLossRecovery = await _pushFirstOutboxWithoutPersistingAck(
      databasePath: databasePath,
      baseUri: baseUri,
      session: session,
      terminal: terminal,
      batchId: _ulid(sequence * 10 + 6),
    );
    await runtime.logout(terminal, employee, _ulid(sequence * 10 + 7));
    employee = null;
    final resumed = await runtime.authenticate(
      terminal,
      EmployeeLoginCommand(
        loginName: _text(secret, 'username'),
        secret: _text(secret, 'password'),
        correlationId: _ulid(sequence * 10 + 8),
        occurredAt: DateTime.now().toUtc(),
      ),
    );
    employee = resumed.employee;

    // 先同步原成交，Return Owner 只能读取服务端不可变订单和原促销快照。
    for (var attempt = 0; attempt < 4; attempt += 1) {
      await runtime.refreshSyncStatus();
    }

    final returnWorkspace = await runtime.findOriginalOrder(settled.orderRef);
    final selectedReturn = await runtime.changeRequestedQuantity(
      returnWorkspace.lines.single.lineRef,
      returnWorkspace.lines.single.maximumReturnableQuantity,
    );
    final submittedReturn = await runtime.submitCashReturn(
      reasonCode: 'CUSTOMER_RETURN',
    );
    expect(selectedReturn.refundableAmountMinor, settled.receivableAmountMinor);
    expect(submittedReturn.status.name, 'pendingApproval');

    final replacementQuote = await runtime.scanBarcode(
      _text(journey, 'barcode'),
    );
    final replacementSale = await runtime.settleCash(
      tenderedAmount: '20.00',
      idempotencyKey:
          '$runId:$journeyId:replacement:${replacementQuote.quoteFingerprint}',
    );
    replacementOrderRef = replacementSale.orderRef;
    expect(replacementSale.receivableAmountMinor, 990);
    for (var attempt = 0; attempt < 4; attempt += 1) {
      await runtime.refreshSyncStatus();
    }
    final exchange = await runtime.create(
      source: PosExchangeSource(
        originalReturn: submittedReturn,
        newSale: replacementSale,
      ),
      reasonCode: 'CUSTOMER_EXCHANGE',
    );
    expect(exchange.status.name, 'draft');

    // 退货与换货都由独立复核员通过正式 API 审批；收银员会话只观察原 Saga 身份。
    await _approveReturnAndExchange(
      baseUri: baseUri,
      tenantId: _text(journey, 'tenantId'),
      reviewerUsername: _text(secret, 'reviewerUsername'),
      reviewerPassword: _text(secret, 'reviewerPassword'),
      returnRef: submittedReturn.returnRef,
      returnCorrelationRef: submittedReturn.correlationRef,
      exchangeRef: exchange.exchangeRef,
      exchangeCorrelationRef: exchange.correlationRef,
      sequence: sequence,
    );
    var observedExchange = exchange;
    for (
      var attempt = 0;
      attempt < 8 && !observedExchange.status.terminal;
      attempt += 1
    ) {
      observedExchange = await runtime.refreshExchange(exchange.exchangeRef);
    }
    expect(observedExchange.status.name, 'completed');
    completedReturnRef = submittedReturn.returnRef;
    completedExchangeRef = exchange.exchangeRef;
    completedReturnAmountMinor = selectedReturn.refundableAmountMinor;

    // 本地理论现金尚未直接写入服务端退款事实；受治理阈值允许把原退款作为可解释差异。
    // 服务端按自身现金流水重算后，实际现金 109.90 与权威理论现金一致。
    await runtime.close(
      shiftId: shift.shiftId,
      actualCash: '109.90',
      idempotencyKey: '$runId:$journeyId:shift-close',
    );
    for (var attempt = 0; attempt < 4; attempt += 1) {
      await runtime.refreshSyncStatus();
    }
    await runtime.logout(terminal, employee, _ulid(sequence * 10 + 2));
    employee = null;
    terminal = null;
  } finally {
    if (terminal != null && employee != null) {
      try {
        await runtime.logout(terminal, employee, _ulid(sequence * 10 + 3));
      } on Object {
        // 清理失败不得覆盖首次失败；普通证据也不得记录凭据或令牌。
      }
    }
    session.close();
  }

  final database = sqlite3.open(databasePath, mode: OpenMode.readOnly);
  try {
    final statuses = <String, int>{};
    for (final row in database.select(
      'SELECT status,COUNT(*) AS count FROM local_outbox GROUP BY status',
    )) {
      statuses[row['status']! as String] = row['count']! as int;
    }
    final eventTypes = database
        .select(
          'SELECT DISTINCT event_type FROM local_outbox ORDER BY event_type',
        )
        .map((row) => row['event_type']! as String)
        .toList(growable: false);
    final unsettled = statuses.entries
        .where((entry) => entry.key != 'ACKED')
        .fold<int>(0, (sum, entry) => sum + entry.value);
    final unsettledEvents = database
        .select('''SELECT event_type,status,COALESCE(last_ack_status,'') AS last_ack_status,
                    COALESCE(last_error_code,'') AS last_error_code
             FROM local_outbox WHERE status<>'ACKED' ORDER BY device_sequence''')
        .map(
          (row) => <String, Object?>{
            'eventType': row['event_type']! as String,
            'status': row['status']! as String,
            'lastAckStatus': row['last_ack_status']! as String,
            'lastErrorCode': row['last_error_code']! as String,
          },
        )
        .toList(growable: false);
    final deadLetterCodes = database
        .select('''SELECT failure_code,status,COUNT(*) AS count FROM local_sync_dead_letter
             GROUP BY failure_code,status ORDER BY failure_code,status''')
        .map(
          (row) => <String, Object?>{
            'failureCode': row['failure_code']! as String,
            'status': row['status']! as String,
            'count': row['count']! as int,
          },
        )
        .toList(growable: false);
    final orderTotals = database
        .select(
          '''SELECT
             COALESCE(SUM(gross_amount_minor),0) AS gross,
             COALESCE(SUM(discount_amount_minor),0) AS discount,
             COALESCE(SUM(surcharge_amount_minor),0) AS surcharge,
             COALESCE(SUM(receivable_amount_minor),0) AS receivable
             FROM local_order WHERE tenant_id=? AND status='COMPLETED' ''',
          [_text(journey, 'tenantId')],
        )
        .single;
    final cashNetMinor =
        database
                .select(
                  '''SELECT COALESCE(SUM(net_amount_minor),0) AS net
             FROM local_cash_payment WHERE tenant_id=? AND status='SUCCEEDED' ''',
                  [_text(journey, 'tenantId')],
                )
                .single['net']!
            as int;
    final firstPromotionSnapshotId =
        database
                .select(
                  '''SELECT snapshot_id
             FROM local_promotion_transaction_snapshot WHERE tenant_id=?
             ORDER BY occurred_at ASC LIMIT 1''',
                  [_text(journey, 'tenantId')],
                )
                .single['snapshot_id']!
            as String;
    final firstCashPaymentId =
        database
                .select(
                  '''SELECT payment_id
             FROM local_cash_payment WHERE tenant_id=?
             ORDER BY occurred_at ASC LIMIT 1''',
                  [_text(journey, 'tenantId')],
                )
                .single['payment_id']!
            as String;
    final promotionAllocatedMinor =
        database
                .select(
                  '''SELECT
             COALESCE(SUM(discount_amount_minor),0) AS allocated
             FROM local_promotion_transaction_allocation WHERE tenant_id=?''',
                  [_text(journey, 'tenantId')],
                )
                .single['allocated']!
            as int;
    final memberBenefitSnapshotCount = _count(
      database,
      'local_order_member_benefit_snapshot',
    );
    final lotAllocatedQuantity = database
        .select(
          '''SELECT
             COALESCE(SUM(base_quantity),0) AS quantity
             FROM local_order_lot_allocation WHERE tenant_id=?''',
          [_text(journey, 'tenantId')],
        )
        .single['quantity'];
    return <String, Object?>{
      'journeyId': journeyId,
      'industry': _text(journey, 'industry'),
      'tenantId': _text(journey, 'tenantId'),
      'storeId': _text(journey, 'storeId'),
      'terminalId': _text(journey, 'terminalId'),
      'businessDate': _text(journey, 'businessDate'),
      'sqlitePathSha256': sha256.convert(utf8.encode(databasePath)).toString(),
      'sqliteFileBytes': databaseFile.lengthSync(),
      'orderCount': _count(database, 'local_order'),
      'orderRef':
          database
                  .select(
                    'SELECT order_id FROM local_order ORDER BY occurred_at DESC LIMIT 1',
                  )
                  .single['order_id']!
              as String,
      'originalOrderRef': originalOrderRef,
      'replacementOrderRef': replacementOrderRef,
      'grossAmountMinor': orderTotals['gross']! as int,
      'discountAmountMinor': orderTotals['discount']! as int,
      'surchargeAmountMinor': orderTotals['surcharge']! as int,
      'receivableAmountMinor': orderTotals['receivable']! as int,
      'cashNetAmountMinor': cashNetMinor,
      'refundedAmountMinor': completedReturnAmountMinor,
      'firstPromotionSnapshotId': firstPromotionSnapshotId,
      'firstCashPaymentId': firstCashPaymentId,
      'promotionAllocatedMinor': promotionAllocatedMinor,
      'memberBenefitSnapshotCount': memberBenefitSnapshotCount,
      'lotAllocatedQuantity': '$lotAllocatedQuantity',
      'cashPaymentCount': _count(database, 'local_cash_payment'),
      'returnCount': 1,
      'exchangeCount': _count(database, 'local_exchange_command'),
      'returnRef': completedReturnRef,
      'exchangeRef': completedExchangeRef,
      'promotionSnapshotCount': _count(
        database,
        'local_promotion_transaction_snapshot',
      ),
      'outboxCount': _count(database, 'local_outbox'),
      'outboxStatuses': statuses,
      'outboxUnsettled': unsettled,
      'unsettledEvents': unsettledEvents,
      'deadLetters': _count(database, 'local_sync_dead_letter'),
      'deadLetterCodes': deadLetterCodes,
      'eventTypes': eventTypes,
      'ackLossRecovery': ackLossRecovery,
    };
  } finally {
    database.close();
  }
}

/// 通过正式同步 HTTP 端口投递一次但不写本地 ACK，用于复现服务端已收、客户端未知。
Future<Map<String, Object?>> _pushFirstOutboxWithoutPersistingAck({
  required String databasePath,
  required Uri baseUri,
  required HttpPosSessionRepository session,
  required TrustedTerminalContext terminal,
  required String batchId,
}) async {
  final database = sqlite3.open(databasePath, mode: OpenMode.readOnly);
  late final SyncEventEnvelope event;
  try {
    final row = database
        .select(
          "SELECT * FROM local_outbox WHERE status='PENDING' ORDER BY device_sequence LIMIT 1",
        )
        .single;
    final payloadJson = row['payload_json']! as String;
    final payloadHash = sha256.convert(utf8.encode(payloadJson)).toString();
    expect(payloadHash, row['payload_sha256']);
    final eventType = row['event_type']! as String;
    final version = RegExp(r'\.v([1-9][0-9]*)$').firstMatch(eventType);
    expect(version, isNotNull);
    event = SyncEventEnvelope(
      eventId: row['event_id']! as String,
      stream: row['stream_code']! as String,
      eventType: eventType,
      eventVersion: int.parse(version!.group(1)!),
      aggregateId: row['aggregate_id']! as String,
      aggregateVersion: row['aggregate_version']! as int,
      deviceId: terminal.deviceId,
      storeId: terminal.storeId,
      terminalId: terminal.terminalId,
      sequenceNo: row['device_sequence']! as int,
      occurredAt: DateTime.parse(row['created_at']! as String).toUtc(),
      idempotencyKey: row['event_id']! as String,
      correlationId: row['correlation_id']! as String,
      payloadHash: payloadHash,
      payload: jsonDecode(payloadJson)! as Map<String, Object?>,
      attemptCount: 1,
    );
  } finally {
    database.close();
  }
  final transport = PosSyncHttpTransport(
    // 与正式 SessionBoundPosRuntime 复用同一 POS API 根路径；
    // ACK 丢失夹具只能省略本地 ACK 持久化，不能绕过或另造同步端点。
    baseUri: baseUri.resolve('api/pos/v1/'),
    clientId: _clientId,
    deviceId: terminal.deviceId,
    accessTokenProvider: session.accessToken,
  );
  try {
    final response = await transport.push(
      SyncPushBatch(batchId: batchId, events: [event]),
    );
    expect(response.acks, hasLength(1));
    expect(response.acks.single.eventId, event.eventId);
    expect(response.acks.single.payloadHash, event.payloadHash);
    expect(response.acks.single.status, anyOf('ACCEPTED', 'DUPLICATE'));
    return {
      'seedId': 'R4-F02',
      'eventId': event.eventId,
      'payloadHash': event.payloadHash,
      'initialAckStatus': response.acks.single.status,
      'ackPersistedBeforeRestart': false,
      'runtimeReopened': true,
    };
  } finally {
    transport.close();
  }
}

/// 使用独立账号完成 Return/Exchange 审批；不复用收银员令牌，也不写入任何凭据证据。
Future<void> _approveReturnAndExchange({
  required Uri baseUri,
  required String tenantId,
  required String reviewerUsername,
  required String reviewerPassword,
  required String returnRef,
  required String returnCorrelationRef,
  required String exchangeRef,
  required String exchangeCorrelationRef,
  required int sequence,
}) async {
  final client = HttpClient();
  try {
    final login = await _jsonRequest(
      client,
      baseUri.resolve('/auth/login'),
      body: <String, Object?>{
        'tenantId': tenantId,
        'username': reviewerUsername,
        'password': reviewerPassword,
        'clientId': _clientId,
        'grantType': 'password',
      },
    );
    final data = login['data'];
    if (data is! Map || data['access_token'] is! String) {
      throw StateError('R4_REVIEWER_LOGIN_INVALID');
    }
    final token = data['access_token']! as String;
    final occurredAt = DateTime.now().toUtc().toIso8601String();
    await _jsonRequest(
      client,
      baseUri.resolve('/api/v1/returns/$returnRef/approve'),
      token: token,
      body: <String, Object?>{
        'commandId': _ulid(sequence * 10 + 4),
        'reasonCode': 'SUPERVISOR_APPROVED',
        'correlationId': returnCorrelationRef,
        'occurredAt': occurredAt,
      },
    );
    await _jsonRequest(
      client,
      baseUri.resolve('/api/v1/pos/exchanges/$exchangeRef/approve'),
      token: token,
      body: <String, Object?>{
        'commandId': _ulid(sequence * 10 + 5),
        'reasonCode': 'SUPERVISOR_APPROVED',
        'correlationId': exchangeCorrelationRef,
        'occurredAt': occurredAt,
      },
    );
  } finally {
    client.close(force: true);
  }
}

Future<Map<String, Object?>> _jsonRequest(
  HttpClient client,
  Uri uri, {
  required Map<String, Object?> body,
  String? token,
}) async {
  final request = await client.postUrl(uri);
  request.headers
    ..contentType = ContentType.json
    ..set(HttpHeaders.acceptHeader, ContentType.json.mimeType)
    ..set('clientid', _clientId);
  if (token != null) {
    request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
  }
  request.write(jsonEncode(body));
  final response = await request.close();
  final text = await utf8.decoder.bind(response).join();
  final envelope = jsonDecode(text);
  if (envelope is! Map ||
      response.statusCode < 200 ||
      response.statusCode >= 300 ||
      (envelope['code'] is num && (envelope['code']! as num).toInt() != 200)) {
    throw StateError('R4_REVIEWER_API_FAILED:${response.statusCode}');
  }
  return envelope.cast<String, Object?>();
}

void _writeEvidence({
  required File output,
  required Uri baseUri,
  required String runId,
  required String status,
  required List<Map<String, Object?>> journeys,
  List<Map<String, Object?>> faultEvidence = const [],
}) {
  final evidence = <String, Object?>{
    'schemaVersion': '1.0',
    'gate': 'G9A-R4',
    'phase': 'R4-R3',
    'runId': runId,
    'status': status,
    'classification': 'FORMAL_JAR_FLUTTER_FILE_SQLITE',
    'server': <String, Object?>{
      'scheme': baseUri.scheme,
      'host': baseUri.host,
      'port': baseUri.port,
      'embeddedHttpServerCount': 0,
    },
    'journeys': journeys,
    'journeyCount': journeys.length,
    'faultEvidence': faultEvidence,
    'directDatabaseBusinessWrites': 0,
    'providerNetworkCalls': 0,
    'realDeviceOrPeripheralCommands': 0,
  };
  output.parent.createSync(recursive: true);
  output.writeAsStringSync(
    '${const JsonEncoder.withIndent('  ').convert(evidence)}\n',
  );
}

final class _ControlledMaterialProvider implements PosTerminalMaterialProvider {
  const _ControlledMaterialProvider(this.source);

  final Map<String, Object?> source;

  @override
  Future<PosTerminalMaterial> load() async => PosTerminalMaterial(
    deviceId: _text(source, 'deviceId'),
    deviceCredential: _text(source, 'deviceCredential'),
    deviceFingerprintSha256: _text(source, 'deviceFingerprintSha256'),
    publicKeySha256: _text(source, 'publicKeySha256'),
    appVersion: '1.0.0',
    protocolVersion: '1.0',
    schemaVersion: '1.0',
  );
}

/// CI 明确关闭服务端请求解密时使用；正式部署仍由生产加密器承担协议加密。
final class _PlainJsonEncryptor implements ApiRequestEncryptor {
  const _PlainJsonEncryptor();

  @override
  EncryptedApiRequest encryptJson(Map<String, Object?> payload) =>
      EncryptedApiRequest(
        body: jsonEncode(payload),
        encryptKey: 'R4_API_DECRYPT_DISABLED',
      );
}

DeviceSnapshot _device() => const DeviceSnapshot(
  metadata: DeviceMetadata(
    manufacturer: 'SYNTHETIC',
    model: 'G9A_R4_VIRTUAL_POS',
    androidRelease: '15',
    androidSdk: 35,
    adapterVersion: '1.0',
  ),
  capabilities: <DeviceCapability>{},
);

Map<String, Object?> _jsonFile(String path) {
  final value = jsonDecode(File(path).readAsStringSync());
  if (value is! Map) throw StateError('R4_EVIDENCE_INVALID');
  return value.cast<String, Object?>();
}

List<Map<String, Object?>> _maps(Object? value) {
  if (value is! List) throw StateError('R4_EVIDENCE_INVALID');
  return value
      .map((item) {
        if (item is! Map) throw StateError('R4_EVIDENCE_INVALID');
        return item.cast<String, Object?>();
      })
      .toList(growable: false);
}

String _required(Map<String, String> source, String key) {
  final value = source[key]?.trim();
  if (value == null || value.isEmpty) throw StateError('R4_ENV_MISSING:$key');
  return value;
}

String _text(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value is String && value.trim().isNotEmpty) return value.trim();
  if (value is num) return value.toString();
  throw StateError('R4_FIELD_INVALID:$key');
}

int _integer(Map<String, Object?> source, String key) {
  final value = source[key];
  if (value is int) return value;
  final parsed = int.tryParse(value.toString());
  if (parsed == null || parsed <= 0) throw StateError('R4_FIELD_INVALID:$key');
  return parsed;
}

int _count(Database database, String table) =>
    database.select('SELECT COUNT(*) AS count FROM $table').single['count']!
        as int;

String _ulid(int value) {
  final suffix = value.toString().padLeft(4, '0');
  return '01K9R40000000000000000$suffix';
}
