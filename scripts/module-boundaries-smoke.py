import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "backend/src/main/java/com/lumira/platform"
WEB_ROOT = ROOT / "apps/web/src"


def fail(message: str) -> None:
    print(f"module boundary check failed: {message}", file=sys.stderr)
    raise SystemExit(1)


java_files = list(JAVA_ROOT.rglob("*.java"))
backend_modules = {}
for path in java_files:
    source = path.read_text()
    for match in re.finditer(
        r'new PlatformModule\("(?P<id>[a-z][a-z0-9-]{0,99})",\s*"[^"]+",\s*"(?P<version>[^"]+)",\s*List\.of\((?P<dependencies>[^)]*)\)(?:,\s*(?P<required>true|false))?\)',
        source,
        re.DOTALL,
    ):
        module = match.groupdict()
        dependencies = tuple(re.findall(r'"([a-z][a-z0-9-]{0,99})"', module["dependencies"]))
        descriptor = (module["version"], module["required"] == "true", dependencies)
        if module["id"] in backend_modules and backend_modules[module["id"]] != descriptor:
            fail(f"backend module descriptor is declared inconsistently: {module['id']}")
        backend_modules[module["id"]] = descriptor
descriptors = set(backend_modules)
if not backend_modules:
    fail("no backend module descriptors found")

# Keep the static extraction check equivalent to ModuleRegistry's runtime
# validation. This lets CI reject an invalid graph before Spring starts and
# keeps the frontend manifest comparison below meaningful.
for module_id, (_, required, dependencies) in backend_modules.items():
    for dependency in dependencies:
        if dependency not in backend_modules:
            fail(f"backend module has missing dependency: {module_id} -> {dependency}")
        if required and not backend_modules[dependency][1]:
            fail(f"required backend module depends on optional module: {module_id} -> {dependency}")

visiting = set()
visited = set()


def visit_module(module_id: str) -> None:
    if module_id in visited:
        return
    if module_id in visiting:
        fail(f"backend module dependency cycle includes: {module_id}")
    visiting.add(module_id)
    for dependency in backend_modules[module_id][2]:
        visit_module(dependency)
    visiting.remove(module_id)
    visited.add(module_id)


for module_id in backend_modules:
    visit_module(module_id)

# Keep the static extraction check equivalent to ModuleRegistry's runtime
# validation. This lets CI reject an invalid graph before Spring starts and
# keeps the frontend manifest comparison below meaningful.
for module_id, (_, required, dependencies) in backend_modules.items():
    for dependency in dependencies:
        if dependency not in backend_modules:
            fail(f"backend module has missing dependency: {module_id} -> {dependency}")
        if required and not backend_modules[dependency][1]:
            fail(f"required backend module depends on optional module: {module_id} -> {dependency}")

visiting = set()
visited = set()


def visit_module(module_id: str) -> None:
    if module_id in visited:
        return
    if module_id in visiting:
        fail(f"backend module dependency cycle includes: {module_id}")
    visiting.add(module_id)
    for dependency in backend_modules[module_id][2]:
        visit_module(dependency)
    visiting.remove(module_id)
    visited.add(module_id)


for module_id in backend_modules:
    visit_module(module_id)

ownership_path = ROOT / "backend/src/main/resources/db/module-ownership.json"
try:
    ownership = json.loads(ownership_path.read_text())
    table_owners = ownership["tableOwners"]
    object_owners = ownership["objectOwners"]
    migration_owners = ownership.get("migrationOwners", {})
except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
    fail(f"database module ownership manifest is invalid: {error}")
migration_dir = ROOT / "backend/src/main/resources/db/migration"
baseline_name = ownership.get("baseline")
active_migrations = {path.name for path in migration_dir.glob("*.sql")}
if baseline_name not in active_migrations:
    fail(f"database baseline is missing: {baseline_name}")
if set(migration_owners) != active_migrations - {baseline_name}:
    fail(
        "database migration ownership is incomplete: "
        f"missing={sorted(active_migrations - {baseline_name} - set(migration_owners))} "
        f"unknown={sorted(set(migration_owners) - active_migrations)}"
    )
