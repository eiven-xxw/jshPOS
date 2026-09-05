-- T2-LOC-001：仅通过前向迁移移除商业 V1 MySQL 业务表外键。
-- 历史 V1—V89 不可修改；主键、唯一约束、CHECK 与既有支撑索引全部保留。
-- 引用完整性由各 Owner 应用服务在可信 tenant_id 范围内校验，并由一致性审计持续发现孤儿引用。

ALTER TABLE `bak_backup_object`
  DROP FOREIGN KEY `fk_bak_object_set`;

ALTER TABLE `bak_restore_check`
  DROP FOREIGN KEY `fk_bak_check_drill`;

ALTER TABLE `bak_restore_drill`
  DROP FOREIGN KEY `fk_bak_drill_set`;

ALTER TABLE `cat_barcode`
  DROP FOREIGN KEY `fk_cat_barcode_sku`,
  DROP FOREIGN KEY `fk_cat_barcode_unit`;

ALTER TABLE `cat_catalog_binding`
  DROP FOREIGN KEY `fk_cat_binding_current`,
  DROP FOREIGN KEY `fk_cat_binding_previous`;

ALTER TABLE `cat_category`
  DROP FOREIGN KEY `fk_cat_category_parent`;

ALTER TABLE `cat_import_batch`
  DROP FOREIGN KEY `fk_cat_import_previous`;

ALTER TABLE `cat_import_error`
  DROP FOREIGN KEY `fk_cat_import_error_batch`;

ALTER TABLE `cat_import_record`
  DROP FOREIGN KEY `fk_cat_import_record_batch`;

ALTER TABLE `cat_lot_policy_version`
  DROP FOREIGN KEY `fk_cat_lot_policy_sku`,
  DROP FOREIGN KEY `fk_cat_lot_policy_store`,
  DROP FOREIGN KEY `fk_cat_lot_policy_template_version`;

ALTER TABLE `cat_migration_product`
  DROP FOREIGN KEY `fk_cat_migration_sku`,
  DROP FOREIGN KEY `fk_cat_migration_unit`;

ALTER TABLE `cat_sku`
  DROP FOREIGN KEY `fk_cat_sku_spu`;

ALTER TABLE `cat_sku_unit`
  DROP FOREIGN KEY `fk_cat_sku_unit_sku`,
  DROP FOREIGN KEY `fk_cat_sku_unit_unit`;

ALTER TABLE `cat_spu`
  DROP FOREIGN KEY `fk_cat_spu_brand`,
  DROP FOREIGN KEY `fk_cat_spu_category`;

ALTER TABLE `cat_weighted_barcode_history`
  DROP FOREIGN KEY `fk_cat_wbh_template`;

ALTER TABLE `cat_weighted_barcode_template`
  DROP FOREIGN KEY `fk_cat_wbt_store`;

ALTER TABLE `dev_capability_snapshot`
  DROP FOREIGN KEY `fk_dev_capability_device`;

ALTER TABLE `dev_terminal_activation`
  DROP FOREIGN KEY `fk_dev_activation_org`,
  DROP FOREIGN KEY `fk_dev_activation_store`;

ALTER TABLE `dev_terminal_credential`
  DROP FOREIGN KEY `fk_dev_credential_device`;

ALTER TABLE `dpk_catalog_package`
  DROP FOREIGN KEY `fk_dpk_package_store`;

ALTER TABLE `inv_audit_event`
  DROP FOREIGN KEY `fk_inv_audit_store`;

ALTER TABLE `inv_cost_audit_event`
  DROP FOREIGN KEY `fk_inv_cost_audit_store`;

ALTER TABLE `inv_cost_balance`
  DROP FOREIGN KEY `fk_inv_cost_balance_policy`,
  DROP FOREIGN KEY `fk_inv_cost_balance_sku`,
  DROP FOREIGN KEY `fk_inv_cost_balance_store`;

