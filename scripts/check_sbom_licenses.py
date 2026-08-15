from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any


def component_licenses(component: dict[str, Any]) -> set[str]:
    result: set[str] = set()
    for entry in component.get("licenses", []):
        license_data = entry.get("license", {})
        value = license_data.get("id") or license_data.get("name") or entry.get("expression")
        if value:
            result.add(str(value))
    return result


def is_restricted(license_name: str) -> bool:
    upper = license_name.upper()
    return (
        "LGPL" in upper
        or "LESSER GENERAL PUBLIC LICENSE" in upper
        or upper.startswith("GPL-")
        or "GNU GENERAL PUBLIC LICENSE" in upper
    )


def is_forbidden(license_name: str) -> bool:
    upper = license_name.upper()
    if "AGPL" in upper or "AFFERO GENERAL PUBLIC LICENSE" in upper:
        return True
    if upper.startswith("GPL-3"):
        return True
    return False


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: check_sbom_licenses.py <cyclonedx-bom.json> <reviewed-license-allowlist.json>"
        )

    sbom_path = Path(sys.argv[1])
    policy_path = Path(sys.argv[2])
    sbom = load_json(sbom_path)
    policy = load_json(policy_path)

    approvals: dict[str, dict[str, Any]] = {}
    for approval in policy.get("approvals", []):
        key = f"{approval['coordinate']}:{approval['version']}"
        if key in approvals:
            raise SystemExit(f"LICENSE POLICY ERROR: duplicate approval {key}")
        approvals[key] = approval

    seen: set[str] = set()
    errors: list[str] = []
    reviewed = 0
    for component in sbom.get("components", []):
        coordinate = f"{component.get('group', '')}:{component.get('name', '')}"
        key = f"{coordinate}:{component.get('version', '')}"
        licenses = component_licenses(component)
        forbidden = sorted(item for item in licenses if is_forbidden(item))
        if forbidden:
            errors.append(f"{key} contains forbidden license(s): {', '.join(forbidden)}")
            continue

        restricted = {item for item in licenses if is_restricted(item)}
        if not restricted:
            continue

        approval = approvals.get(key)
        if approval is None:
            errors.append(
                f"{key} contains unreviewed restricted license(s): {', '.join(sorted(restricted))}"
            )
            continue

        approved = set(approval.get("approved_restricted_licenses", []))
        if restricted != approved:
            errors.append(
                f"{key} restricted license set changed: observed={sorted(restricted)}, approved={sorted(approved)}"
            )
            continue
        if not approval.get("selected_license") or not approval.get("basis"):
            errors.append(f"{key} approval lacks selected_license or basis")
            continue
        seen.add(key)
        reviewed += 1

    stale = sorted(set(approvals) - seen)
    if stale:
        errors.append("approved components missing or changed in SBOM: " + ", ".join(stale))

    if errors:
        for error in errors:
            print(f"LICENSE POLICY ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)

    print(
        f"LICENSE POLICY OK: {reviewed} exact restricted-license components reviewed; "
        "no unapproved GPL/AGPL or restricted component"
    )


if __name__ == "__main__":
    try:
        main()
    except (OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        print(f"LICENSE POLICY ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