sql_migrations = "\n".join(path.read_text() for path in migration_dir.glob("*.sql"))
migration_tables = set(re.findall(
    r"CREATE TABLE(?:\s+IF NOT EXISTS)?\s+(?:public\.)?(platform_[a-z0-9_]+)", sql_migrations, re.IGNORECASE
))
manifest_tables = set(table_owners)
if migration_tables != manifest_tables:
    fail(
        "database table ownership is incomplete: "
        f"missing={sorted(migration_tables - manifest_tables)} "
        f"unknown={sorted(manifest_tables - migration_tables)}"
    )
migration_objects = set(re.findall(
    r"CREATE (?:OR REPLACE )?(?:VIEW|FUNCTION)\s+(?:public\.)?([a-z0-9_]+)",
    sql_migrations,
    re.IGNORECASE,
))
manifest_objects = set(object_owners)
if migration_objects != manifest_objects:
    fail(
        "database object ownership is incomplete: "
        f"missing={sorted(migration_objects - manifest_objects)} "
        f"unknown={sorted(manifest_objects - migration_objects)}"
    )
valid_database_owners = descriptors | {"kernel"}
for name, owner in {**table_owners, **object_owners, **migration_owners}.items():
    if owner not in valid_database_owners:
        fail(f"database object {name} has unknown module owner: {owner}")

# Package ownership is intentionally explicit while the codebase is still a
# modular monolith. The package-to-module map is the extraction seam: moving a
# package to a different Maven module later must not change its HTTP contract.
package_owners = {
    "identity": "identity",
    "ai": "platform",
    "audit": "audit-console",
    "feedback": "feedback",
    "sensitive": "sensitive-words",
    "localization": "localization",
    "mail": "communication",
    "sms": "communication",
    "communication": "communication",
    "dictionaries": "dictionaries",
    "drafts": "identity",
    "events": "events",
    "files": "files",
    "moduleadmin": "platform",
    "monitoring": "monitoring",
    "navigation": "navigation",
    "notifications": "notifications",
    "organizations": "directory",
    "roles": "directory",
    "settings": "settings",
    "users": "directory",
    "directory": "directory",
    "web": "platform",
    "modules": "kernel",
    "content": "kernel",
    "common": "kernel",
}
for path in java_files:
    relative_parts = path.relative_to(JAVA_ROOT).parts
    # The Spring Boot launcher is the composition root; it is allowed to know
    # every module and is checked separately by the application build.
    if len(relative_parts) == 1:
        continue
    source_owner = package_owners.get(relative_parts[0])
    if source_owner is None:
        fail(f"backend package has no module owner: {path.relative_to(ROOT)}")
    descriptor = backend_modules.get(source_owner)
    dependencies = set(descriptor[2]) if descriptor else set()
    for target_package in set(re.findall(r"com\.lumira\.platform\.([a-z][a-z0-9_]*)\.", path.read_text())):
        target_owner = package_owners.get(target_package)
        if target_owner is None:
            fail(f"unknown backend package owner {target_package}: {path.relative_to(ROOT)}")
        if target_owner not in {source_owner, "kernel"} and target_owner not in dependencies:
            fail(
                f"undeclared backend module dependency {source_owner} -> {target_owner}: "
                f"{path.relative_to(ROOT)}"
            )

central = (JAVA_ROOT / "modules/ModuleConfiguration.java").read_text()
if "new PlatformModule(" in central:
    fail("the kernel configuration must not own feature descriptors")

for path in JAVA_ROOT.rglob("*Controller.java"):
    source = path.read_text()
    owners = re.findall(r'@(?:com\.lumira\.platform\.modules\.)?ModuleEndpoint\("([^"]+)"\)', source)
    if not owners:
        fail(f"controller has no module owner: {path.relative_to(ROOT)}")
    unknown = set(owners) - descriptors
    if unknown:
        fail(f"controller references unknown module {sorted(unknown)}: {path.relative_to(ROOT)}")
    relative = path.relative_to(JAVA_ROOT).as_posix()
    expected_owner = {
        # Session administration is an identity implementation exposed by the
        # directory bounded context; keep the exception explicit until the
        # implementation package is moved without changing its API.
        "identity/OnlineSessionController.java": "directory",
    }.get(relative, package_owners[relative.split("/", 1)[0]])
    if set(owners) != {expected_owner}:
        fail(
            f"controller module does not match its package owner: {relative} "
            f"expected={expected_owner} actual={sorted(set(owners))}"
        )