ALTER TABLE `inv_cost_ledger`
  DROP FOREIGN KEY `fk_inv_cost_ledger_balance`,
  DROP FOREIGN KEY `fk_inv_cost_ledger_inventory`,
  DROP FOREIGN KEY `fk_inv_cost_ledger_policy`,
  DROP FOREIGN KEY `fk_inv_cost_ledger_reversal`,
  DROP FOREIGN KEY `fk_inv_cost_ledger_sku`;

ALTER TABLE `inv_cost_policy_version`
  DROP FOREIGN KEY `fk_inv_cost_policy_store`;

ALTER TABLE `inv_cost_rebuild_run`
  DROP FOREIGN KEY `fk_inv_cost_rebuild_balance`,
  DROP FOREIGN KEY `fk_inv_cost_rebuild_store`;

ALTER TABLE `inv_lot_allocation`
  DROP FOREIGN KEY `fk_inv_lot_allocation_command`,
  DROP FOREIGN KEY `fk_inv_lot_allocation_identity`;

ALTER TABLE `inv_lot_audit_event`
  DROP FOREIGN KEY `fk_inv_lot_audit_store`;

ALTER TABLE `inv_lot_balance`
  DROP FOREIGN KEY `fk_inv_lot_balance_identity`;

ALTER TABLE `inv_lot_command`
  DROP FOREIGN KEY `fk_inv_lot_command_store`;

ALTER TABLE `inv_lot_expiry_projection`
  DROP FOREIGN KEY `fk_inv_lot_expiry_identity`;

ALTER TABLE `inv_lot_identity`
  DROP FOREIGN KEY `fk_inv_lot_policy`,
  DROP FOREIGN KEY `fk_inv_lot_sku`,
  DROP FOREIGN KEY `fk_inv_lot_store`,
  DROP FOREIGN KEY `fk_inv_lot_unit`;

ALTER TABLE `inv_lot_ledger`
  DROP FOREIGN KEY `fk_inv_lot_ledger_balance`,
  DROP FOREIGN KEY `fk_inv_lot_ledger_command`;

ALTER TABLE `inv_lot_package_release`
  DROP FOREIGN KEY `fk_inv_lot_package_store`;

ALTER TABLE `inv_stock_anomaly`
  DROP FOREIGN KEY `fk_inv_anomaly_command`,
  DROP FOREIGN KEY `fk_inv_anomaly_policy`,
  DROP FOREIGN KEY `fk_inv_anomaly_store`;

ALTER TABLE `inv_stock_balance`
  DROP FOREIGN KEY `fk_inv_balance_sku`;

ALTER TABLE `inv_stock_command`
  DROP FOREIGN KEY `fk_inv_command_store`;

ALTER TABLE `inv_stock_ledger`
  DROP FOREIGN KEY `fk_inv_ledger_balance`,
  DROP FOREIGN KEY `fk_inv_ledger_command`,
  DROP FOREIGN KEY `fk_inv_ledger_policy`,
  DROP FOREIGN KEY `fk_inv_ledger_sku`,
  DROP FOREIGN KEY `fk_inv_ledger_unit`;

ALTER TABLE `inv_stock_policy_version`
  DROP FOREIGN KEY `fk_inv_policy_store`;

ALTER TABLE `inv_stocktake`
  DROP FOREIGN KEY `fk_inv_stocktake_store`;

ALTER TABLE `inv_stocktake_adjustment`
  DROP FOREIGN KEY `fk_inv_stocktake_adjustment_command`,
  DROP FOREIGN KEY `fk_inv_stocktake_adjustment_head`,
  DROP FOREIGN KEY `fk_inv_stocktake_adjustment_line`;

ALTER TABLE `inv_stocktake_count`
  DROP FOREIGN KEY `fk_inv_stocktake_count_head`,
  DROP FOREIGN KEY `fk_inv_stocktake_count_line`;

ALTER TABLE `inv_stocktake_line`
  DROP FOREIGN KEY `fk_inv_stocktake_line_head`,
  DROP FOREIGN KEY `fk_inv_stocktake_line_sku`,
  DROP FOREIGN KEY `fk_inv_stocktake_line_unit`;

ALTER TABLE `inv_transfer_audit_event`
  DROP FOREIGN KEY `fk_trf_audit_store`;

