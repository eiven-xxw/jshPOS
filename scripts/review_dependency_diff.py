from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import PurePosixPath


SHA = re.compile(r"^[0-9a-fA-F]{40}$")


def is_dependency_input(path: str) -> bool:
    item = PurePosixPath(path)
    name = item.name
    if name in {
        "pom.xml",
        "package.json",
        "package-lock.json",
        "pnpm-lock.yaml",
        "yarn.lock",
        "pubspec.yaml",
        "pubspec.lock",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradle-wrapper.properties",
        "libs.versions.toml",
        "Dockerfile",
    }:
        return True
    return (
        path == ".github/dependabot.yml"
        or path == "docs/compliance/reviewed-license-allowlist.json"
        or path.startswith(".github/workflows/")
        or path.startswith("infra/compose/")
    )


def main() -> None:
    if len(sys.argv) != 3 or not all(SHA.fullmatch(item) for item in sys.argv[1:]):
        raise SystemExit("usage: review_dependency_diff.py <base-40-char-sha> <head-40-char-sha>")

    base, head = sys.argv[1:]
    result = subprocess.run(
        ["git", "diff", "--name-status", "--find-renames", base, head, "--"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    changed: list[dict[str, str]] = []
    for raw_line in result.stdout.splitlines():
        fields = raw_line.split("\t")
        if not fields:
            continue
        status = fields[0]
        path = fields[-1].replace("\\", "/")
        if is_dependency_input(path):
            changed.append({"status": status, "path": path})

    print(
        json.dumps(
            {
                "base": base.lower(),
                "head": head.lower(),
                "changed_dependency_inputs": changed,
                "changed_dependency_input_count": len(changed),
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError, UnicodeError) as exc:
        print(f"DEPENDENCY DIFF ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