for path in (JAVA_ROOT / "common").glob("*.java"):
    source = path.read_text()
    if re.search(r"(?:MailDeliveryStore|SmsDeliveryStore)", source):
        fail(f"common layer depends on a concrete delivery module: {path.relative_to(ROOT)}")
    if path.name.endswith("Port.java") and re.search(r"(?:import\s+org\.springframework\.|import\s+org\.springframework\.jdbc\.|import\s+jakarta\.servlet\.)", source):
        fail(f"kernel port leaks framework or persistence types: {path.relative_to(ROOT)}")

for path in java_files:
    if "/audit/" not in str(path) and "AuditRepository" in path.read_text():
        fail(f"feature leaks the audit query repository instead of AuditPort: {path.relative_to(ROOT)}")

# Public module APIs must compile without the web or persistence adapters.
# Jackson annotations are allowed for wire compatibility; Spring MVC/JDBC
# types would make later Maven extraction depend on the application module.
for api_root in (JAVA_ROOT / "identity/api", JAVA_ROOT / "dictionaries/api", JAVA_ROOT / "notifications/api"):
    for path in api_root.glob("*.java"):
        source = path.read_text()
        if re.search(r"(?:import\s+org\.springframework\.|org\.springframework\.jdbc\.|import\s+jakarta\.servlet\.)", source):
            fail(f"public module API leaks framework or persistence types: {path.relative_to(ROOT)}")

for path in (JAVA_ROOT / "identity").glob("*.java"):
    source = path.read_text()
    if re.search(r"(?:import\s+com\.lumira\.platform\.(?:mail|sms)\.|com\.lumira\.platform\.(?:mail|sms)\.)", source):
        fail(f"identity depends on a concrete communication implementation: {path.relative_to(ROOT)}")

for path in (JAVA_ROOT / "feedback").glob("*.java"):
    source = path.read_text()
    if re.search(r"(?:FileService|FileRepository|FileUploadRateLimiter|ImageNormalizer|SensitiveWordService|NotificationService|DictionaryLookupRepository)", source):
        fail(f"feedback depends on a private cross-module implementation: {path.relative_to(ROOT)}")

for package in ("mail", "sms"):
    for path in (JAVA_ROOT / package).glob("*.java"):
        source = path.read_text()
        if re.search(r"(?:import\s+com\.lumira\.platform\.settings\.|com\.lumira\.platform\.settings\.)", source):
            fail(f"communication adapter depends on settings implementation: {path.relative_to(ROOT)}")

# Identity is the lower-level required module.  Its public dependencies must
# be kernel contracts or its own api package; otherwise the descriptor graph
# can claim independence while Java silently rebuilds a cycle through a
# settings/file/dictionary implementation.
for path in (JAVA_ROOT / "identity").glob("*.java"):
    source = path.read_text()
    forbidden = re.findall(
        r"(?:import\s+com\.lumira\.platform\.([a-z][a-z0-9_]*)\.|"
        r"com\.lumira\.platform\.([a-z][a-z0-9_]*)\.)",
        source,
    )
    forbidden = {next(value for value in pair if value) for pair in forbidden}
    forbidden -= {"common", "identity", "modules"}
    if forbidden:
        fail(f"identity leaks concrete feature packages {sorted(forbidden)}: {path.relative_to(ROOT)}")

# Platform adapters may depend on identity's contracts, but not on private
# identity implementations.  This is what keeps later Maven extraction
# mechanical instead of turning into a package rename exercise.
for path in java_files:
    if "/identity/" in str(path):
        continue
    source = path.read_text()
    for target in re.findall(r"(?:import\s+)?com\.lumira\.platform\.identity\.([A-Za-z0-9_.]+)", source):
        if not target.startswith("api."):
            fail(f"feature imports private identity implementation {target}: {path.relative_to(ROOT)}")

