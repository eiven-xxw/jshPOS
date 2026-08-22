import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:sqlite3/sqlite3.dart';

import '../../../infrastructure/local_database/pos_local_database.dart';
import '../../catalog/domain/lot_expiry.dart';
import '../domain/checkout_models.dart';
import '../domain/ulid_generator.dart';

/// POS 结算事务中冻结的批次快照；与订单、现金和 Outbox 同事务提交。
final class LotCheckoutSnapshot {
  const LotCheckoutSnapshot({
    required this.packageVersion,
    required this.warehouseId,
    required this.payload,
    required this.payloadSha256,
  });

  final int packageVersion;
  final String warehouseId;
  final Map<String, Object?> payload;
  final String payloadSha256;
}

/// 从已验签活动包按 FEFO 扣减本地批次投影并冻结不可变成交分配。
final class LotCheckoutAllocator {
  const LotCheckoutAllocator({required this.database, required this.ulids});

  final PosLocalDatabase database;
  final UlidGenerator ulids;
  Database get _db => database.database;

  LotCheckoutSnapshot? freezeSale({
    required Basket basket,
    required String businessDate,
    required String industryTemplateVersion,
    required String commandId,
    required String occurredAt,
  }) {
    final community = industryTemplateVersion.toUpperCase().startsWith(
      'COMMUNITY_SUPERMARKET',
    );
    final bindingRows = _db.select(
      '''SELECT b.active_package_version,b.active_payload_sha256,s.warehouse_id,s.industry
         FROM local_lot_package_binding b JOIN local_lot_package_slot s
           ON s.tenant_id=b.tenant_id AND s.store_id=b.store_id
          AND s.package_version=b.active_package_version
         WHERE b.singleton_id=1 AND b.tenant_id=? AND b.store_id=? AND s.state='ACTIVE' ''',
      [database.binding.tenantId, database.binding.storeId],
    );
    if (!community) {
      if (bindingRows.isNotEmpty) {
        throw StateError(
          'LOT-TEMPLATE-101: non-community template cannot activate lot path',
        );
      }
      return null;
    }
    if (bindingRows.length != 1 ||
        bindingRows.single['industry'] != 'COMMUNITY_SUPERMARKET') {
      throw StateError(
        'LOT-DPK-124: community store requires one trusted lot package',
      );
    }
    final packageVersion = bindingRows.single['active_package_version']! as int;
    final warehouseId = bindingRows.single['warehouse_id']! as String;
    final businessDay = _parseDate(businessDate, 'businessDate');
    final frozen = <Map<String, Object?>>[];
    for (final line in basket.lines) {
      final policies = _db.select(
        '''SELECT * FROM local_lot_policy WHERE tenant_id=? AND store_id=? AND package_version=?
           AND sku_id=?''',
        [
          database.binding.tenantId,
          database.binding.storeId,
          packageVersion,
          line.quote.skuId,
        ],
      );
      if (policies.isEmpty) continue;
      if (policies.length != 1) {
        throw StateError('LOT-POLICY-101: ambiguous local lot policy');
      }
      final policy = policies.single;
      if (policy['enabled'] != 1) continue;
      final rows = _db.select(
        '''SELECT * FROM local_lot_balance WHERE tenant_id=? AND store_id=? AND package_version=?
           AND warehouse_id=? AND sku_id=? AND base_unit_id=? AND expiry_date>=?
           ORDER BY expiry_date,received_date,lot_id LIMIT 100''',
        [
          database.binding.tenantId,
          database.binding.storeId,
          packageVersion,
          warehouseId,
          line.quote.skuId,
          line.quote.unitId,
          businessDate,
        ],
      );
      final selected = LocalLotRules.allocateFefo(
        candidates: rows
            .map(
              (row) => LocalLotCandidate(
                lotId: row['lot_id']! as String,
                receivedDate: _parseDate(
                  row['received_date']! as String,
                  'receivedDate',
                ),
                expiryDate: _parseDate(
                  row['expiry_date']! as String,
                  'expiryDate',
                ),
                available: ExactLotQuantity.parse(
                  row['quantity_decimal']! as String,
                  allowZero: true,
                ),
                policyVersionId: row['policy_version_id']! as String,
              ),
            )
            .toList(growable: false),
        requested: ExactLotQuantity.parse(line.quantity.canonical),
        businessDate: businessDay,
      );
      for (final allocation in selected) {
        final currentRows = _db.select(
          '''SELECT * FROM local_lot_balance WHERE tenant_id=? AND store_id=? AND package_version=?
             AND lot_id=?''',
          [
            database.binding.tenantId,
            database.binding.storeId,
            packageVersion,
            allocation.lotId,
          ],
        );
        if (currentRows.length != 1) {
          throw StateError('LOT-BALANCE-102: selected lot disappeared');
        }
        final current = currentRows.single;
        final before = ExactLotQuantity.parse(
          current['quantity_decimal']! as String,
          allowZero: true,
        );
        final after = before - allocation.quantity;
        final sequence = (current['last_ledger_sequence']! as int) + 1;
        final version = current['record_version']! as int;
        _db.execute(
          '''UPDATE local_lot_balance SET quantity_decimal=?,last_ledger_sequence=?,record_version=record_version+1
             WHERE tenant_id=? AND store_id=? AND package_version=? AND lot_id=?
               AND record_version=? AND quantity_decimal=?''',
          [
            after.canonical,
            sequence,
            database.binding.tenantId,
            database.binding.storeId,
            packageVersion,
            allocation.lotId,
            version,
            before.canonical,
          ],
        );
        if (_db.updatedRows != 1) {
          throw StateError('LOT-BALANCE-103: local lot version conflict');
        }
        final allocationBody = <String, Object?>{
          'orderId': basket.orderId,
          'orderLineId': line.lineId,
          'skuId': line.quote.skuId,
          'baseUnitId': line.quote.unitId,
          'lotId': allocation.lotId,
          'quantity': allocation.quantity.canonical,
          'policyVersionId': allocation.policyVersionId,
          'expiryDate': _date(allocation.expiryDate),
          'businessDate': businessDate,
          'packageVersion': packageVersion,
        };
        final contentHash = sha256
            .convert(utf8.encode(jsonEncode(allocationBody)))
            .toString();
        _db.execute(
          '''INSERT INTO local_order_lot_allocation(allocation_id,tenant_id,store_id,package_version,
             order_id,order_line_id,lot_id,sku_id,base_unit_id,quantity_decimal,policy_version_id,
             expiry_date,business_date,content_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
          [
            ulids.next(),
            database.binding.tenantId,
            database.binding.storeId,
            packageVersion,
            basket.orderId,
            line.lineId,
            allocation.lotId,
            line.quote.skuId,
            line.quote.unitId,
            allocation.quantity.canonical,
            allocation.policyVersionId,
            _date(allocation.expiryDate),
            businessDate,
            contentHash,
            occurredAt,
          ],
        );
        _db.execute(
          '''INSERT INTO local_lot_ledger(ledger_id,tenant_id,store_id,package_version,lot_id,
             ledger_sequence,quantity_before,quantity_delta,quantity_after,movement_type,order_id,
             order_line_id,command_id,business_date,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,'SALE_OUT',?,?,?,?,?)''',
          [
            ulids.next(),
            database.binding.tenantId,
            database.binding.storeId,
            packageVersion,
            allocation.lotId,
            sequence,
            before.canonical,
            '-${allocation.quantity.canonical}',
            after.canonical,
            basket.orderId,
            line.lineId,
            commandId,
            businessDate,
            occurredAt,
          ],
        );
        frozen.add(allocationBody);
      }
    }
    final payload = <String, Object?>{
      'schemaVersion': '1.0',
      'orderId': basket.orderId,
      'storeId': database.binding.storeId,
      'terminalId': database.binding.terminalId,
      'warehouseId': warehouseId,
      'businessDate': businessDate,
      'packageVersion': packageVersion,
      'allocations': frozen,
    };
    final payloadJson = jsonEncode(payload);
    final payloadHash = sha256.convert(utf8.encode(payloadJson)).toString();
    _db.execute(
      '''INSERT INTO local_order_lot_snapshot(order_id,tenant_id,package_version,payload_json,
         payload_sha256,created_at) VALUES(?,?,?,?,?,?)''',
      [
        basket.orderId,
        database.binding.tenantId,
        packageVersion,
        payloadJson,
        payloadHash,
        occurredAt,
      ],
    );
    database.checkpoint('lot.sale.snapshot');
    return LotCheckoutSnapshot(
      packageVersion: packageVersion,
      warehouseId: warehouseId,
      payload: payload,
      payloadSha256: payloadHash,
    );
  }

  static DateTime _parseDate(String value, String field) {
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value)) {
      throw StateError('LOT-DATE-102: $field is invalid');
    }
    final parsed = DateTime.tryParse('${value}T00:00:00Z');
    if (parsed == null || _date(parsed) != value) {
      throw StateError('LOT-DATE-102: $field is invalid');
    }
    return parsed;
  }

  static String _date(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';
}
