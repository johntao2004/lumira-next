"""Check owned source paths and Java declaration names, excluding generated/vendor output."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {"node_modules", "target", "dist", ".runtime", ".git", ".umi", ".umi-production", ".umi-test"}


def owned(root):
    return [p for p in root.rglob("*") if p.is_file() and not EXCLUDED.intersection(p.relative_to(ROOT).parts)]


def problems():
    errors = []
    files = []
    front = [ROOT / "apps/web/src", *[p / "frontend" for p in (ROOT / "plugins").iterdir() if (p / "frontend").exists()]]
    java = [ROOT / "backend/src/main/java", ROOT / "backend/src/test/java"]
    java += [p for plugin in (ROOT / "plugins").iterdir() for p in (plugin / "backend/src/main/java", plugin / "backend/src/test/java") if p.exists()]
    for root in front:
        for path in owned(root):
            if path.suffix not in {".ts", ".tsx"}:
                continue
            files.append(path)
            if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*(?:\.(?:test|spec))?\.(?:ts|tsx)", path.name):
                errors.append(f"frontend file name: {path.relative_to(ROOT)}")
    for root in java:
        for path in owned(root):
            if path.suffix != ".java":
                continue
            files.append(path)
            source = path.read_text()
            if path.name not in {"package-info.java", "module-info.java"} and not re.fullmatch(r"[A-Z][A-Za-z0-9]*\.java", path.name):
                errors.append(f"Java file name: {path.relative_to(ROOT)}")
            package = re.search(r"^package\s+([\w.]+);", source, re.M)
            if not package and path.name != "module-info.java":
                errors.append(f"Java source requires a package: {path.relative_to(ROOT)}")
            if package and (not re.fullmatch(r"[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)*", package[1]) or package[1].replace(".", "/") != path.parent.relative_to(root).as_posix()):
                errors.append(f"Java package/path mismatch: {path.relative_to(ROOT)}")
            public = re.search(r"^public\s+(?:(?:abstract|final|sealed|non-sealed)\s+)*(?:class|interface|enum|record|@interface)\s+(\w+)", source, re.M)
            if public and public[1] != path.stem:
                errors.append(f"Java public type/file mismatch: {path.relative_to(ROOT)}")
    host_migration_root = ROOT / "backend/src/main/resources/db/migration"
    legacy_upgrade_root = ROOT / "backend/src/main/resources/db/upgrade"
    if legacy_upgrade_root.exists():
        errors.append("legacy global upgrade directory is not allowed")
    migration_roots = [
        host_migration_root,
        *[p / "migrations" for p in (ROOT / "plugins").iterdir() if (p / "migrations").exists()],
    ]
    host_migrations = list(host_migration_root.glob("*.sql"))
    baseline_files = [path for path in host_migrations if path.name.startswith("B")]
    expected_baseline = "B52__platform_initialization.sql"
    if len(baseline_files) != 1 or baseline_files[0].name != expected_baseline:
        errors.append(f"global migration directory requires only {expected_baseline}")
    if {path.name for path in host_migrations} != {expected_baseline}:
        errors.append("global migration directory must contain only its initialization baseline")
    for root in migration_roots:
        for path in root.glob("*.sql"):
            files.append(path)
            if not re.fullmatch(r"[VB][0-9]+(?:[._][0-9]+)*__[a-z0-9]+(?:_[a-z0-9]+)*\.sql", path.name):
                errors.append(f"Flyway file name: {path.relative_to(ROOT)}")
    for root in [ROOT / "scripts", ROOT / "tests"]:
        for path in owned(root):
            if path.suffix not in {".mjs", ".py", ".sh"}:
                continue
            files.append(path)
            if not re.fullmatch(r"[a-z0-9]+(?:[-_][a-z0-9]+)*(?:\.test)?\.(?:mjs|py|sh)", path.name):
                errors.append(f"script/test file name: {path.relative_to(ROOT)}")
    seen = {}
    for path in files:
        name = path.relative_to(ROOT).as_posix()
        previous = seen.setdefault(name.casefold(), name)
        if previous != name:
            errors.append(f"case-sensitive path collision: {previous} / {name}")
    return errors, len(files)


if __name__ == "__main__":
    errors, count = problems()
    if errors:
        print("\n".join(errors), file=sys.stderr)
        sys.exit(1)
    if not count:
        raise SystemExit("source naming check scanned no files")
    print(f"source naming check passed: {count} owned source, test, script and migration files")
