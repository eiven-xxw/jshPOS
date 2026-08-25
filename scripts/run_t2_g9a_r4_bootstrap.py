#!/usr/bin/env python3
"""经正式 HTTP API 建立 G9A-R4 三业态商业前置、主数据、签名包与虚构终端。

脚本不连接 MySQL/Redis，不写业务表，不记录请求正文和令牌。一次性终端凭据与合成登录
口令仅写入调用方指定的受控临时文件；普通证据只保存不可逆摘要。
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import time
import urllib.parse
from datetime import datetime, timedelta, timezone
from typing import Any

from run_t2_gate8b_runtime_api_journey import (
    ApiClient,
    JourneyFailure,
    PLATFORM_TENANT,
    TENANT_ADMIN_ROLE_KEY,
    api_headers,
    data,
    find_by,
    require_value,
    timestamp,
)


ROOT = pathlib.Path(__file__).resolve().parents[1]
# SaaS/Subscription 的服务端平台治理边界按已接受契约检查该角色键；
# 复核员仍是独立账号，只授予本次审批所需菜单，不与发起人复用身份。
PLATFORM_ROLE_KEY = "platform_admin"
PLATFORM_REVIEWER_MENU_IDS = [9201700, 9201703, 9201709]
INDUSTRIES = (
    ("CONVENIENCE", "convenience", "便利店"),
    ("SNACK_DISCOUNT", "snack", "零食折扣店"),
    ("COMMUNITY_SUPERMARKET", "community", "社区超市"),
)
WAREHOUSE_ID = "01K9R400000000000000000099"
CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def stable_ulid(label: str) -> str:
    """生成满足契约的确定性合成 ULID；不用于生产身份生成。"""
    value = int.from_bytes(hashlib.sha256(label.encode("utf-8")).digest()[:15], "big")
    chars: list[str] = []
    for _ in range(24):
        chars.append(CROCKFORD[value & 31])
        value >>= 5
    return "01" + "".join(reversed(chars))


def instant_timestamp(value: datetime) -> str:
    """按 REST ``Instant`` 契约输出 UTC ISO-8601，避免复用 LocalDateTime 格式。"""
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def all_commercial_menu_ids() -> list[int]:
    """从已发布迁移读取商业菜单身份，不查询或写入数据库。"""
    result = {1, 100, 101, 1001, 1002, 1008}
    for path in (ROOT / "server/ruoyi-modules").glob("jshpos-*/src/main/resources/db/migration/*.sql"):
        for value in re.findall(r"\b920[0-9]{4}\b", path.read_text(encoding="utf-8", errors="replace")):
            menu_id = int(value)
            # 平台 SaaS 审批能力不下放给商户；Subscription 自查和 Service 能力保留。
            if menu_id < 9201700 or 9201720 <= menu_id <= 9201790:
                result.add(menu_id)
    if len(result) < 200:
        raise JourneyFailure(f"商业菜单发现异常，仅 {len(result)} 项")
    return sorted(result)


def create_platform_prerequisites(client: ApiClient, passwords: dict[str, str], run_tag: str) -> tuple[Any, Any]:
    client.login(PLATFORM_TENANT, "admin", passwords["platform"], "r4-platform-login")
    client.call("POST", "/system/role", "r4-platform-reviewer-role", body={
        "roleName": "G9A-R4平台复核员", "roleKey": PLATFORM_ROLE_KEY, "roleSort": 3,
        "dataScope": "1", "menuCheckStrictly": True, "deptCheckStrictly": True,
        "status": "0", "menuIds": PLATFORM_REVIEWER_MENU_IDS, "remark": "内部正式栈职责分离",
    })
    rows = client.call("GET", "/system/role/list?" + urllib.parse.urlencode({
        "roleKey": PLATFORM_ROLE_KEY, "pageNum": 1, "pageSize": 20,
    }), "r4-platform-reviewer-role-read").get("rows", [])
    role_id = require_value(find_by(rows, "roleKey", PLATFORM_ROLE_KEY, "r4-platform-reviewer-role-read").get("roleId"),
                            "r4-platform-reviewer-role-read")
    client.call("POST", "/system/user", "r4-platform-reviewer-user", body={
        "deptId": 103, "userName": "g9a_r4_reviewer", "nickName": "G9A-R4合成复核员",
        "password": passwords["reviewer"], "status": "0", "roleIds": [role_id], "postIds": [],
        "remark": "内部正式栈职责分离账号",
    })
    client.call("POST", "/system/tenant/package", "r4-tenant-package-create", body={
        "packageName": f"G9A-R4商业V1-{run_tag}", "menuIds": all_commercial_menu_ids(),
        "remark": "三业态内部正式栈权限套餐", "menuCheckStrictly": True, "status": "0",
    })
    packages = data(client.call("GET", "/system/tenant/package/selectList", "r4-tenant-package-read")) or []
    package_id = require_value(find_by(packages, "packageName", f"G9A-R4商业V1-{run_tag}",
                                       "r4-tenant-package-read").get("packageId"), "r4-tenant-package-read")

    now = datetime.now(timezone.utc)
    plan_body = {
        "planCode": f"G9AR4_{run_tag}", "planName": "G9A-R4三业态商业套餐",
        "platformPackageId": package_id, "accountLimit": 50,
    }
    plan = data(client.call("POST", "/api/v1/saas/plans", "r4-saas-plan-create", body=plan_body,
                            headers=api_headers(f"r4-plan-{run_tag}", stable_ulid("r4-plan-trace-" + run_tag))))
    plan_id = require_value(plan.get("planId"), "r4-saas-plan-create")
    version = data(client.call("POST", f"/api/v1/saas/plans/{plan_id}/versions", "r4-entitlement-create", body={
        "versionNo": 1, "effectiveAt": timestamp(now - timedelta(days=1)),
        "expiresAt": timestamp(now + timedelta(days=365)),
        "items": [
            {"featureCode": "COMMERCIAL_V1", "enabled": True, "quotaLimit": 100000},
            {"featureCode": "SERVICE_OPERATIONS", "enabled": True, "quotaLimit": 10000},
        ],
    }, headers=api_headers(f"r4-ent-{run_tag}", stable_ulid("r4-ent-trace-" + run_tag))))
    version_id = require_value(version.get("versionId"), "r4-entitlement-create")
    client.call("POST", f"/api/v1/saas/entitlements/{version_id}/validate", "r4-entitlement-validate",
                headers=api_headers(f"r4-ent-val-{run_tag}", stable_ulid("r4-ent-val-trace-" + run_tag)))
    client.login(PLATFORM_TENANT, "g9a_r4_reviewer", passwords["reviewer"], "r4-reviewer-login")
    client.call("POST", f"/api/v1/saas/entitlements/{version_id}/approve", "r4-entitlement-approve",
                headers=api_headers(f"r4-ent-appr-{run_tag}", stable_ulid("r4-ent-appr-trace-" + run_tag)))
    client.login(PLATFORM_TENANT, "admin", passwords["platform"], "r4-platform-login-after-approval")
    client.call("POST", f"/api/v1/saas/entitlements/{version_id}/publish", "r4-entitlement-publish",
                headers=api_headers(f"r4-ent-pub-{run_tag}", stable_ulid("r4-ent-pub-trace-" + run_tag)))
    client.call("POST", f"/api/v1/saas/entitlements/{version_id}/activate", "r4-entitlement-activate",
                headers=api_headers(f"r4-ent-act-{run_tag}", stable_ulid("r4-ent-act-trace-" + run_tag)))
    return plan_id, package_id


def activate_terminal(client: ApiClient, ids: dict[str, Any], label: str) -> dict[str, Any]:
    fingerprint = hashlib.sha256(f"{label}:fingerprint".encode()).hexdigest()
    public_key = hashlib.sha256(f"{label}:device-public-key".encode()).hexdigest()
    issued = data(client.call("POST", "/api/v1/terminal-activations", f"{label}-terminal-issue", body={
        "orgUnitId": ids["orgId"], "storeId": ids["storeId"], "boundUserId": ids["userId"],
        "terminalProfileCode": "ANDROID_POS_V1", "expiresInSeconds": 1800,
        "idempotencyKey": f"g9a-r4-terminal-issue-{label}",
    }))
    activation_id = require_value(issued.get("activationId"), f"{label}-terminal-issue")
    activation_secret = require_value(issued.get("activationSecret"), f"{label}-terminal-issue")
    activated = data(client.call("POST", "/api/pos/v1/terminals/activate", f"{label}-terminal-activate", body={
        "activationId": activation_id, "activationSecret": activation_secret,
        "deviceFingerprintSha256": fingerprint, "publicKeySha256": public_key,
        "appVersion": "1.0.0", "protocolVersion": "1.0", "schemaVersion": "1.0",
        "capability": {"scanner": "KEYBOARD_WEDGE", "printer": "UNAVAILABLE", "synthetic": True},
        "idempotencyKey": f"g9a-r4-terminal-activate-{label}",
        "clientTime": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    }, authenticated=False))
    return {
        "deviceId": require_value(activated.get("deviceId"), f"{label}-terminal-activate"),
        "terminalId": require_value(activated.get("terminalId"), f"{label}-terminal-activate"),
        "deviceCredential": require_value(activated.get("deviceCredential"), f"{label}-terminal-activate"),
        "deviceFingerprintSha256": fingerprint,
        "publicKeySha256": public_key,
    }


def prepare_community_lots(client: ApiClient, passwords: dict[str, str], tenant_id: str,
                           username: str, reviewer_username: str, store_id: Any,
                           sku_id: Any, unit_id: Any, run_tag: str) -> int:
    """经 Catalog/Procurement/Inventory 正式端口形成可签名的社区超市批次事实。"""
    today = datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=8))).date()
    policy_id = stable_ulid(f"r4-lot-policy-{run_tag}")
    client.call("POST", "/api/v1/catalog/lot-policies", "community-lot-policy", body={
        "policyVersionId": policy_id, "storeId": store_id, "skuId": sku_id,
        "enabled": True, "expiryBasis": "EXPLICIT_EXPIRY_DATE", "shelfLifeDays": None,
        "nearExpiryDays": 7,
        "effectiveFrom": (datetime.now(timezone.utc) - timedelta(days=1)).isoformat().replace("+00:00", "Z"),
    }, headers={"X-Correlation-ID": stable_ulid(f"r4-lot-policy-trace-{run_tag}")})

    supplier_id = stable_ulid(f"r4-lot-supplier-{run_tag}")
    order_id = stable_ulid(f"r4-lot-order-{run_tag}")
    order_line_id = stable_ulid(f"r4-lot-order-line-{run_tag}")
    receipt_id = stable_ulid(f"r4-lot-receipt-{run_tag}")
    receipt_line_id = stable_ulid(f"r4-lot-receipt-line-{run_tag}")
    client.call("POST", "/api/v1/procurement/suppliers", "community-supplier", body={
        "supplierId": supplier_id, "code": f"R4_LOT_{run_tag}", "name": "社区超市合成供应商",
        "correlationId": stable_ulid(f"r4-lot-supplier-trace-{run_tag}"),
    })
    client.call("POST", "/api/v1/procurement/orders", "community-purchase-order", body={
        "orderId": order_id, "supplierId": supplier_id, "storeId": str(store_id),
        "warehouseId": WAREHOUSE_ID, "expectedDate": (today + timedelta(days=1)).isoformat(),
        "overReceiptToleranceBps": 0,
        "lines": [{"orderLineId": order_line_id, "skuId": str(sku_id), "unitId": str(unit_id),
                   "orderedQuantity": "100.000000", "unitPriceMinor": "500", "taxRateBps": 0}],
        "correlationId": stable_ulid(f"r4-lot-order-trace-{run_tag}"),
    })
    client.call("POST", f"/api/v1/procurement/orders/{order_id}/submit", "community-purchase-submit",
                body={"correlationId": stable_ulid(f"r4-lot-submit-trace-{run_tag}")})
    client.login(tenant_id, reviewer_username, passwords["reviewer"], "community-reviewer-purchase-login")
    client.call("POST", f"/api/v1/procurement/orders/{order_id}/approve", "community-purchase-approve",
                body={"correlationId": stable_ulid(f"r4-lot-approve-trace-{run_tag}")})
    client.login(tenant_id, username, passwords["tenant"], "community-admin-after-purchase-approval")
    client.call("POST", f"/api/v1/procurement/orders/{order_id}/receipts", "community-receipt-create", body={
        "receiptId": receipt_id,
        "lines": [{"receiptLineId": receipt_line_id, "orderLineId": order_line_id,
                   "receivedQuantity": "100.000000"}],
        "correlationId": stable_ulid(f"r4-lot-receipt-trace-{run_tag}"),
    })
    client.call("POST", f"/api/v1/procurement/receipts/{receipt_id}/confirm", "community-receipt-confirm", body={
        "eventId": stable_ulid(f"r4-lot-receipt-event-{run_tag}"),
        "correlationId": stable_ulid(f"r4-lot-confirm-trace-{run_tag}"),
        "lotSplits": [{"receiptLineId": receipt_line_id, "baseQuantity": "100.000000",
                       "supplierLotCode": f"SUP-{run_tag}", "internalLotCode": f"INT-{run_tag}",
                       "productionDate": (today - timedelta(days=2)).isoformat(),
                       "receivedDate": today.isoformat(),
                       "explicitExpiryDate": (today + timedelta(days=30)).isoformat()}],
    })
    package = data(client.call(
        "POST", "/api/v1/inventory/lots/package?" + urllib.parse.urlencode({
            "storeId": store_id, "warehouseId": WAREHOUSE_ID,
        }), "community-lot-package-publish",
        headers=api_headers(stable_ulid(f"r4-lot-package-{run_tag}"),
                            stable_ulid(f"r4-lot-package-trace-{run_tag}")),
    ))
    try:
        payload = json.loads(base64.b64decode(package["payload"], validate=True))
        version = int(payload["packageVersion"])
    except (KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
        raise JourneyFailure("community-lot-package-publish: 签名包内容无效") from error
    if version <= 0:
        raise JourneyFailure("community-lot-package-publish: 包版本无效")
    return version


def prepare_standard_stock(client: ApiClient, passwords: dict[str, str], tenant_id: str,
                           username: str, reviewer_username: str, store_id: Any,
                           sku_id: Any, unit_id: Any, run_tag: str, label: str) -> None:
    """经 Procurement/Inventory/Costing 正式端口为非批次业态形成可销售期初事实。"""
    today = datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=8))).date()
    supplier_id = stable_ulid(f"r4-stock-supplier-{run_tag}-{label}")
    order_id = stable_ulid(f"r4-stock-order-{run_tag}-{label}")
    order_line_id = stable_ulid(f"r4-stock-order-line-{run_tag}-{label}")
    receipt_id = stable_ulid(f"r4-stock-receipt-{run_tag}-{label}")
    receipt_line_id = stable_ulid(f"r4-stock-receipt-line-{run_tag}-{label}")
    client.call("POST", "/api/v1/procurement/suppliers", f"{label}-supplier", body={
        "supplierId": supplier_id, "code": f"R4_STOCK_{run_tag}_{label.upper()}",
        "name": f"{label}合成供应商",
        "correlationId": stable_ulid(f"r4-stock-supplier-trace-{run_tag}-{label}"),
    })
    client.call("POST", "/api/v1/procurement/orders", f"{label}-purchase-order", body={
        "orderId": order_id, "supplierId": supplier_id, "storeId": str(store_id),
        "warehouseId": WAREHOUSE_ID, "expectedDate": (today + timedelta(days=1)).isoformat(),
        "overReceiptToleranceBps": 0,
        "lines": [{"orderLineId": order_line_id, "skuId": str(sku_id), "unitId": str(unit_id),
                   "orderedQuantity": "100.000000", "unitPriceMinor": "500", "taxRateBps": 0}],
        "correlationId": stable_ulid(f"r4-stock-order-trace-{run_tag}-{label}"),
    })
    client.call("POST", f"/api/v1/procurement/orders/{order_id}/submit", f"{label}-purchase-submit",
                body={"correlationId": stable_ulid(f"r4-stock-submit-trace-{run_tag}-{label}")})
    client.login(tenant_id, reviewer_username, passwords["reviewer"], f"{label}-reviewer-stock-login")
    client.call("POST", f"/api/v1/procurement/orders/{order_id}/approve", f"{label}-purchase-approve",
                body={"correlationId": stable_ulid(f"r4-stock-approve-trace-{run_tag}-{label}")})
    client.login(tenant_id, username, passwords["tenant"], f"{label}-admin-after-stock-approval")
    client.call("POST", f"/api/v1/procurement/orders/{order_id}/receipts", f"{label}-receipt-create", body={
        "receiptId": receipt_id,
        "lines": [{"receiptLineId": receipt_line_id, "orderLineId": order_line_id,
                   "receivedQuantity": "100.000000"}],
        "correlationId": stable_ulid(f"r4-stock-receipt-trace-{run_tag}-{label}"),
    })
    client.call("POST", f"/api/v1/procurement/receipts/{receipt_id}/confirm", f"{label}-receipt-confirm", body={
        "eventId": stable_ulid(f"r4-stock-receipt-event-{run_tag}-{label}"),
        "correlationId": stable_ulid(f"r4-stock-confirm-trace-{run_tag}-{label}"),
    })


def create_tenant(client: ApiClient, passwords: dict[str, str], plan_id: Any, industry: str,
                  label: str, display: str, run_tag: str) -> tuple[dict[str, Any], dict[str, Any]]:
    now = datetime.now(timezone.utc)
    # 每条商户开户旅程都从平台可信身份重新开始，禁止沿用上一租户管理员会话。
    client.login(PLATFORM_TENANT, "admin", passwords["platform"],
                 f"{label}-platform-login-before-application")
    application = data(client.call("POST", "/api/v1/saas/applications", f"{label}-application-create", body={
        "applicationCode": f"G9AR4_{run_tag}_{label.upper()}", "companyName": f"G9A-R4虚构{display}",
        "industry": industry, "planId": plan_id,
    }, headers=api_headers(f"r4-app-{run_tag}-{label}", stable_ulid(f"r4-app-{run_tag}-{label}"))))
    application_id = require_value(application.get("application", {}).get("applicationId"), f"{label}-application")
    client.call("POST", f"/api/v1/saas/applications/{application_id}/preflight", f"{label}-application-preflight",
                headers=api_headers(f"r4-app-pre-{run_tag}-{label}", stable_ulid(f"r4-app-pre-{run_tag}-{label}")))
    client.login(PLATFORM_TENANT, "g9a_r4_reviewer", passwords["reviewer"], f"{label}-reviewer-login")
    client.call("POST", f"/api/v1/saas/applications/{application_id}/approve", f"{label}-application-approve",
                body={"reason": "G9A-R4内部三业态独立复核"},
                headers=api_headers(f"r4-app-appr-{run_tag}-{label}", stable_ulid(f"r4-app-appr-{run_tag}-{label}")))
    client.login(PLATFORM_TENANT, "admin", passwords["platform"], f"{label}-platform-login")
    username = f"r4_{label}_admin"
    provisioned = data(client.call("POST", f"/api/v1/saas/applications/{application_id}/provision",
                                   f"{label}-tenant-provision", body={
        "contactName": "虚构联系人", "contactPhone": "00000000000",
        "bootstrapUsername": username, "bootstrapPassword": passwords["tenant"],
    }, headers=api_headers(f"r4-prov-{run_tag}-{label}", stable_ulid(f"r4-prov-{run_tag}-{label}"))))
    tenant_id = require_value(provisioned.get("application", {}).get("tenantId"), f"{label}-tenant-provision")
    client.call("POST", f"/api/v1/saas/applications/{application_id}/initialize", f"{label}-tenant-initialize",
                headers=api_headers(f"r4-init-{run_tag}-{label}", stable_ulid(f"r4-init-{run_tag}-{label}")))
    client.call("POST", f"/api/v1/saas/applications/{application_id}/activate", f"{label}-tenant-activate",
                headers=api_headers(f"r4-activate-{run_tag}-{label}", stable_ulid(f"r4-activate-{run_tag}-{label}")))
    term = {
        "contractRef": f"G9AR4-{run_tag}-{label}-CONTRACT", "externalOrderRef": f"G9AR4-{run_tag}-{label}-ORDER",
        "startsAt": timestamp(now - timedelta(hours=1)), "endsAt": timestamp(now + timedelta(days=90)),
        "graceEndsAt": timestamp(now + timedelta(days=97)), "businessTimeZone": "Asia/Shanghai",
        "degradationPolicyVersion": "RECOVERY-V1",
    }
    subscription = data(client.call("POST", f"/api/v1/subscriptions/tenants/{tenant_id}",
                                    f"{label}-subscription-create", body=term,
                                    headers=api_headers(f"r4-sub-{run_tag}-{label}", stable_ulid(f"r4-sub-{run_tag}-{label}"))))
    subscription_id = require_value(subscription.get("subscription", {}).get("subscriptionId"),
                                    f"{label}-subscription-create")
    client.call("POST", f"/api/v1/subscriptions/{subscription_id}/activate", f"{label}-subscription-activate",
                headers=api_headers(f"r4-sub-act-{run_tag}-{label}", stable_ulid(f"r4-sub-act-{run_tag}-{label}")))

    client.login(tenant_id, username, passwords["tenant"], f"{label}-tenant-login")
    profile = data(client.call("GET", "/system/user/getInfo", f"{label}-tenant-profile"))
    user_id = require_value(profile.get("user", {}).get("userId"), f"{label}-tenant-profile")
    tenant_role_rows = client.call(
        "GET", "/system/role/list?" + urllib.parse.urlencode({
            "roleKey": TENANT_ADMIN_ROLE_KEY, "pageNum": 1, "pageSize": 20,
        }), f"{label}-tenant-admin-role-read",
    ).get("rows", [])
    tenant_admin_role_id = require_value(
        find_by(tenant_role_rows, "roleKey", TENANT_ADMIN_ROLE_KEY,
                f"{label}-tenant-admin-role-read").get("roleId"),
        f"{label}-tenant-admin-role-read",
    )
    reviewer_username = f"r4_{label}_reviewer"
    client.call("POST", "/system/user", f"{label}-tenant-reviewer-create", body={
        "deptId": profile.get("user", {}).get("deptId"),
        "userName": reviewer_username, "nickName": f"{display}合成复核员",
        "password": passwords["reviewer"], "status": "0",
        "roleIds": [tenant_admin_role_id], "postIds": [],
        "remark": "G9A-R4 内部正式栈职责分离账号",
    })
    org = data(client.call("POST", "/api/v1/foundation/org-units", f"{label}-org-create", body={
        "parentId": None, "code": f"R4_{label.upper()}_HQ", "name": f"{display}虚构总部", "type": "HEADQUARTERS",
    }))
    org_id = require_value(org.get("orgUnitId"), f"{label}-org-create")
    store = data(client.call("POST", "/api/v1/foundation/stores", f"{label}-store-create", body={
        "orgUnitId": org_id, "platformDeptId": None, "code": f"R4_{label.upper()}_STORE",
        "name": f"{display}虚构门店", "zoneId": "Asia/Shanghai", "businessDayStart": "06:00:00",
    }))
    store_id = require_value(store.get("storeId"), f"{label}-store-create")
    reviewer_rows = client.call(
        "GET", "/system/user/list?" + urllib.parse.urlencode({
            "userName": reviewer_username, "pageNum": 1, "pageSize": 20,
        }), f"{label}-tenant-reviewer-read",
    ).get("rows", [])
    reviewer_user_id = require_value(
        find_by(reviewer_rows, "userName", reviewer_username,
                f"{label}-tenant-reviewer-read").get("userId"),
        f"{label}-tenant-reviewer-read",
    )
    tenant_scope = {"scopes": [{"scopeType": "TENANT", "orgUnitId": None, "storeId": None}]}
    client.call("PUT", f"/api/v1/foundation/staff-scopes/{user_id}",
                f"{label}-tenant-admin-scope", body=tenant_scope)
    client.call("PUT", f"/api/v1/foundation/staff-scopes/{reviewer_user_id}",
                f"{label}-tenant-reviewer-scope", body=tenant_scope)

    # 门店正式创建语义是 PREPARING；只有完成可信范围配置后才能通过正式 API 激活。
    # 价签、数据包和 POS 后续旅程必须消费 ACTIVE 门店，不能在测试脚本中绕过该冻结点。
    store = data(client.call("PUT", f"/api/v1/foundation/stores/{store_id}",
                             f"{label}-store-activate", body={
        "orgUnitId": org_id, "platformDeptId": None,
        "code": f"R4_{label.upper()}_STORE", "name": f"{display}虚构门店",
        "zoneId": "Asia/Shanghai", "businessDayStart": "06:00:00",
        "status": "ACTIVE", "version": store["version"],
    }))
    if store.get("status") != "ACTIVE":
        raise RuntimeError(f"G9A-R4 bootstrap failed: {label}-store-activate did not reach ACTIVE")

    service_catalog = data(client.call("POST", "/api/v1/service/catalogs", f"{label}-service-catalog", body={
        "catalogCode": f"R4_{label.upper()}_OPENING", "versionNo": 1, "industryTemplate": industry,
        "name": f"{display}内部开店检查",
        "items": [{"itemCode": "FORMAL_STACK_READY", "itemName": "正式栈已验证",
                   "mandatory": True, "sequenceNo": 1}],
    }, headers=api_headers(f"r4-svc-cat-{run_tag}-{label}", stable_ulid(f"r4-svc-cat-{run_tag}-{label}"))))
    service_catalog_id = require_value(service_catalog.get("catalog", {}).get("catalogId"),
                                       f"{label}-service-catalog")
    client.call("POST", f"/api/v1/service/catalogs/{service_catalog_id}/publish",
                f"{label}-service-catalog-publish",
                headers=api_headers(f"r4-svc-cat-pub-{run_tag}-{label}",
                                    stable_ulid(f"r4-svc-cat-pub-{run_tag}-{label}")))
    service_project = data(client.call("POST", "/api/v1/service/projects", f"{label}-service-project", body={
        "storeId": store_id, "catalogId": service_catalog_id,
        "targetDate": (now.date() + timedelta(days=7)).isoformat(), "ownerUserId": user_id,
    }, headers=api_headers(f"r4-svc-project-{run_tag}-{label}",
                          stable_ulid(f"r4-svc-project-{run_tag}-{label}"))))
    service_project_id = require_value(service_project.get("project", {}).get("projectId"),
                                       f"{label}-service-project")
    service_check_id = require_value(service_project.get("checks", [{}])[0].get("checkId"),
                                     f"{label}-service-project")
    for version, command in enumerate(("PREFLIGHT", "MARK_READY", "START")):
        client.call("POST", f"/api/v1/service/projects/{service_project_id}/commands",
                    f"{label}-service-{command.lower()}",
                    body={"command": command, "reason": "G9A-R4内部正式栈检查"},
                    headers={**api_headers(f"r4-svc-{command.lower()}-{run_tag}-{label}",
                                           stable_ulid(f"r4-svc-{command.lower()}-{run_tag}-{label}")),
                             "If-Match-Version": str(version)})
    client.call("POST", f"/api/v1/service/projects/{service_project_id}/checks/{service_check_id}/complete",
                f"{label}-service-check-complete", body={"reason": "内部合成检查完成"},
                headers={**api_headers(f"r4-svc-check-{run_tag}-{label}",
                                       stable_ulid(f"r4-svc-check-{run_tag}-{label}")),
                         "If-Match-Version": "0"})

    category = data(client.call("POST", "/api/v1/catalog/categories", f"{label}-category-create", body={
        "parentId": None, "code": f"R4_{label.upper()}_FOOD", "name": "合成食品", "sortNo": 1,
    }))
    brand = data(client.call("POST", "/api/v1/catalog/brands", f"{label}-brand-create", body={
        "code": f"R4_{label.upper()}_BRAND", "name": "合成品牌",
    }))
    unit = data(client.call("POST", "/api/v1/catalog/units", f"{label}-unit-create", body={
        "code": "PCS", "name": "件", "decimalScale": 3 if industry != "CONVENIENCE" else 0,
    }))
    barcode = {"convenience": "6900000000001", "snack": "6900000000018", "community": "6900000000025"}[label]
    product = data(client.call("POST", "/api/v1/catalog/products", f"{label}-product-create", body={
        "spuCode": f"R4-{label.upper()}-SPU", "skuCode": f"R4-{label.upper()}-SKU", "name": f"{display}合成商品",
        "categoryId": category["id"], "brandId": brand["id"],
        # Catalog 与 Flutter 正式契约使用 WEIGHT；WEIGHTED 不是已发布枚举值。
        "productType": "WEIGHT" if industry != "CONVENIENCE" else "STANDARD", "attributes": {},
        "units": [{"unitId": unit["id"], "ratioNumerator": 1, "ratioDenominator": 1,
                   "primary": True, "barcodes": [barcode]}],
    }))
    client.call("PUT", f"/api/v1/catalog/products/{product['skuId']}/state", f"{label}-product-activate",
                body={"state": "ACTIVE", "version": product["version"]})
    price_book = data(client.call("POST", "/api/v1/catalog/price-books", f"{label}-price-book-create", body={
        "code": f"R4_{label.upper()}_BASE", "name": f"{display}基础价", "versionNo": 1,
        "scopeType": "TENANT_BASE", "storeId": None,
    }))
    client.call("POST", f"/api/v1/catalog/price-books/{price_book['priceBookId']}/items", f"{label}-price-item", body={
        "skuId": product["skuId"], "unitId": unit["id"], "amountMinor": 990,
        "effectiveFrom": (now - timedelta(days=1)).isoformat().replace("+00:00", "Z"), "effectiveTo": None,
    })
    client.call("POST", f"/api/v1/catalog/price-books/{price_book['priceBookId']}/publish",
                f"{label}-price-book-publish")

    template = data(client.call("POST", "/api/v1/foundation/config/templates", f"{label}-manual-template", body={
        "code": "PROMOTION_MANUAL_AUTHORITY", "name": "人工优惠权限", "industry": industry,
    }))
    config = data(client.call("POST", f"/api/v1/foundation/config/templates/{template['templateId']}/versions",
                              f"{label}-manual-version", body={
        "schemaVersion": "1.0", "content": {"policyType": "PROMOTION_MANUAL_AUTHORITY",
            "withoutApprovalMinor": 100, "withApprovalMinor": 1000, "minimumLinePayableMinor": 1,
            "maximumRoundingMinor": 9, "roundingMultiplesMinor": [1, 10]},
    }))
    client.call("POST", f"/api/v1/foundation/config/versions/{config['configVersionId']}/publish",
                f"{label}-manual-publish")
    client.call("POST", "/api/v1/foundation/config/bindings/activate", f"{label}-manual-activate", body={
        "templateId": template["templateId"], "configVersionId": config["configVersionId"],
        "targetType": "STORE", "targetId": store_id,
    })
    # 退货退款会在原开放班次内形成服务端现金负向流水，而 POS 本地只在同步后观察该事实。
    # 因此双方都必须消费同一受治理阈值，不能依赖服务端缺省零阈值或测试内硬编码审批绕过。
    shift_template = data(client.call(
        "POST", "/api/v1/foundation/config/templates", f"{label}-shift-template", body={
            "code": "SHIFT_CASH_DIFFERENCE", "name": "交班现金差异阈值", "industry": industry,
        }))
    shift_config = data(client.call(
        "POST", f"/api/v1/foundation/config/templates/{shift_template['templateId']}/versions",
        f"{label}-shift-version", body={
            "schemaVersion": "1.0", "content": {"cashDifferenceApprovalMinor": 1000},
        }))
    client.call(
        "POST", f"/api/v1/foundation/config/versions/{shift_config['configVersionId']}/publish",
        f"{label}-shift-publish")
    client.call("POST", "/api/v1/foundation/config/bindings/activate", f"{label}-shift-activate", body={
        "templateId": shift_template["templateId"],
        "configVersionId": shift_config["configVersionId"],
        "targetType": "STORE", "targetId": store_id,
    })
    # 所有三业态在产生库存事实前都必须经正式 API 发布明确的失败关闭策略。
    # 不能依赖数据库默认值，更不能为了 E2E 绕过 Inventory Owner 的策略冻结点。
    inventory_policy_version_id = stable_ulid(f"r4-inventory-policy-{run_tag}-{label}")
    client.call("POST", "/api/v1/inventory/policies", f"{label}-inventory-policy", body={
        "policyVersionId": inventory_policy_version_id,
        "storeId": str(store_id),
        "warehouseId": WAREHOUSE_ID,
        "negativeStockMode": "DENY",
        "effectiveFrom": instant_timestamp(now - timedelta(days=1)),
        "correlationId": stable_ulid(f"r4-inventory-policy-trace-{run_tag}-{label}"),
    })
    # 采购收货会在库存 Owner 的同一应用编排中消费 Costing Owner；正式旅程必须先
    # 发布仓级移动加权策略，不能依赖隐式默认策略或在收货时绕开成本失败关闭。
    cost_policy_version_id = stable_ulid(f"r4-cost-policy-{run_tag}-{label}")
    client.call("POST", "/api/inventory/cost-policies", f"{label}-cost-policy", body={
        "policyVersionId": cost_policy_version_id,
        "storeId": str(store_id),
        "warehouseId": WAREHOUSE_ID,
        "effectiveFrom": instant_timestamp(now - timedelta(days=1)),
        "correlationId": stable_ulid(f"r4-cost-policy-trace-{run_tag}-{label}"),
    })
    lot_package_version = 0
    if industry == "COMMUNITY_SUPERMARKET":
        lot_package_version = prepare_community_lots(
            client, passwords, tenant_id, username, reviewer_username, store_id,
            product["skuId"], unit["id"], run_tag,
        )
    else:
        prepare_standard_stock(
            client, passwords, tenant_id, username, reviewer_username, store_id,
            product["skuId"], unit["id"], run_tag, label,
        )
    catalog_package = data(client.call("POST", "/api/v1/catalog/packages", f"{label}-catalog-package", body={
        "storeId": store_id, "packageVersion": 1, "previousVersion": 0,
    }))
    promotion_package = data(client.call("POST", "/api/v1/promotions/packages", f"{label}-promotion-package", body={
        "storeId": str(store_id), "packageVersion": 1, "previousVersion": 0,
        "expiresAt": (now + timedelta(days=30)).isoformat().replace("+00:00", "Z"),
        "correlationId": stable_ulid(f"r4-promo-package-{run_tag}-{label}"),
    }))

    ids = {"orgId": org_id, "storeId": store_id, "userId": user_id}
    terminal = activate_terminal(client, ids, label)
    context = {
        "journeyId": f"R4-{label.upper()}", "industry": industry, "tenantId": tenant_id,
        "applicationId": application_id, "subscriptionId": subscription_id, "orgId": org_id,
        "storeId": store_id, "userId": user_id, "reviewerUserId": reviewer_user_id,
        "serviceProjectId": service_project_id,
        "manualTemplateId": template["templateId"], "manualConfigVersionId": config["configVersionId"],
        "shiftTemplateId": shift_template["templateId"],
        "shiftConfigVersionId": shift_config["configVersionId"],
        "inventoryPolicyVersionId": inventory_policy_version_id,
        "costPolicyVersionId": cost_policy_version_id,
        "skuId": product["skuId"], "unitId": unit["id"],
        "skuCode": product["skuCode"], "barcode": barcode, "catalogVersion": catalog_package["packageVersion"],
        "promotionVersion": promotion_package["packageVersion"], "lotPackageVersion": lot_package_version,
        "terminalId": terminal["terminalId"],
        "deviceId": terminal["deviceId"], "businessDate": now.astimezone(timezone(timedelta(hours=8))).date().isoformat(),
    }
    secret = {
        "journeyId": context["journeyId"], "tenantId": tenant_id, "username": username,
        "password": passwords["tenant"], "reviewerUsername": reviewer_username,
        "reviewerPassword": passwords["reviewer"], **terminal,
    }
    return context, secret


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--secrets-output", required=True, type=pathlib.Path)
    parser.add_argument("--signing-public-key-base64", required=True)
    args = parser.parse_args()
    passwords = {
        "platform": os.environ.get("GATE8B_PLATFORM_PASSWORD", ""),
        "tenant": os.environ.get("GATE8B_BOOTSTRAP_PASSWORD", ""),
        "reviewer": os.environ.get("GATE8B_REVIEWER_PASSWORD", ""),
    }
    if not all(passwords.values()):
        print("G9A-R4 bootstrap failed: missing controlled synthetic credentials")
        return 1
    try:
        raw_public = base64.b64decode(args.signing_public_key_base64, validate=True)
        if len(raw_public) != 32:
            raise ValueError("Ed25519 public key must be 32 bytes")
        run_tag = hashlib.sha256(f"{time.time_ns()}:{os.getpid()}".encode()).hexdigest()[:8].upper()
        run_id = stable_ulid("g9a-r4-run-" + run_tag)
        client = ApiClient(args.base_url)
        started = time.perf_counter()
        plan_id, _ = create_platform_prerequisites(client, passwords, run_tag)
        contexts: list[dict[str, Any]] = []
        secrets: list[dict[str, Any]] = []
        for industry, label, display in INDUSTRIES:
            context, secret = create_tenant(client, passwords, plan_id, industry, label, display, run_tag)
            contexts.append(context)
            secrets.append(secret)
        observations = [item.__dict__ for item in client.observations]
        evidence = {
            "schemaVersion": "1.0", "gate": "G9A-R4", "phase": "R4-R2", "runId": run_id,
            "status": "PASS", "classification": "FORMAL_HTTP_COMMERCIAL_AND_THREE_INDUSTRY_BOOTSTRAP",
            "environment": {"mysql": "FORMAL", "redis": "FORMAL", "jar": "COMMERCIAL", "transport": "HTTP"},
            "journeys": contexts, "journeyCount": len(contexts), "observationCount": len(observations),
            "elapsedMs": round((time.perf_counter() - started) * 1000),
            "maxApiDurationMs": max(item["duration_ms"] for item in observations),
            "syntheticSecretBundleSha256": hashlib.sha256(json.dumps(secrets, sort_keys=True).encode()).hexdigest(),
            "signingPublicKeySha256": hashlib.sha256(raw_public).hexdigest(),
            "directDatabaseBusinessWrites": 0, "providerNetworkCalls": 0,
            "realDeviceOrPeripheralCommands": 0, "observations": observations,
        }
        secret_bundle = {"schemaVersion": "1.0", "runId": run_id, "journeys": secrets}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        args.secrets_output.parent.mkdir(parents=True, exist_ok=True)
        args.secrets_output.write_text(json.dumps(secret_bundle, ensure_ascii=False), encoding="utf-8")
        os.chmod(args.secrets_output, 0o600)
        print(f"G9A-R4 bootstrap passed: run={run_id} journeys=3 observations={len(observations)}")
        return 0
    except (JourneyFailure, ValueError, KeyError, TypeError) as error:
        print(f"G9A-R4 bootstrap failed: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