ALTER TABLE `inv_transfer_command`
  DROP FOREIGN KEY `fk_trf_command_head`;

ALTER TABLE `inv_transfer_dispatch`
  DROP FOREIGN KEY `fk_trf_dispatch_head`;

ALTER TABLE `inv_transfer_dispatch_line`
  DROP FOREIGN KEY `fk_trf_dispatch_line_head`,
  DROP FOREIGN KEY `fk_trf_dispatch_line_transfer`;

ALTER TABLE `inv_transfer_line`
  DROP FOREIGN KEY `fk_trf_line_head`,
  DROP FOREIGN KEY `fk_trf_line_requested_unit`,
  DROP FOREIGN KEY `fk_trf_line_sku`,
  DROP FOREIGN KEY `fk_trf_line_unit`;

ALTER TABLE `inv_transfer_order`
  DROP FOREIGN KEY `fk_trf_destination_store`,
  DROP FOREIGN KEY `fk_trf_source_store`;

ALTER TABLE `inv_transfer_receipt`
  DROP FOREIGN KEY `fk_trf_receipt_head`;

ALTER TABLE `inv_transfer_receipt_line`
  DROP FOREIGN KEY `fk_trf_receipt_line_dispatch`,
  DROP FOREIGN KEY `fk_trf_receipt_line_head`,
  DROP FOREIGN KEY `fk_trf_receipt_line_transfer`;

ALTER TABLE `inv_transfer_transit_ledger`
  DROP FOREIGN KEY `fk_trf_transit_head`,
  DROP FOREIGN KEY `fk_trf_transit_line`;

ALTER TABLE `jsh_config_binding`
  DROP FOREIGN KEY `fk_jsh_binding_current`,
  DROP FOREIGN KEY `fk_jsh_binding_previous`,
  DROP FOREIGN KEY `fk_jsh_binding_store`;

ALTER TABLE `jsh_config_template_version`
  DROP FOREIGN KEY `fk_jsh_cfgver_template`;

ALTER TABLE `jsh_org_unit`
  DROP FOREIGN KEY `fk_jsh_org_parent`;

ALTER TABLE `jsh_staff_scope`
  DROP FOREIGN KEY `fk_jsh_scope_org`,
  DROP FOREIGN KEY `fk_jsh_scope_store`;

ALTER TABLE `jsh_store`
  DROP FOREIGN KEY `fk_jsh_store_org`;

ALTER TABLE `lbl_label_task`
  DROP FOREIGN KEY `fk_lbl_task_price_book`,
  DROP FOREIGN KEY `fk_lbl_task_store`;

ALTER TABLE `lbl_label_task_item`
  DROP FOREIGN KEY `fk_lbl_item_price_book`,
  DROP FOREIGN KEY `fk_lbl_item_price_item`,
  DROP FOREIGN KEY `fk_lbl_item_sku`,
  DROP FOREIGN KEY `fk_lbl_item_store`,
  DROP FOREIGN KEY `fk_lbl_item_task`,
  DROP FOREIGN KEY `fk_lbl_item_unit`;

ALTER TABLE `lbl_task_event`
  DROP FOREIGN KEY `fk_lbl_event_item`,
  DROP FOREIGN KEY `fk_lbl_event_task`;

ALTER TABLE `lbl_task_exception`
  DROP FOREIGN KEY `fk_lbl_exception_item`,
  DROP FOREIGN KEY `fk_lbl_exception_task`;

ALTER TABLE `lbl_template`
  DROP FOREIGN KEY `fk_lbl_template_store`;

ALTER TABLE `mbr_benefit_level_mapping`
  DROP FOREIGN KEY `fk_mbr_benefit_mapping_version`;

ALTER TABLE `mbr_benefit_scope`
  DROP FOREIGN KEY `fk_mbr_benefit_scope_store`,
  DROP FOREIGN KEY `fk_mbr_benefit_scope_version`;

ALTER TABLE `mbr_benefit_state_event`
  DROP FOREIGN KEY `fk_mbr_benefit_state_version`;

