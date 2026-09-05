from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION_ROOT = ROOT / "server" / "ruoyi-modules"
POLICY_VERSION = 202609050090
POLICY_MIGRATION = (
    MIGRATION_ROOT
    / "jshpos-integration"
    / "src"
    / "main"
    / "resources"
    / "db"
    / "migration"
    / "V202609050090__remove_business_foreign_keys.sql"
)
MIGRATION_VERSION = re.compile(r"^V(?P<version>\d+)__.+\.sql$")
CREATE_TABLE = re.compile(
    r"^\s*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(?P<table>[A-Za-z0-9_]+)`?",
    re.IGNORECASE,
)
ALTER_TABLE = re.compile(
    r"^\s*ALTER\s+TABLE\s+`?(?P<table>[A-Za-z0-9_]+)`?",
    re.IGNORECASE,
)
FOREIGN_KEY = re.compile(
    r"CONSTRAINT\s+`?(?P<constraint>[A-Za-z0-9_]+)`?\s+FOREIGN\s+KEY",
    re.IGNORECASE,
)
DROP_FOREIGN_KEY = re.compile(
    r"DROP\s+FOREIGN\s+KEY\s+`?(?P<constraint>[A-Za-z0-9_]+)`?",
    re.IGNORECASE,
)


@dataclass(frozen=True, order=True)
class ForeignKey:
    table: str
    constraint: str


def migration_files() -> list[tuple[int, Path]]:
    result: list[tuple[int, Path]] = []
    for path in MIGRATION_ROOT.glob("jshpos-*/src/main/resources/db/migration/V*.sql"):
        match = MIGRATION_VERSION.match(path.name)
        if match:
            result.append((int(match.group("version")), path))
    return sorted(result, key=lambda item: (item[0], item[1].as_posix()))


def named_foreign_keys(path: Path) -> set[ForeignKey]:
    table: str | None = None
    mode: str | None = None
    found: set[ForeignKey] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if match := CREATE_TABLE.search(line):
            table, mode = match.group("table"), "create"
        elif match := ALTER_TABLE.search(line):
            table, mode = match.group("table"), "alter"

        for match in FOREIGN_KEY.finditer(line):
            if table is None:
                raise ValueError(f"{path.relative_to(ROOT)}:{line_number}: 无法确定外键所属表")
            found.add(ForeignKey(table, match.group("constraint")))

        if mode == "create" and re.match(r"^\s*\)\s*(?:ENGINE|COMMENT|;)", line, re.IGNORECASE):
            table, mode = None, None
        elif mode == "alter" and re.search(r";\s*$", line):
            table, mode = None, None
    return found


def dropped_foreign_keys(path: Path) -> set[ForeignKey]:
    table: str | None = None
    found: set[ForeignKey] = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if match := ALTER_TABLE.search(line):
            table = match.group("table")
        if match := DROP_FOREIGN_KEY.search(line):
            if table is None:
                raise ValueError(f"{path.relative_to(ROOT)}:{line_number}: 无法确定被删除外键所属表")
            key = ForeignKey(table, match.group("constraint"))
            if key in found:
                raise ValueError(f"{path.relative_to(ROOT)}:{line_number}: 重复删除 {key}")
            found.add(key)
        if table is not None and re.search(r";\s*$", line):
            table = None
    return found


def executable_sql(sql: str) -> str:
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.DOTALL)
    sql = re.sub(r"--[^\r\n]*", " ", sql)
    return sql


def validate_no_database_foreign_keys() -> tuple[int, int]:
    files = migration_files()
    if not POLICY_MIGRATION.is_file():
        raise ValueError(f"缺少 {POLICY_MIGRATION.relative_to(ROOT)}")

    historical: set[ForeignKey] = set()
    for version, path in files:
        if version < POLICY_VERSION:
            historical.update(named_foreign_keys(path))

    removed = dropped_foreign_keys(POLICY_MIGRATION)
    missing = sorted(historical - removed)
    unknown = sorted(removed - historical)
    if len(historical) != 309:
        raise ValueError(f"V1—V89 外键基线应为 309，实际为 {len(historical)}")
    if missing or unknown:
        raise ValueError(f"V90 外键清单不闭合：missing={missing[:5]} unknown={unknown[:5]}")

    violations: list[str] = []
    for version, path in files:
        if version <= POLICY_VERSION:
            continue
        sql = executable_sql(path.read_text(encoding="utf-8"))
        if re.search(r"\bFOREIGN\s+KEY\b|\bREFERENCES\s+`?[A-Za-z_]", sql, re.IGNORECASE):
            violations.append(str(path.relative_to(ROOT)))
    if violations:
        raise ValueError("V90 之后禁止新增数据库外键：" + ", ".join(violations))

    base_sql = executable_sql((ROOT / "server/script/sql/ry_vue_5.X.sql").read_text(encoding="utf-8"))
    if re.search(r"\bFOREIGN\s+KEY\b|\bREFERENCES\s+`?[A-Za-z_]", base_sql, re.IGNORECASE):
        raise ValueError("RuoYi 基础初始化 SQL 不得新增数据库外键")
    return len(historical), len({item.table for item in historical})


def main() -> None:
    try:
        count, tables = validate_no_database_foreign_keys()
    except ValueError as error:
        print(f"NO-FK POLICY ERROR: {error}", file=sys.stderr)
        raise SystemExit(1) from error
    print(f"NO-FK POLICY OK: V90 removes {count} foreign keys from {tables} MySQL business tables")


if __name__ == "__main__":
    main()