required_adapters = {
    "ProfileSchemaPort": "settings/ProfileSchemaReader.java",
    "ProfileDictionaryPort": "dictionaries/DictionaryLookupService.java",
    "AvatarStatusPort": "files/AvatarRepository.java",
    "AccountOwnerPort": "identity/ProfileRepository.java",
    "SecurityPolicyPort": "settings/SecuritySettingsService.java",
    "PasskeyPolicyPort": "settings/PasskeySettingsService.java",
    "MaintenancePolicyPort": "settings/MaintenanceService.java",
    "ContentPolicyPort": "sensitive/SensitiveWordService.java",
    "FileContentPort": "files/FileService.java",
    "NotificationProducerPort": "notifications/NotificationService.java",
    "DictionaryLookupPort": "dictionaries/DictionaryLookupService.java",
    "UploadRateLimitPort": "files/FileUploadRateLimiter.java",
    "MailConfigurationPort": "settings/SmtpSettingsService.java",
    "SmsConfigurationPort": "settings/SmsSettingsService.java",
    "SecretStorePort": "settings/SecretStore.java",
    "AiUserDirectoryPort": "users/AiUserDirectoryAdapter.java",
}
for port, relative in required_adapters.items():
    if port not in (JAVA_ROOT / relative).read_text():
        fail(f"missing adapter for module port {port}: {relative}")

for relative in ("mail/MailDeliveryStore.java", "sms/SmsDeliveryStore.java"):
    source = (JAVA_ROOT / relative).read_text()
    if "extends DurableDeliveryStore" not in source:
        fail(f"delivery store bypasses the shared communication state machine: {relative}")

for relative in ("mail/MailDeliveryWorker.java", "sms/SmsDeliveryWorker.java"):
    if "extends DurableDeliveryWorker" not in (JAVA_ROOT / relative).read_text():
        fail(f"delivery worker bypasses the shared communication orchestration: {relative}")

for relative in ("moduleadmin/ModuleAccessInterceptor.java", "moduleadmin/ModuleAccessConfiguration.java"):
    source = (JAVA_ROOT / relative).read_text()
    if "ModuleStateStore" in source:
        fail(f"HTTP module gate depends on the state storage implementation: {relative}")

for relative in ("navigation/NavigationMenuService.java", "web/PlatformController.java"):
    source = (JAVA_ROOT / relative).read_text()
    if "ModuleStateStore" in source:
        fail(f"platform consumer depends on the module state storage implementation: {relative}")

if "ModuleStateStore" in (JAVA_ROOT / "modules/ModulePackageService.java").read_text():
    fail("module package lifecycle depends on the module state storage implementation")

if "DictionaryLookupRepository" in (JAVA_ROOT / "settings/ProfileFieldsController.java").read_text():
    fail("settings controller depends on the dictionary storage implementation")

if "ModuleStateStore" in (JAVA_ROOT / "moduleadmin/ModuleAdminController.java").read_text():
    fail("module administration controller exposes the state storage model")

workspace = (WEB_ROOT / "platform/shell/workspace.tsx").read_text()
if "const featurePages" in workspace:
    fail("workspace still owns the feature page map")
for line in workspace.splitlines():
    if 'from "@/features/' in line and not any(
        allowed in line for allowed in ("@/features/account/", "@/features/notifications/notifications-button")
    ):
        fail("workspace imports a business page directly")

registry = (WEB_ROOT / "platform/modules/registry.tsx").read_text()
manifest_paths = list((WEB_ROOT / "features").rglob("manifest.ts"))
manifest_sources = "".join(path.read_text() for path in manifest_paths)

