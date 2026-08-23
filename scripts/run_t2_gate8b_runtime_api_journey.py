#!/usr/bin/env python3
"""在正式 MySQL/Redis 运行时经公开 HTTP API 执行 Gate 8B 合成旅程。

脚本不连接数据库、不读取 Redis、不调用测试后门，也不把令牌、密码或请求正文写入证据。
环境初始化仅使用 RuoYi 与各 Owner 已发布的正式 API。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e"
PLATFORM_TENANT = "000000"
PLATFORM_ROLE_KEY = "platform_admin"

# 新租户只得到本旅程需要的系统用户/角色、Foundation 与 Service 权限。
TENANT_PACKAGE_MENU_IDS = [
    1, 100, 101, 1001, 1002, 1008,
    *range(9200000, 9200005),
    *range(9201780, 9201791),
]
PLATFORM_REVIEWER_MENU_IDS = [9201700, 9201703, 9201709]


class JourneyFailure(RuntimeError):
    """表示正式 API 旅程失败；消息不得携带凭据或完整请求正文。"""


@dataclass
class Observation:
    stage: str
    method: str
    path: str
    http_status: int
    business_code: int | None
    duration_ms: int
    outcome: str


class ApiClient:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None
        self.observations: list[Observation] = []

    def login(self, tenant_id: str, username: str, password: str, stage: str) -> None:
        response = self.call(
            "POST",
            "/auth/login",
            stage,
            body={
                "tenantId": tenant_id,
                "username": username,
                "password": password,
                "clientId": CLIENT_ID,
                "grantType": "password",
            },
            authenticated=False,
        )
        token = response.get("data", {}).get("access_token")
        if not isinstance(token, str) or not token:
            raise JourneyFailure(f"{stage}: 登录未返回 access_token")
        self.token = token

    def call(
        self,
        method: str,
        path: str,
        stage: str,
        *,
        body: Any | None = None,
        headers: dict[str, str] | None = None,
        authenticated: bool = True,
        expect_success: bool = True,
    ) -> dict[str, Any]:
        request_headers = {"Accept": "application/json"}
        if body is not None:
            request_headers["Content-Type"] = "application/json; charset=utf-8"
        if authenticated:
            if not self.token:
                raise JourneyFailure(f"{stage}: 缺少认证令牌")
            request_headers["Authorization"] = f"Bearer {self.token}"
        request_headers.update(headers or {})
        encoded = None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=encoded, headers=request_headers, method=method)
        started = time.perf_counter()
        http_status = 0
        raw = b""
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                http_status = response.status
                raw = response.read()
        except urllib.error.HTTPError as error:
            http_status = error.code
            raw = error.read()
        except Exception as error:  # pragma: no cover - 仅运行时网络失败路径
            raise JourneyFailure(f"{stage}: HTTP 请求失败 ({type(error).__name__})") from error
        duration_ms = max(1, round((time.perf_counter() - started) * 1000))
        try:
            payload = json.loads(raw.decode("utf-8")) if raw else {}
        except Exception as error:
            raise JourneyFailure(f"{stage}: HTTP {http_status} 未返回 JSON") from error
        business_code = payload.get("code") if isinstance(payload, dict) else None
        success = 200 <= http_status < 300 and business_code in (None, 200)
        expected = success if expect_success else not success
        self.observations.append(Observation(
            stage=stage,
            method=method,
            path=path.split("?", 1)[0],
            http_status=http_status,
            business_code=business_code if isinstance(business_code, int) else None,
            duration_ms=duration_ms,
            outcome="PASS" if expected else "FAIL",
        ))
        if not expected:
            message = payload.get("msg", "") if isinstance(payload, dict) else ""
            raise JourneyFailure(f"{stage}: HTTP={http_status}, code={business_code}, msg={str(message)[:160]}")
        return payload


def api_headers(key: str, correlation: str) -> dict[str, str]:
    return {"Idempotency-Key": key, "X-Correlation-ID": correlation}


def data(response: dict[str, Any]) -> Any:
    return response.get("data")


def require_value(value: Any, stage: str) -> Any:
    if value is None or value == "":
        raise JourneyFailure(f"{stage}: 正式 API 未返回稳定身份")
    return value


def find_by(rows: list[dict[str, Any]], key: str, value: Any, stage: str) -> dict[str, Any]:
    for row in rows:
        if row.get(key) == value:
            return row
    raise JourneyFailure(f"{stage}: 未找到 {key}={value}")


def timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).replace(tzinfo=None, microsecond=0).isoformat()


def run(args: argparse.Namespace) -> dict[str, Any]:
    platform_password = os.environ.get("GATE8B_PLATFORM_PASSWORD")
    bootstrap_password = os.environ.get("GATE8B_BOOTSTRAP_PASSWORD")
    reviewer_password = os.environ.get("GATE8B_REVIEWER_PASSWORD")
    if not all((platform_password, bootstrap_password, reviewer_password)):
        raise JourneyFailure("缺少受控合成凭据环境变量")

    client = ApiClient(args.base_url)
    now = datetime.now(timezone.utc)
    started = time.perf_counter()

    # 1. 平台治理：通过正式 RuoYi API 建立职责分离复核角色和技术租户套餐。
    client.login(PLATFORM_TENANT, "admin", platform_password, "platform-admin-login")
    client.call("POST", "/system/role", "platform-reviewer-role-create", body={
        "roleName": "Gate8B平台复核员",
        "roleKey": PLATFORM_ROLE_KEY,
        "roleSort": 2,
        "dataScope": "1",
        "menuCheckStrictly": True,
        "deptCheckStrictly": True,
        "status": "0",
        "menuIds": PLATFORM_REVIEWER_MENU_IDS,
        "remark": "仅用于内部合成职责分离验收",
    })
    role_rows = client.call(
        "GET", "/system/role/list?" + urllib.parse.urlencode({"roleKey": PLATFORM_ROLE_KEY, "pageNum": 1, "pageSize": 20}),
        "platform-reviewer-role-query",
    ).get("rows", [])
    platform_role_id = require_value(find_by(role_rows, "roleKey", PLATFORM_ROLE_KEY, "platform-reviewer-role-query").get("roleId"), "platform-reviewer-role-query")
    client.call("POST", "/system/user", "platform-reviewer-user-create", body={
        "deptId": 103,
        "userName": "gate8b_reviewer",
        "nickName": "Gate8B合成复核员",
        "password": reviewer_password,
        "status": "0",
        "roleIds": [platform_role_id],
        "postIds": [],
        "remark": "内部合成职责分离账号",
    })
    client.call("POST", "/system/tenant/package", "tenant-package-create", body={
        "packageName": "Gate8B内部商业运营",
        "menuIds": TENANT_PACKAGE_MENU_IDS,
        "remark": "内部合成 API 旅程最小权限套餐",
        "menuCheckStrictly": True,
        "status": "0",
    })
    package_rows = data(client.call("GET", "/system/tenant/package/selectList", "tenant-package-query")) or []
    package_id = require_value(find_by(package_rows, "packageName", "Gate8B内部商业运营", "tenant-package-query").get("packageId"), "tenant-package-query")

    # 2. SaaS：套餐/权益和商户开户，审批由独立平台复核员执行。
    plan_body = {
        "planCode": "GATE8B_V1",
        "planName": "Gate8B内部商业套餐",
        "platformPackageId": package_id,
        "accountLimit": 20,
    }
    plan = data(client.call("POST", "/api/v1/saas/plans", "saas-plan-create", body=plan_body,
                            headers=api_headers("g8b-plan-create-001", "g8b-plan-trace-001")))
    plan_id = require_value(plan.get("planId"), "saas-plan-create")
    replay_plan = data(client.call("POST", "/api/v1/saas/plans", "saas-plan-idempotent-replay", body=plan_body,
                                   headers=api_headers("g8b-plan-create-001", "g8b-plan-trace-001")))
    if replay_plan.get("planId") != plan_id:
        raise JourneyFailure("saas-plan-idempotent-replay: 未返回原结果")
    client.call("POST", "/api/v1/saas/plans", "saas-plan-same-key-different-content", body={**plan_body, "planName": "冲突内容"},
                headers=api_headers("g8b-plan-create-001", "g8b-plan-trace-001"), expect_success=False)

    version = data(client.call("POST", f"/api/v1/saas/plans/{plan_id}/versions", "saas-entitlement-version-create", body={
        "versionNo": 1,
        "effectiveAt": timestamp(now - timedelta(days=1)),
        "expiresAt": timestamp(now + timedelta(days=365)),
        "items": [{"featureCode": "SERVICE_OPERATIONS", "enabled": True, "quotaLimit": 1000}],
    }, headers=api_headers("g8b-ent-create-001", "g8b-ent-trace-001")))
    version_id = require_value(version.get("versionId"), "saas-entitlement-version-create")
    client.call("POST", f"/api/v1/saas/entitlements/{version_id}/validate", "saas-entitlement-validate",
                headers=api_headers("g8b-ent-validate-001", "g8b-ent-trace-002"))
    client.login(PLATFORM_TENANT, "gate8b_reviewer", reviewer_password, "platform-reviewer-login-entitlement")
    approved_version = data(client.call("POST", f"/api/v1/saas/entitlements/{version_id}/approve", "saas-entitlement-independent-approve",
                                        headers=api_headers("g8b-ent-approve-001", "g8b-ent-trace-003")))
    if approved_version.get("state") != "APPROVED":
        raise JourneyFailure("saas-entitlement-independent-approve: 状态不是 APPROVED")
    client.login(PLATFORM_TENANT, "admin", platform_password, "platform-admin-login-after-entitlement-approval")
    client.call("POST", f"/api/v1/saas/entitlements/{version_id}/publish", "saas-entitlement-publish",
                headers=api_headers("g8b-ent-publish-001", "g8b-ent-trace-004"))
    client.call("POST", f"/api/v1/saas/entitlements/{version_id}/activate", "saas-entitlement-activate",
                headers=api_headers("g8b-ent-activate-001", "g8b-ent-trace-005"))

    application = data(client.call("POST", "/api/v1/saas/applications", "saas-application-create", body={
        "applicationCode": "GATE8B-APP-001",
        "companyName": "Gate8B虚构便利商户",
        "industry": "CONVENIENCE",
        "planId": plan_id,
    }, headers=api_headers("g8b-app-create-001", "g8b-app-trace-001")))
    application_id = require_value(application.get("application", {}).get("applicationId"), "saas-application-create")
    client.call("POST", f"/api/v1/saas/applications/{application_id}/preflight", "saas-application-preflight",
                headers=api_headers("g8b-app-preflight-001", "g8b-app-trace-002"))
    client.login(PLATFORM_TENANT, "gate8b_reviewer", reviewer_password, "platform-reviewer-login-application")
    approved_application = data(client.call("POST", f"/api/v1/saas/applications/{application_id}/approve", "saas-application-independent-approve",
                                            body={"reason": "内部合成独立复核通过"},
                                            headers=api_headers("g8b-app-approve-001", "g8b-app-trace-003")))
    if approved_application.get("application", {}).get("state") != "APPROVED":
        raise JourneyFailure("saas-application-independent-approve: 状态不是 APPROVED")
    client.login(PLATFORM_TENANT, "admin", platform_password, "platform-admin-login-provision")
    provisioned = data(client.call("POST", f"/api/v1/saas/applications/{application_id}/provision", "saas-tenant-provision", body={
        "contactName": "虚构联系人",
        "contactPhone": "00000000000",
        "bootstrapUsername": "gate8b_tenant_admin",
        "bootstrapPassword": bootstrap_password,
    }, headers=api_headers("g8b-app-provision-001", "g8b-app-trace-004")))
    tenant_id = require_value(provisioned.get("application", {}).get("tenantId"), "saas-tenant-provision")
    client.call("POST", f"/api/v1/saas/applications/{application_id}/initialize", "saas-tenant-initialize",
                headers=api_headers("g8b-app-initialize-001", "g8b-app-trace-005"))
    activated = data(client.call("POST", f"/api/v1/saas/applications/{application_id}/activate", "saas-tenant-activate",
                                 headers=api_headers("g8b-app-activate-001", "g8b-app-trace-006")))
    if activated.get("application", {}).get("state") != "ACTIVE":
        raise JourneyFailure("saas-tenant-activate: 状态不是 ACTIVE")

    # 3. Subscription：创建、激活、续期、受控降级和恢复；全部使用原稳定身份。
    first_start, first_end = now - timedelta(hours=1), now + timedelta(days=30)
    first_term = {
        "contractRef": "G8B-CONTRACT-001",
        "externalOrderRef": "G8B-ORDER-001",
        "startsAt": timestamp(first_start),
        "endsAt": timestamp(first_end),
        "graceEndsAt": timestamp(first_end + timedelta(days=7)),
        "businessTimeZone": "Asia/Shanghai",
        "degradationPolicyVersion": "RECOVERY-V1",
    }
    subscription = data(client.call("POST", f"/api/v1/subscriptions/tenants/{tenant_id}", "subscription-create",
                                    body=first_term, headers=api_headers("g8b-sub-create-001", "g8b-sub-trace-001")))
    subscription_id = require_value(subscription.get("subscription", {}).get("subscriptionId"), "subscription-create")
    replay_subscription = data(client.call("POST", f"/api/v1/subscriptions/tenants/{tenant_id}", "subscription-idempotent-replay",
                                           body=first_term, headers=api_headers("g8b-sub-create-001", "g8b-sub-trace-001")))
    if replay_subscription.get("subscription", {}).get("subscriptionId") != subscription_id:
        raise JourneyFailure("subscription-idempotent-replay: 未返回原结果")
    client.call("POST", f"/api/v1/subscriptions/tenants/{tenant_id}", "subscription-same-key-different-content",
                body={**first_term, "externalOrderRef": "G8B-ORDER-CONFLICT"},
                headers=api_headers("g8b-sub-create-001", "g8b-sub-trace-001"), expect_success=False)
    client.call("POST", f"/api/v1/subscriptions/{subscription_id}/activate", "subscription-activate",
                headers=api_headers("g8b-sub-activate-001", "g8b-sub-trace-002"))
    renew_end = first_end + timedelta(days=30)
    client.call("POST", f"/api/v1/subscriptions/{subscription_id}/renew", "subscription-renew", body={
        "contractRef": "G8B-CONTRACT-002", "externalOrderRef": "G8B-ORDER-002",
        "startsAt": timestamp(first_end), "endsAt": timestamp(renew_end),
        "graceEndsAt": timestamp(renew_end + timedelta(days=7)), "businessTimeZone": "Asia/Shanghai",
    }, headers=api_headers("g8b-sub-renew-001", "g8b-sub-trace-003"))
    suspended = data(client.call("POST", f"/api/v1/subscriptions/{subscription_id}/suspend", "subscription-suspend",
                                 body={"reason": "内部合成受控降级"},
                                 headers=api_headers("g8b-sub-suspend-001", "g8b-sub-trace-004")))
    if suspended.get("accessMode") != "RECOVERY_ONLY":
        raise JourneyFailure("subscription-suspend: 未进入 RECOVERY_ONLY")
    restore_end = now + timedelta(days=90)
    restored = data(client.call("POST", f"/api/v1/subscriptions/{subscription_id}/restore", "subscription-restore", body={
        "contractRef": "G8B-CONTRACT-003", "externalOrderRef": "G8B-ORDER-003",
        "startsAt": timestamp(now - timedelta(minutes=5)), "endsAt": timestamp(restore_end),
        "graceEndsAt": timestamp(restore_end + timedelta(days=7)), "businessTimeZone": "Asia/Shanghai",
    }, headers=api_headers("g8b-sub-restore-001", "g8b-sub-trace-005")))
    if restored.get("accessMode") != "NORMAL":
        raise JourneyFailure("subscription-restore: 未恢复 NORMAL")

    # 4. 新商户正式登录，经 Foundation API 创建组织/门店，再经 Service API 完成运营旅程。
    client.login(tenant_id, "gate8b_tenant_admin", bootstrap_password, "tenant-admin-login")
    tenant_profile = data(client.call("GET", "/system/user/getInfo", "tenant-admin-profile"))
    tenant_admin_user_id = require_value(tenant_profile.get("user", {}).get("userId"), "tenant-admin-profile")
    org = data(client.call("POST", "/api/v1/foundation/org-units", "foundation-org-create", body={
        "parentId": None, "code": "G8B_HQ", "name": "Gate8B虚构总部", "type": "HEADQUARTERS",
    }))
    org_id = require_value(org.get("orgUnitId"), "foundation-org-create")
    store = data(client.call("POST", "/api/v1/foundation/stores", "foundation-store-create", body={
        "orgUnitId": org_id, "platformDeptId": None, "code": "G8B_STORE_01", "name": "Gate8B虚构门店",
        "zoneId": "Asia/Shanghai", "businessDayStart": "06:00:00",
    }))
    store_id = require_value(store.get("storeId"), "foundation-store-create")

    tenant_role_rows = client.call(
        "GET", "/system/role/list?" + urllib.parse.urlencode({"roleKey": "tenant_admin", "pageNum": 1, "pageSize": 20}),
        "tenant-admin-role-query",
    ).get("rows", [])
    tenant_admin_role_id = require_value(find_by(tenant_role_rows, "roleKey", "tenant_admin", "tenant-admin-role-query").get("roleId"), "tenant-admin-role-query")
    client.call("POST", "/system/user", "tenant-reviewer-user-create", body={
        "deptId": tenant_profile.get("user", {}).get("deptId"),
        "userName": "gate8b_tenant_reviewer", "nickName": "Gate8B租户复核员",
        "password": reviewer_password, "status": "0", "roleIds": [tenant_admin_role_id], "postIds": [],
        "remark": "内部合成服务工单独立复核账号",
    })

    catalog = data(client.call("POST", "/api/v1/service/catalogs", "service-catalog-create", body={
        "catalogCode": "G8B_OPENING_V1", "versionNo": 1, "industryTemplate": "CONVENIENCE",
        "name": "Gate8B虚构开店检查",
        "items": [{"itemCode": "CONFIG_READY", "itemName": "配置已复核", "mandatory": True, "sequenceNo": 1}],
    }, headers=api_headers("g8b-svc-catalog-create-001", "g8b-svc-trace-001")))
    catalog_id = require_value(catalog.get("catalog", {}).get("catalogId"), "service-catalog-create")
    client.call("POST", f"/api/v1/service/catalogs/{catalog_id}/publish", "service-catalog-publish",
                headers=api_headers("g8b-svc-catalog-publish-001", "g8b-svc-trace-002"))
    project = data(client.call("POST", "/api/v1/service/projects", "service-project-create", body={
        "storeId": store_id, "catalogId": catalog_id,
        "targetDate": (now.date() + timedelta(days=7)).isoformat(), "ownerUserId": tenant_admin_user_id,
    }, headers=api_headers("g8b-svc-project-create-001", "g8b-svc-trace-003")))
    project_id = require_value(project.get("project", {}).get("projectId"), "service-project-create")
    check_id = require_value(project.get("checks", [{}])[0].get("checkId"), "service-project-create")
    client.call("POST", f"/api/v1/service/projects/{project_id}/commands", "service-project-preflight",
                body={"command": "PREFLIGHT", "reason": "开始内部合成预检"},
                headers={**api_headers("g8b-svc-project-preflight-001", "g8b-svc-trace-004"), "If-Match-Version": "0"})
    client.call("POST", f"/api/v1/service/projects/{project_id}/commands", "service-project-ready",
                body={"command": "MARK_READY", "reason": "内部合成预检通过"},
                headers={**api_headers("g8b-svc-project-ready-001", "g8b-svc-trace-005"), "If-Match-Version": "1"})
    client.call("POST", f"/api/v1/service/projects/{project_id}/commands", "service-project-start",
                body={"command": "START", "reason": "开始内部合成实施"},
                headers={**api_headers("g8b-svc-project-start-001", "g8b-svc-trace-006"), "If-Match-Version": "2"})
    client.call("POST", f"/api/v1/service/projects/{project_id}/checks/{check_id}/complete", "service-project-check-complete",
                body={"reason": "内部合成检查完成"},
                headers={**api_headers("g8b-svc-check-001", "g8b-svc-trace-007"), "If-Match-Version": "0"})
    ticket_body = {
        "storeId": store_id, "projectId": project_id, "serviceType": "IMPLEMENTATION_SUPPORT", "priority": "P2",
        "subject": "Gate8B虚构实施工单", "description": "仅合成资料", "internalTargetMinutes": 240,
    }
    ticket = data(client.call("POST", "/api/v1/service/tickets", "service-ticket-create", body=ticket_body,
                              headers=api_headers("g8b-svc-ticket-create-001", "g8b-svc-trace-006")))
    ticket_id = require_value(ticket.get("ticket", {}).get("ticketId"), "service-ticket-create")
    replay_ticket = data(client.call("POST", "/api/v1/service/tickets", "service-ticket-idempotent-replay", body=ticket_body,
                                     headers=api_headers("g8b-svc-ticket-create-001", "g8b-svc-trace-006")))
    if replay_ticket.get("ticket", {}).get("ticketId") != ticket_id:
        raise JourneyFailure("service-ticket-idempotent-replay: 未返回原结果")
    client.call("POST", "/api/v1/service/tickets", "service-ticket-same-key-different-content",
                body={**ticket_body, "subject": "冲突内容"},
                headers=api_headers("g8b-svc-ticket-create-001", "g8b-svc-trace-006"), expect_success=False)

    def ticket_command(command: str, version: int, key: str, resolution: str | None = None) -> dict[str, Any]:
        return data(client.call("POST", f"/api/v1/service/tickets/{ticket_id}/commands", f"service-ticket-{command.lower()}", body={
            "command": command, "assigneeUserId": tenant_admin_user_id if command == "CLAIM" else None,
            "leaseMinutes": 30 if command in {"CLAIM", "START", "RESOLVE"} else None,
            "reason": "内部合成处置", "resolutionSummary": resolution,
        }, headers={**api_headers(key, f"g8b-svc-{key}"), "If-Match-Version": str(version)}))

    ticket_command("CLAIM", 0, "ticket-claim-001")
    ticket_command("START", 1, "ticket-start-001")
    resolved = ticket_command("RESOLVE", 2, "ticket-resolve-001", "内部合成问题已解决")
    if resolved.get("ticket", {}).get("state") != "RESOLVED":
        raise JourneyFailure("service-ticket-resolve: 状态不是 RESOLVED")
    client.login(tenant_id, "gate8b_tenant_reviewer", reviewer_password, "tenant-reviewer-login")
    closed = ticket_command("CLOSE", 3, "ticket-close-001", "独立复核关闭")
    if closed.get("ticket", {}).get("state") != "CLOSED":
        raise JourneyFailure("service-ticket-close: 状态不是 CLOSED")

    # 5. 由平台管理员演练逻辑停用和受控恢复；租户历史不删除。
    client.login(PLATFORM_TENANT, "admin", platform_password, "platform-admin-login-lifecycle")
    client.call("POST", f"/api/v1/saas/tenants/{tenant_id}/deactivate", "saas-tenant-deactivate",
                body={"reason": "内部合成受控停用"}, headers=api_headers("g8b-tenant-deactivate-001", "g8b-tenant-trace-001"))
    lifecycle = data(client.call("POST", f"/api/v1/saas/tenants/{tenant_id}/restore", "saas-tenant-restore",
                                 body={"reason": "内部合成受控恢复"},
                                 headers=api_headers("g8b-tenant-restore-001", "g8b-tenant-trace-002")))
    if lifecycle.get("lifecycleState") != "ACTIVE":
        raise JourneyFailure("saas-tenant-restore: 生命周期未恢复 ACTIVE")

    elapsed_ms = round((time.perf_counter() - started) * 1000)
    observations = [observation.__dict__ for observation in client.observations]
    identity_material = f"{tenant_id}:{application_id}:{subscription_id}:{store_id}:{ticket_id}".encode("utf-8")
    return {
        "schema_version": "1.0",
        "requirement_id": "T2-E2E-005",
        "evidence_level": "INTERNAL_COMMERCIAL_OPERATIONS_CANDIDATE",
        "environment": {"mysql": "FORMAL_RUNTIME", "redis": "FORMAL_RUNTIME", "transport": "HTTP_REST"},
        "journey": "SAA_TO_SUB_TO_SVC",
        "result": "PASS",
        "direct_database_business_writes": 0,
        "provider_network_calls": 0,
        "real_device_or_peripheral_commands": 0,
        "synthetic_identity_sha256": hashlib.sha256(identity_material).hexdigest(),
        "observation_count": len(observations),
        "elapsed_ms": elapsed_ms,
        "max_api_duration_ms": max(item["duration_ms"] for item in observations),
        "p0_open": 0,
        "p1_open": 0,
        "observations": observations,
        "limitations": [
            "INTERNAL_SYNTHETIC_ONLY",
            "NO_REAL_BILLING_OR_NOTIFICATION",
            "NO_SANDBOX_OR_REAL_FUNDS",
            "NO_REAL_DEVICE_OR_PERIPHERAL",
            "NOT_FULL_ALPHA_OR_PRODUCTION",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        evidence = run(args)
    except JourneyFailure as error:
        print(f"Gate 8B runtime API journey failed: {error}")
        return 1
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Gate 8B runtime API journey passed: {evidence['observation_count']} observations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