ALTER TABLE `mbr_benefit_version`
  DROP FOREIGN KEY `fk_mbr_benefit_version_policy`;

ALTER TABLE `mbr_consent_ledger`
  DROP FOREIGN KEY `fk_mbr_consent_member`;

ALTER TABLE `mbr_entitlement_snapshot`
  DROP FOREIGN KEY `fk_mbr_entitlement_member`,
  DROP FOREIGN KEY `fk_mbr_entitlement_version`;

ALTER TABLE `mbr_identity`
  DROP FOREIGN KEY `fk_mbr_identity_member`;

ALTER TABLE `mbr_level_history`
  DROP FOREIGN KEY `fk_mbr_level_member`,
  DROP FOREIGN KEY `fk_mbr_level_store`;

ALTER TABLE `mbr_member_link_ledger`
  DROP FOREIGN KEY `fk_mbr_link_source`,
  DROP FOREIGN KEY `fk_mbr_link_target`;

ALTER TABLE `mbr_points_account`
  DROP FOREIGN KEY `fk_mbr_points_account_member`;

ALTER TABLE `mbr_points_allocation`
  DROP FOREIGN KEY `fk_mbr_points_allocation_lot`;

ALTER TABLE `mbr_points_ledger`
  DROP FOREIGN KEY `fk_mbr_points_ledger_member`,
  DROP FOREIGN KEY `fk_mbr_points_ledger_store`;

ALTER TABLE `mbr_points_lot`
  DROP FOREIGN KEY `fk_mbr_points_lot_member`;

ALTER TABLE `mbr_privacy_history`
  DROP FOREIGN KEY `fk_mbr_privacy_history_request`;

ALTER TABLE `mbr_privacy_request`
  DROP FOREIGN KEY `fk_mbr_privacy_member`;

ALTER TABLE `mig_approval`
  DROP FOREIGN KEY `fk_mig_approval_batch`;

ALTER TABLE `mig_audit_event`
  DROP FOREIGN KEY `fk_mig_audit_batch`;

ALTER TABLE `mig_file`
  DROP FOREIGN KEY `fk_mig_file_batch`;

ALTER TABLE `mig_outbox`
  DROP FOREIGN KEY `fk_mig_outbox_batch`;

ALTER TABLE `mig_owner_checkpoint`
  DROP FOREIGN KEY `fk_mig_checkpoint_batch`,
  DROP FOREIGN KEY `fk_mig_checkpoint_row`;

ALTER TABLE `mig_preflight_error`
  DROP FOREIGN KEY `fk_mig_error_batch`,
  DROP FOREIGN KEY `fk_mig_error_file`;

ALTER TABLE `mig_reconciliation`
  DROP FOREIGN KEY `fk_mig_reconcile_batch`;

ALTER TABLE `mig_staging_row`
  DROP FOREIGN KEY `fk_mig_stage_batch`,
  DROP FOREIGN KEY `fk_mig_stage_file`;

ALTER TABLE `mig_state_event`
  DROP FOREIGN KEY `fk_mig_state_batch`;

ALTER TABLE `onb_approval`
  DROP FOREIGN KEY `fk_onb_approval_plan`;

ALTER TABLE `onb_audit_event`
  DROP FOREIGN KEY `fk_onb_audit_plan`;

ALTER TABLE `onb_check_result`
  DROP FOREIGN KEY `fk_onb_check_plan`;

ALTER TABLE `onb_command_result`
  DROP FOREIGN KEY `fk_onb_command_plan`;

ALTER TABLE `onb_config_snapshot`
  DROP FOREIGN KEY `fk_onb_snapshot_plan`;

ALTER TABLE `onb_outbox`
  DROP FOREIGN KEY `fk_onb_outbox_plan`;

ALTER TABLE `onb_plan`
  DROP FOREIGN KEY `fk_onb_plan_source`,
  DROP FOREIGN KEY `fk_onb_plan_target`,
  DROP FOREIGN KEY `fk_onb_plan_template`;

ALTER TABLE `onb_state_event`
  DROP FOREIGN KEY `fk_onb_state_plan`;