# Resource tables share one paging contract.  Catch a feature silently
# reintroducing a divergent local default before it becomes another CRUD
# implementation fork.
inline_list_default = re.compile(
    r'useState(?:<ListRequest>)?\s*\(\s*\{\s*page\s*:\s*1\s*,\s*'
    r'pageSize\s*:\s*20\s*,\s*search\s*:\s*(["\'])\s*\1\s*\}\s*\)',
    re.DOTALL,
)
for path in [*WEB_ROOT.joinpath("features").rglob("*.tsx"), *WEB_ROOT.joinpath("platform/components").rglob("*.tsx")]:
    source = path.read_text()
    if "ResourceTable" in source and inline_list_default.search(source):
        fail(f"resource table duplicates list request defaults: {path.relative_to(ROOT)}")

list_hook = (WEB_ROOT / "platform/routing/use-list-request.ts").read_text()
if '"../components/resource-table"' in list_hook or "'../components/resource-table'" in list_hook:
    fail("routing list state must depend on the API pagination contract, not the table component")

transport = (WEB_ROOT / "platform/api/client.ts").read_text()
for domain_type in ("CurrentUser", "PasskeyOptions", "PasskeyCredential", "ModuleInfo", "ModulePackage", "NavigationMenu", "UserSummary"):
    if re.search(rf"(?:interface|type)\s+{domain_type}\b", transport):
        fail(f"API transport client owns a domain DTO: {domain_type}")

pagination = (WEB_ROOT / "platform/api/pagination.ts").read_text()
if re.search(r"(?:from|import)\s+[^\n]*(?:antd|react|resource-table)", pagination):
    fail("API pagination contracts must not depend on UI components")

# A manifest must stay cheap to import. Page modules are deliberately lazy;
# otherwise the registry would be modular only on paper while the initial
# client bundle still pulled every feature into the workspace shell.
for relative in (
    "platform/module.tsx",
    "feedback/module.tsx",
    "audit/module.tsx",
    "sensitive/module.tsx",
    "localization/module.tsx",
    "communication/module.tsx",
):
    source = (WEB_ROOT / "features" / relative).read_text()
    if re.search(r'import\s+\{[^}]*Page[^}]*\}\s+from\s+["\']', source):
        fail(f"frontend module statically imports a page: {relative}")
    if "lazy(" not in source:
        fail(f"frontend module does not lazy-load its pages: {relative}")

frontend_modules = {}
for path in manifest_paths:
    source = path.read_text()
    match = re.search(
        r'id:\s*"(?P<id>[a-z][a-z0-9-]{0,99})".*?version:\s*"(?P<version>[^"]+)".*?required:\s*(?P<required>true|false).*?dependencies:\s*\[(?P<dependencies>[^]]*)\]',
        source,
        re.DOTALL,
    )
    if not match:
        fail(f"frontend manifest is not machine-readable: {path.relative_to(ROOT)}")
    module = match.groupdict()
    dependencies = tuple(re.findall(r'"([a-z][a-z0-9-]{0,99})"', module["dependencies"]))
    descriptor = (module["version"], module["required"] == "true", dependencies)
    if module["id"] in frontend_modules and frontend_modules[module["id"]] != descriptor:
        fail(f"frontend module manifest is declared inconsistently: {module['id']}")
    frontend_modules[module["id"]] = descriptor
if backend_modules != frontend_modules:
    fail(f"backend/frontend module descriptors drift: backend={sorted(backend_modules)} frontend={sorted(frontend_modules)}")
for module_id in ("identity", "platform", "communication", "audit-console", "feedback", "sensitive-words", "localization"):
    if f'id: "{module_id}"' not in registry and f'id: "{module_id}"' not in "".join(
        (path.read_text() for path in (WEB_ROOT / "features").rglob("module.tsx"))
    ) + manifest_sources:
        fail(f"frontend module descriptor is missing: {module_id}")
if "Duplicate frontend route" not in registry or "Uncatalogued frontend page" not in registry:
    fail("frontend registry does not enforce route/page ownership")

print(
    "module boundary check passed: "
    f"{len(descriptors)} backend descriptors, "
    f"{len(list(JAVA_ROOT.rglob('*Controller.java')))} owned controllers, "
    "frontend registry contract"
)
