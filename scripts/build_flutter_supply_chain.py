from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import unquote, urlparse


def resolve_uri(uri: str, base: Path) -> Path:
    parsed = urlparse(uri)
    if parsed.scheme == "file":
        value = unquote(parsed.path)
        if re.match(r"^/[A-Za-z]:/", value):
            value = value[1:]
        return Path(value)
    return (base / unquote(uri)).resolve()


def find_license(root: Path) -> Path | None:
    current = root
    for _ in range(4):
        for name in ("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "COPYING.txt"):
            candidate = current / name
            if candidate.is_file():
                return candidate
        if current.parent == current:
            break
        current = current.parent
    return None


def classify(text: str) -> str:
    lower = text.lower()
    if "apache license" in lower and "version 2.0" in lower:
        return "Apache-2.0"
    if "permission is hereby granted, free of charge" in lower:
        return "MIT"
    if "redistribution and use in source and binary forms" in lower:
        return "BSD-3-Clause" if "neither the name" in lower else "BSD-2-Clause"
    if "mozilla public license" in lower and "2.0" in lower:
        return "MPL-2.0"
    if "isc license" in lower or "permission to use, copy, modify, and/or distribute" in lower:
        return "ISC"
    if "gnu affero general public license" in lower:
        return "AGPL"
    if "gnu general public license" in lower:
        return "GPL"
    return "UNKNOWN-REVIEW"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deps", type=Path, required=True)
    parser.add_argument("--package-config", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    deps = json.loads(args.deps.read_text(encoding="utf-8"))
    config = json.loads(args.package_config.read_text(encoding="utf-8"))
    roots = {item["name"]: resolve_uri(item["rootUri"], args.package_config.parent)
             for item in config["packages"]}
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=True)
    inventory = []
    components = []
    dependencies = []
    missing_external = []
    prohibited = []
    root_name = deps["root"]
    for package in sorted(deps["packages"], key=lambda item: item["name"]):
        name = package["name"]
        version = package.get("version") or "unknown"
        source = package.get("source", "unknown")
        root = roots.get(name)
        license_path = find_license(root) if root else None
        text = license_path.read_text(encoding="utf-8", errors="replace") if license_path else ""
        license_id = classify(text) if text else "UNKNOWN-REVIEW"
        internal = name == root_name or source in {"root", "path"}
        if internal and license_id == "UNKNOWN-REVIEW":
            license_id = "LicenseRef-Proprietary-Internal"
        if not internal and not license_path:
            missing_external.append(name)
        if license_id in {"AGPL", "GPL"}:
            prohibited.append(f"{name}:{license_id}")
        license_hash = hashlib.sha256(text.encode("utf-8")).hexdigest() if text else None
        inventory.append({
            "name": name,
            "version": version,
            "source": source,
            "license": license_id,
            "licenseSha256": license_hash,
            "internal": internal,
        })
        ref = f"pkg:pub/{name}@{version}"
        component = {
            "type": "application" if name == root_name else "library",
            "bom-ref": ref,
            "name": name,
            "version": version,
            "purl": ref,
            "licenses": [{"license": {"id": license_id}}],
        }
        if license_hash:
            component["hashes"] = [{"alg": "SHA-256", "content": license_hash}]
        components.append(component)
        dependencies.append({
            "ref": ref,
            "dependsOn": [f"pkg:pub/{child}@{next((p.get('version') for p in deps['packages'] if p['name'] == child), 'unknown')}"
                          for child in package.get("dependencies", [])],
        })
    if missing_external:
        raise SystemExit(f"FLUTTER LICENSE ERROR: external packages missing license text: {missing_external}")
    if prohibited:
        raise SystemExit(f"FLUTTER LICENSE ERROR: prohibited strong-copyleft packages: {prohibited}")

    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:00000000-0000-4000-8000-000000000002",
        "version": 1,
        "metadata": {"component": next(item for item in components if item["name"] == root_name)},
        "components": components,
        "dependencies": dependencies,
    }
    (output / "flutter-cyclonedx.json").write_text(
        json.dumps(bom, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "flutter-license-inventory.json").write_text(
        json.dumps({"schemaVersion": "1.0", "packages": inventory,
                    "prohibitedStrongCopyleft": 0, "missingExternalLicenses": 0},
                   ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"FLUTTER SUPPLY CHAIN OK: packages={len(inventory)} prohibited=0 missingExternal=0")


if __name__ == "__main__":
    main()