ALTER TABLE `onb_step_checkpoint`
  DROP FOREIGN KEY `fk_onb_checkpoint_plan`;

ALTER TABLE `ops_daily_close`
  DROP FOREIGN KEY `fk_ops_close_correction`,
  DROP FOREIGN KEY `fk_ops_close_store`;

ALTER TABLE `ops_daily_close_approval`
  DROP FOREIGN KEY `fk_ops_approval_close`;

ALTER TABLE `ops_daily_close_audit`
  DROP FOREIGN KEY `fk_ops_audit_close`;

ALTER TABLE `ops_daily_close_checkpoint`
  DROP FOREIGN KEY `fk_ops_checkpoint_close`;

ALTER TABLE `ops_daily_close_command_result`
  DROP FOREIGN KEY `fk_ops_command_close`;

ALTER TABLE `ops_daily_close_difference`
  DROP FOREIGN KEY `fk_ops_difference_close`;

ALTER TABLE `ops_daily_close_outbox`
  DROP FOREIGN KEY `fk_ops_outbox_close`;

ALTER TABLE `ops_daily_close_preflight`
  DROP FOREIGN KEY `fk_ops_preflight_close`;

ALTER TABLE `ops_daily_close_signature`
  DROP FOREIGN KEY `fk_ops_signature_close`;

ALTER TABLE `ops_daily_close_snapshot`
  DROP FOREIGN KEY `fk_ops_snapshot_close`;

ALTER TABLE `ops_daily_close_state_event`
  DROP FOREIGN KEY `fk_ops_state_close`;

ALTER TABLE `ops_exception_action_plan`
  DROP FOREIGN KEY `fk_ops_exc_plan_case`;

ALTER TABLE `ops_exception_audit_event`
  DROP FOREIGN KEY `fk_ops_exc_audit_case`;

ALTER TABLE `ops_exception_case`
  DROP FOREIGN KEY `fk_ops_exc_case_store`;

ALTER TABLE `ops_exception_command`
  DROP FOREIGN KEY `fk_ops_exc_command_case`;

ALTER TABLE `ops_exception_lease_event`
  DROP FOREIGN KEY `fk_ops_exc_lease_case`;

ALTER TABLE `ops_exception_observation`
  DROP FOREIGN KEY `fk_ops_exc_obs_case`;

ALTER TABLE `ops_exception_outbox`
  DROP FOREIGN KEY `fk_ops_exc_outbox_case`;

ALTER TABLE `ops_exception_repair_command`
  DROP FOREIGN KEY `fk_ops_exc_repair_case`;

ALTER TABLE `ops_exception_review`
  DROP FOREIGN KEY `fk_ops_exc_review_case`;

ALTER TABLE `ops_exception_state_event`
  DROP FOREIGN KEY `fk_ops_exc_state_case`;

ALTER TABLE `ord_cash_payment`
  DROP FOREIGN KEY `fk_cash_payment_order`,
  DROP FOREIGN KEY `fk_cash_payment_shift`;

ALTER TABLE `ord_cash_refund`
  DROP FOREIGN KEY `fk_ord_cash_refund_order`,
  DROP FOREIGN KEY `fk_ord_cash_refund_payment`,
  DROP FOREIGN KEY `fk_ord_cash_refund_shift`;

ALTER TABLE `ord_cash_tender`
  DROP FOREIGN KEY `fk_ord_cash_tender_order`,
  DROP FOREIGN KEY `fk_ord_cash_tender_shift`,
  DROP FOREIGN KEY `fk_ord_cash_tender_store`;

ALTER TABLE `ord_member_benefit_binding`
  DROP FOREIGN KEY `fk_ord_member_benefit_order`;

ALTER TABLE `ord_order_line`
  DROP FOREIGN KEY `fk_ord_line_order`,
  DROP FOREIGN KEY `fk_ord_line_sku`,
  DROP FOREIGN KEY `fk_ord_line_unit`;

ALTER TABLE `ord_print_job`
  DROP FOREIGN KEY `fk_print_job_order`;

ALTER TABLE `ord_print_request`
  DROP FOREIGN KEY `fk_print_request_document`,
  DROP FOREIGN KEY `fk_print_request_job`,
  DROP FOREIGN KEY `fk_print_request_order`;

ALTER TABLE `ord_promotion_binding`
  DROP FOREIGN KEY `fk_ord_promotion_order`;

ALTER TABLE `ord_receipt_document`
  DROP FOREIGN KEY `fk_receipt_document_order`;

ALTER TABLE `ord_sales_order`
  DROP FOREIGN KEY `fk_ord_shift`,
  DROP FOREIGN KEY `fk_ord_store`;

ALTER TABLE `ord_state_history`
  DROP FOREIGN KEY `fk_ord_history_order`;

ALTER TABLE `ord_tender_settlement`
  DROP FOREIGN KEY `fk_ord_tender_settlement_order`;

ALTER TABLE `pay_payment_attempt`
  DROP FOREIGN KEY `fk_pay_attempt_payment`;

ALTER TABLE `pay_payment_intent`
  DROP FOREIGN KEY `fk_pay_intent_order`,
  DROP FOREIGN KEY `fk_pay_intent_store`,
  DROP FOREIGN KEY `fk_pay_intent_tender_allocation`,
  DROP FOREIGN KEY `fk_pay_intent_tender_plan`;

ALTER TABLE `pay_provider_observation`
  DROP FOREIGN KEY `fk_pay_observation_attempt`;

ALTER TABLE `pay_reconciliation_case`
  DROP FOREIGN KEY `fk_pay_rec_case_run`;

ALTER TABLE `pay_refund`
  DROP FOREIGN KEY `fk_pay_refund_order`,
  DROP FOREIGN KEY `fk_pay_refund_payment`,
  DROP FOREIGN KEY `fk_pay_refund_store`;

ALTER TABLE `pay_refund_line`
  DROP FOREIGN KEY `fk_pay_refund_line_order_line`,
  DROP FOREIGN KEY `fk_pay_refund_line_refund`;

ALTER TABLE `pay_statement_entry`
  DROP FOREIGN KEY `fk_pay_statement_run`;

ALTER TABLE `pay_tender_allocation`
  DROP FOREIGN KEY `fk_pay_tender_allocation_plan`;

ALTER TABLE `pay_tender_history`
  DROP FOREIGN KEY `fk_pay_tender_history_plan`;

ALTER TABLE `pay_tender_plan`
  DROP FOREIGN KEY `fk_pay_tender_plan_order`,
  DROP FOREIGN KEY `fk_pay_tender_plan_shift`,
  DROP FOREIGN KEY `fk_pay_tender_plan_store`;

ALTER TABLE `pos_sync_business_fact`
  DROP FOREIGN KEY `fk_pos_sync_fact_inbox`;

ALTER TABLE `pos_sync_cursor`
  DROP FOREIGN KEY `fk_pos_sync_cursor_device`;

ALTER TABLE `pos_sync_dead_letter`
  DROP FOREIGN KEY `fk_pos_sync_dead_inbox`;

ALTER TABLE `pos_sync_device`
  DROP FOREIGN KEY `fk_pos_sync_device_org`,
  DROP FOREIGN KEY `fk_pos_sync_device_store`;

ALTER TABLE `pos_sync_inbox`
  DROP FOREIGN KEY `fk_pos_sync_inbox_device`;

ALTER TABLE `pos_sync_pull_page`
  DROP FOREIGN KEY `fk_pos_sync_page_device`;

ALTER TABLE `pos_sync_security_event`
  DROP FOREIGN KEY `fk_pos_sync_security_device`;

ALTER TABLE `prc_member_price_item`
  DROP FOREIGN KEY `fk_prc_member_price_item_sku`,
  DROP FOREIGN KEY `fk_prc_member_price_item_unit`,
  DROP FOREIGN KEY `fk_prc_member_price_item_version`;

ALTER TABLE `prc_member_price_version`
  DROP FOREIGN KEY `fk_prc_member_price_store`;

ALTER TABLE `prc_price_book`
  DROP FOREIGN KEY `fk_prc_book_store`;

ALTER TABLE `prc_price_item`
  DROP FOREIGN KEY `fk_prc_item_book`,
  DROP FOREIGN KEY `fk_prc_item_sku`,
  DROP FOREIGN KEY `fk_prc_item_unit`;

ALTER TABLE `prm_adjustment`
  DROP FOREIGN KEY `fk_prm_adjustment_quote`;

ALTER TABLE `prm_manual_price_audit`
  DROP FOREIGN KEY `fk_prm_manual_quote`,
  DROP FOREIGN KEY `fk_prm_manual_store`;

ALTER TABLE `prm_quote`
  DROP FOREIGN KEY `fk_prm_quote_package`,
  DROP FOREIGN KEY `fk_prm_quote_store`;

ALTER TABLE `prm_quote_line`
  DROP FOREIGN KEY `fk_prm_quote_line_quote`;

ALTER TABLE `prm_quote_member_benefit`
  DROP FOREIGN KEY `fk_prm_member_benefit_quote`;

ALTER TABLE `prm_refund_allocation_ledger`
  DROP FOREIGN KEY `fk_prm_refund_allocation`;

ALTER TABLE `prm_rule_benefit`
  DROP FOREIGN KEY `fk_prm_benefit_version`;

ALTER TABLE `prm_rule_package`
  DROP FOREIGN KEY `fk_prm_package_store`;

ALTER TABLE `prm_rule_package_item`
  DROP FOREIGN KEY `fk_prm_package_item_package`,
  DROP FOREIGN KEY `fk_prm_package_item_version`;

ALTER TABLE `prm_rule_scope`
  DROP FOREIGN KEY `fk_prm_scope_version`;

ALTER TABLE `prm_rule_version`
  DROP FOREIGN KEY `fk_prm_version_rule`;

ALTER TABLE `prm_transaction_allocation`
  DROP FOREIGN KEY `fk_prm_allocation_snapshot`;

ALTER TABLE `prm_transaction_snapshot`
  DROP FOREIGN KEY `fk_prm_snapshot_quote`,
  DROP FOREIGN KEY `fk_prm_snapshot_store`;

ALTER TABLE `pur_audit_event`
  DROP FOREIGN KEY `fk_pur_audit_store`;

ALTER TABLE `pur_purchase_order`
  DROP FOREIGN KEY `fk_pur_order_store`,
  DROP FOREIGN KEY `fk_pur_order_supplier`;

ALTER TABLE `pur_purchase_order_line`
  DROP FOREIGN KEY `fk_pur_order_line_head`,
  DROP FOREIGN KEY `fk_pur_order_line_sku`,
  DROP FOREIGN KEY `fk_pur_order_line_unit`;

ALTER TABLE `pur_purchase_return`
  DROP FOREIGN KEY `fk_pur_return_receipt`;

ALTER TABLE `pur_purchase_return_line`
  DROP FOREIGN KEY `fk_pur_return_line_head`,
  DROP FOREIGN KEY `fk_pur_return_line_receipt`,
  DROP FOREIGN KEY `fk_pur_return_line_sku`,
  DROP FOREIGN KEY `fk_pur_return_line_unit`;

ALTER TABLE `pur_receipt`
  DROP FOREIGN KEY `fk_pur_receipt_order`,
  DROP FOREIGN KEY `fk_pur_receipt_store`;

ALTER TABLE `pur_receipt_line`
  DROP FOREIGN KEY `fk_pur_receipt_line_head`,
  DROP FOREIGN KEY `fk_pur_receipt_line_order`,
  DROP FOREIGN KEY `fk_pur_receipt_line_sku`,
  DROP FOREIGN KEY `fk_pur_receipt_line_unit`;

ALTER TABLE `ret_exchange_event`
  DROP FOREIGN KEY `fk_ret_exchange_event`;

ALTER TABLE `ret_exchange_leg`
  DROP FOREIGN KEY `fk_ret_exchange_leg`;

ALTER TABLE `ret_return_line`
  DROP FOREIGN KEY `fk_ret_line_header`;

ALTER TABLE `ret_state_history`
  DROP FOREIGN KEY `fk_ret_history_header`;

ALTER TABLE `rpl_generation_run`
  DROP FOREIGN KEY `fk_rpl_run_policy`;

ALTER TABLE `rpl_policy_item`
  DROP FOREIGN KEY `fk_rpl_item_base_unit`,
  DROP FOREIGN KEY `fk_rpl_item_policy`,
  DROP FOREIGN KEY `fk_rpl_item_purchase_unit`,
  DROP FOREIGN KEY `fk_rpl_item_sku`,
  DROP FOREIGN KEY `fk_rpl_item_supplier`;

ALTER TABLE `rpl_policy_version`
  DROP FOREIGN KEY `fk_rpl_policy_store`;

ALTER TABLE `rpl_suggestion`
  DROP FOREIGN KEY `fk_rpl_suggestion_policy_item`,
  DROP FOREIGN KEY `fk_rpl_suggestion_run`;

ALTER TABLE `rpl_suggestion_event`
  DROP FOREIGN KEY `fk_rpl_event_suggestion`;

ALTER TABLE `rpt_export_artifact`
  DROP FOREIGN KEY `fk_rpt_artifact_export`;

ALTER TABLE `rpt_projection_lineage`
  DROP FOREIGN KEY `fk_rpt_lineage_source`;

ALTER TABLE `saas_application_state_event`
  DROP FOREIGN KEY `fk_saas_state_app`;

ALTER TABLE `saas_entitlement_item`
  DROP FOREIGN KEY `fk_saas_item_version`;

ALTER TABLE `saas_entitlement_version`
  DROP FOREIGN KEY `fk_saas_ent_plan`;

ALTER TABLE `saas_initialization_checkpoint`
  DROP FOREIGN KEY `fk_saas_checkpoint_app`;

ALTER TABLE `saas_merchant_application`
  DROP FOREIGN KEY `fk_saas_app_plan`;

ALTER TABLE `saas_tenant_entitlement`
  DROP FOREIGN KEY `fk_saas_tenant_plan`;

ALTER TABLE `shf_cash_ledger`
  DROP FOREIGN KEY `fk_cash_ledger_order`,
  DROP FOREIGN KEY `fk_cash_ledger_payment`,
  DROP FOREIGN KEY `fk_cash_ledger_refund`,
  DROP FOREIGN KEY `fk_cash_ledger_shift`,
  DROP FOREIGN KEY `fk_cash_ledger_tender`;

ALTER TABLE `shf_cash_movement`
  DROP FOREIGN KEY `fk_shf_movement_shift`;

ALTER TABLE `shf_drawer_event`
  DROP FOREIGN KEY `fk_shf_drawer_shift`;

ALTER TABLE `shf_shift`
  DROP FOREIGN KEY `fk_shf_store`;

ALTER TABLE `shf_shift_approval`
  DROP FOREIGN KEY `fk_shf_approval_shift`;

ALTER TABLE `sub_notification_intent`
  DROP FOREIGN KEY `fk_sub_notice_header`;

ALTER TABLE `sub_subscription_state_event`
  DROP FOREIGN KEY `fk_sub_state_header`;

ALTER TABLE `sub_subscription_term`
  DROP FOREIGN KEY `fk_sub_term_header`;

ALTER TABLE `svc_attachment`
  DROP FOREIGN KEY `fk_svc_attachment_ticket`;

ALTER TABLE `svc_catalog_item`
  DROP FOREIGN KEY `fk_svc_catalog_item_header`;

ALTER TABLE `svc_implementation_project`
  DROP FOREIGN KEY `fk_svc_project_catalog`;

ALTER TABLE `svc_project_check_item`
  DROP FOREIGN KEY `fk_svc_project_check_header`;

ALTER TABLE `upg_rollout`
  DROP FOREIGN KEY `fk_upg_rollout_release`;

ALTER TABLE `upg_terminal_task`
  DROP FOREIGN KEY `fk_upg_task_release`,
  DROP FOREIGN KEY `fk_upg_task_rollout`;
