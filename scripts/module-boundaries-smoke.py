"""以可执行约束检查前后端清单、公共依赖、宿主装配、懒加载和 DTO 边界，在运行前暴露依赖方向或声明漂移。
Check frontend and backend manifests, public dependencies, host composition, lazy loading and DTO boundaries through executable constraints to expose dependency or declaration drift before runtime."""
import json
from pathlib import Path
import re
import sys
from module_boundaries import parse_backend_modules, validate_backend_module_graph
from plugin_boundaries import validate_plugin_sources

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "backend/src/main/java/com/lumira/platform"
WEB_ROOT = ROOT / "apps/web/src"


def fail(message: str) -> None:
    print(f"module boundary check failed: {message}", file=sys.stderr)
    raise SystemExit(1)


try:
    validate_plugin_sources(ROOT)
except (ValueError, OSError, KeyError) as error:
    fail(str(error))

PLUGIN_ROOTS = [p / "backend/src/main/java/com/lumira/platform" for p in (ROOT / "plugins").iterdir() if (p / "plugin.json").exists()]
PLUGIN_ROOTS.append(ROOT / "plugins/directory/backend/src/main/java/com/lumira/plugins/directory")
PLUGIN_ROOTS.append(ROOT / "plugins/file-retention/backend/src/main/java/com/lumira/plugins")
PLUGIN_ROOTS.append(ROOT / "plugins/file-console/backend/src/main/java/com/lumira/plugins")
PLUGIN_ROOTS.append(ROOT / "plugins/site-console/backend/src/main/java/com/lumira/plugins")
PLUGIN_ROOTS.append(ROOT / "plugins/delivery-settings/backend/src/main/java/com/lumira/plugins")
PLUGIN_ROOTS.append(ROOT / "plugins/profile-console/backend/src/main/java/com/lumira/plugins")
PLUGIN_ROOTS.append(ROOT / "plugins/personal-files/backend/src/main/java/com/lumira/plugins")
PLUGIN_ROOTS.append(ROOT / "plugins/authentication-console/backend/src/main/java/com/lumira/plugins")
SDK_ROOTS = [p / "src/main/java/com/lumira/platform" for p in (ROOT / "framework").iterdir() if p.is_dir()]
def java_relative(path):
    for base in [JAVA_ROOT, *PLUGIN_ROOTS, *SDK_ROOTS]:
        if path.is_relative_to(base): return path.relative_to(base)
    raise ValueError(path)
def java_path(relative):
    for base in [JAVA_ROOT, *PLUGIN_ROOTS, *SDK_ROOTS]:
        if (base / relative).exists(): return base / relative
    if relative == "navigation/NavigationMenuService.java":
        return ROOT / "plugins/navigation-console/backend/src/main/java/com/lumira/plugins/navigationconsole/NavigationMenuService.java"
    return JAVA_ROOT / relative
java_files = [p for base in [JAVA_ROOT, *PLUGIN_ROOTS, *SDK_ROOTS] for p in base.rglob("*.java")]
for path in java_files:
    if path.name.endswith("Controller.java") and re.search(
        r"public\s+(?:[\w.]+<)?\w+Repository\.\w+", path.read_text()
    ):
        fail(f"controller exposes a persistence record; add an API DTO: {path}")

try:
    host_modules = parse_backend_modules(path.read_text() for path in JAVA_ROOT.rglob("*.java"))
    backend_modules = parse_backend_modules(path.read_text() for path in java_files)
    # 运行时插件由 PluginRuntime.registerCatalog 根据 plugin.json 注册到 ModuleRegistry；静态图应纳入相同描述符，并与插件侧源码描述符对照。
    # Runtime plugins enter ModuleRegistry from plugin.json through
    # PluginRuntime.registerCatalog; include the same descriptors in the
    # static graph and compare any plugin-side source descriptors with it.
    plugin_ids = set()
    for manifest_path in sorted((ROOT / "plugins").glob("*/plugin.json")):
        manifest = json.loads(manifest_path.read_text())
        if not isinstance(manifest, dict):
            fail(f"invalid plugin module descriptor: {manifest_path.relative_to(ROOT)}")
        module_id = manifest.get("id")
        dependencies = manifest.get("dependencies", [])
        if (
            not isinstance(module_id, str)
            or not re.fullmatch(r"[a-z][a-z0-9-]{0,99}", module_id)
            or not isinstance(manifest.get("version"), str)
            or not manifest["version"].strip()
            or not isinstance(manifest.get("name"), str)
            or not manifest["name"].strip()
            or not isinstance(dependencies, list)
        ):
            fail(f"invalid plugin module descriptor: {manifest_path.relative_to(ROOT)}")
        if module_id in host_modules:
            fail(f"plugin module conflicts with host descriptor: {module_id}")
        if module_id in plugin_ids:
            fail(f"duplicate plugin module descriptor: {module_id}")
        plugin_ids.add(module_id)
        dependency_ids = []
        for dependency in dependencies:
            dependency_id = dependency.get("id") if isinstance(dependency, dict) else None
            if not isinstance(dependency_id, str) or not re.fullmatch(
                r"[a-z][a-z0-9-]{0,99}", dependency_id
            ):
                fail(f"invalid plugin dependency in {manifest_path.relative_to(ROOT)}")
            dependency_ids.append(dependency_id)
        if module_id in dependency_ids or len(dependency_ids) != len(set(dependency_ids)):
            fail(f"invalid plugin dependency graph in {manifest_path.relative_to(ROOT)}")
        descriptor = (manifest["version"], False, tuple(dependency_ids))
        source_descriptor = backend_modules.get(module_id)
        if source_descriptor is not None and source_descriptor != descriptor:
            fail(
                f"plugin.json and backend module descriptor disagree for {module_id}: "
                f"manifest={descriptor} source={source_descriptor}"
            )
        backend_modules[module_id] = descriptor
    # Spring 启动前的静态提取须与 ModuleRegistry 校验等价，涵盖运行时插件描述符、必需模块、依赖及循环约束。
    # Keep static extraction equivalent to ModuleRegistry validation before
    # Spring starts, including runtime plugin descriptors, required-module,
    # dependency, and cycle constraints.
    validate_backend_module_graph(backend_modules)
except (OSError, json.JSONDecodeError, ValueError) as error:
    fail(str(error))
descriptors = set(backend_modules)

ownership_path = ROOT / "backend/src/main/resources/db/module-ownership.json"
try:
    ownership = json.loads(ownership_path.read_text())
    table_owners = ownership["tableOwners"]
    object_owners = ownership["objectOwners"]
    migration_owners = ownership.get("migrationOwners", {})
except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
    fail(f"database module ownership manifest is invalid: {error}")
migration_dir = ROOT / "backend/src/main/resources/db/migration"
upgrade_dir = ROOT / "backend/src/main/resources/db/upgrade"
baseline_name = ownership.get("baseline")
active_migrations = {path.name for path in migration_dir.glob("*.sql")}
if baseline_name != "B52__platform_initialization.sql":
    fail(f"database baseline must be B52__platform_initialization.sql: {baseline_name}")
baseline_files = {name for name in active_migrations if name.startswith("B")}
if baseline_name not in active_migrations or baseline_files != {baseline_name}:
    fail(f"database baseline is missing or not unique: {baseline_name}")
if active_migrations != {baseline_name}:
    fail(f"global migration directory must contain only its initialization baseline: {sorted(active_migrations)}")
if upgrade_dir.exists():
    fail("legacy global upgrade directory is not allowed")
if migration_owners != {}:
    fail(f"historical global migration ownership is not allowed: {sorted(migration_owners)}")
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

# 在模块化单体阶段明确声明包归属；包到模块的映射是后续拆分边界，将包移入其他 Maven 模块不能改变其 HTTP 契约。
# Package ownership is intentionally explicit while the codebase is still a
# modular monolith. The package-to-module map is the extraction seam: moving a
# package to a different Maven module later must not change its HTTP contract.
package_owners = {
    "identity": "identity",
    "ai": "ai",
    "audit": "audit-console",
    "feedback": "feedback",
    "sensitive": "sensitive-words",
    "localization": "localization",
    "mfa": "mfa",
    "mail": "communication",
    "sms": "communication",
    "communication": "communication",
    "dictionaries": "dictionaries",
    "events": "events",
    "files": "files",
    "fileretention": "file-retention",
    "fileconsole": "file-console",
    "siteconsole": "site-console",
    "deliverysettings": "delivery-settings",
    "profileconsole": "profile-console",
    "authenticationconsole": "authentication-console",
    "personalfiles": "personal-files",
    "moduleadmin": "platform",
    "plugins": "platform",
    "monitoring": "monitoring",
    "navigation": "navigation",
    "notifications": "notifications",
    "organizations": "directory",
    "roles": "directory",
    "settings": "settings",
    "users": "directory",
    "directory": "directory",
    "templates": "directory",
    "sessions": "directory",
    "web": "platform",
    "modules": "kernel",
    "content": "kernel",
    "common": "kernel",
}
for path in java_files:
    relative_parts = java_relative(path).parts
    # Spring Boot 启动器是装配入口，可以了解所有模块，并由应用构建单独检查。
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

central = java_path("modules/ModuleConfiguration.java").read_text()
if "new PlatformModule(" in central:
    fail("the kernel configuration must not own feature descriptors")

for path in [p for p in java_files if p.name.endswith("Controller.java")]:
    source = path.read_text()
    owners = re.findall(r'@(?:com\.lumira\.platform\.modules\.)?ModuleEndpoint\("([^"]+)"\)', source)
    if not owners:
        fail(f"controller has no module owner: {path.relative_to(ROOT)}")
    unknown = set(owners) - descriptors
    if unknown:
        fail(f"controller references unknown module {sorted(unknown)}: {path.relative_to(ROOT)}")
    relative = java_relative(path).as_posix()
    expected_owner = {
        # 会话管理是由目录领域暴露的身份实现；在保持 API 不变并迁移实现包之前，显式保留这一例外。
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
    if "/audit/" not in path.as_posix() and "AuditRepository" in path.read_text():
        fail(f"feature leaks the audit query repository instead of AuditPort: {path.relative_to(ROOT)}")

# 公共模块 API 必须能脱离 Web 和持久化适配器编译；允许用于传输兼容的 Jackson 注解，而 Spring MVC/JDBC 类型会使后续 Maven 拆分反向依赖应用模块。
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

for path in (ROOT / "plugins/feedback/backend/src/main/java/com/lumira/platform/feedback").rglob("*.java"):
    source = path.read_text()
    if re.search(r"(?:FileService|FileRepository|FileUploadRateLimiter|ImageNormalizer|SensitiveWordService|NotificationService|DictionaryLookupRepository)", source):
        fail(f"feedback depends on a private cross-module implementation: {path.relative_to(ROOT)}")

for package in ("mail", "sms"):
    for path in (JAVA_ROOT / package).glob("*.java"):
        source = path.read_text()
        if re.search(r"(?:import\s+com\.lumira\.platform\.settings\.|com\.lumira\.platform\.settings\.)", source):
            fail(f"communication adapter depends on settings implementation: {path.relative_to(ROOT)}")

# 身份是底层必需模块，其公开依赖只能来自内核契约或自身 api 包；否则描述符图看似独立，Java 却可能通过设置、文件或字典实现重建循环。
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

# 平台适配器可以依赖身份契约，但不能依赖身份私有实现，使后续 Maven 拆分无需重新梳理包耦合。
# Platform adapters may depend on identity's contracts, but not on private
# identity implementations.  This is what keeps later Maven extraction
# mechanical instead of turning into a package rename exercise.
for path in java_files:
    if "/identity/" in path.as_posix():
        continue
    source = path.read_text()
    for target in re.findall(r"(?:import\s+)?com\.lumira\.platform\.identity\.([A-Za-z0-9_.]+)", source):
        if not target.startswith("api."):
            fail(f"feature imports private identity implementation {target}: {path.relative_to(ROOT)}")

required_adapters = {
    "ProfileSchemaPort": "settings/ProfileSchemaReader.java",
    "ProfileDictionaryPort": "dictionaries/DictionaryKernelAdapter.java",
    "AvatarStatusPort": "files/AvatarRepository.java",
    "AccountOwnerPort": "identity/ProfileRepository.java",
    "CaptchaConfigurationPort": "identity/CaptchaConfigurationReader.java",
    "SecurityPolicyPort": "settings/SecurityPolicyReader.java",
    "PasskeyPolicyPort": "settings/PasskeyPolicyReader.java",
    "MaintenancePolicyPort": "settings/MaintenanceReader.java",
    "ContentPolicyPort": "sensitive/SensitiveWordService.java",
    "FileContentPort": "files/FileService.java",
    "NotificationProducerPort": "notifications/NotificationService.java",
    "DictionaryLookupPort": "dictionaries/DictionaryKernelAdapter.java",
    "UploadRateLimitPort": "files/FileUploadRateLimiter.java",
    "MailConfigurationPort": "settings/SmtpConfigurationReader.java",
    "SmsConfigurationPort": "settings/SmsConfigurationReader.java",
    "SecretStorePort": "settings/SecretStore.java",
    "DirectoryAiServicePort": "users/AiUserDirectoryAdapter.java",
    "DirectoryTemplateServicePort": "users/TemplateUserDataAdapter.java",
    "DirectoryRoleAssignmentServicePort": "roles/RoleService.java",
}
for port, relative in required_adapters.items():
    if port not in java_path(relative).read_text():
        fail(f"missing adapter for module port {port}: {relative}")

for relative in ("mail/MailDeliveryStore.java", "sms/SmsDeliveryStore.java"):
    source = java_path(relative).read_text()
    if "extends DurableDeliveryStore" not in source:
        fail(f"delivery store bypasses the shared communication state machine: {relative}")

for relative in ("mail/MailDeliveryWorker.java", "sms/SmsDeliveryWorker.java"):
    if "extends DurableDeliveryWorker" not in java_path(relative).read_text():
        fail(f"delivery worker bypasses the shared communication orchestration: {relative}")

for relative in ("moduleadmin/ModuleAccessInterceptor.java", "moduleadmin/ModuleAccessConfiguration.java"):
    source = java_path(relative).read_text()
    if "ModuleStateStore" in source:
        fail(f"HTTP module gate depends on the state storage implementation: {relative}")

for relative in ("navigation/NavigationMenuService.java", "web/PlatformController.java"):
    source = java_path(relative).read_text()
    if "ModuleStateStore" in source:
        fail(f"platform consumer depends on the module state storage implementation: {relative}")


if "DictionaryLookupRepository" in (ROOT / "plugins/profile-console/backend/src/main/java/com/lumira/plugins/profileconsole/ProfileFieldsController.java").read_text():
    fail("settings controller depends on the dictionary storage implementation")

if "ModuleStateStore" in java_path("moduleadmin/ModuleAdminController.java").read_text():
    fail("module administration controller exposes the state storage model")

workspace = (WEB_ROOT / "app/dashboard-workspace.tsx").read_text()
if "const featurePages" in workspace:
    fail("workspace still owns the feature page map")
for shell_file in WEB_ROOT.joinpath("platform/shell").rglob("*"):
    if shell_file.suffix not in (".ts", ".tsx"):
        continue
    for feature_import in re.findall(
        r'''(?:from\s*|import\s*\()\s*['"](@/features/[^'"]+)''', shell_file.read_text()
    ):
        if not feature_import.startswith("@/features/account/"):
            fail(f"workspace imports a business implementation directly: {shell_file.relative_to(ROOT)}")

registry = (WEB_ROOT / "app/dashboard-modules.tsx").read_text()
registry_contracts = (WEB_ROOT / "platform/modules/contracts.ts").read_text()
manifest_paths = [*WEB_ROOT.joinpath("features").rglob("manifest.ts"), *ROOT.joinpath("plugins").glob("*/frontend/manifest.ts")]
manifest_sources = "".join(path.read_text() for path in manifest_paths)

# 宿主可编目插件元数据，但不得将可选插件实现编入自身构建包；共享 UI 应置于 SDK 中。
# The host may catalogue plugin metadata, but must not compile an optional
# plugin's implementation into its own bundle. Shared UI belongs in the SDK.
plugin_root = (ROOT / "plugins").resolve()
sdk_root = (plugin_root / "sdk" / "frontend").resolve()
for source_path in [*WEB_ROOT.rglob("*.ts"), *WEB_ROOT.rglob("*.tsx")]:
    if any(part in {".umi", ".umi-production", "node_modules"} for part in source_path.parts):
        continue
    for specifier in re.findall(r'(?:from\s*|import\s*\(\s*)["\']([^"\']+)["\']', source_path.read_text()):
        if not specifier.startswith("."):
            continue
        target = (source_path.parent / specifier).resolve()
        if target.is_relative_to(plugin_root) and not target.is_relative_to(sdk_root) and target.name not in {"manifest", "manifest.ts"}:
            fail(f"host imports optional plugin implementation: {source_path.relative_to(ROOT)} -> {specifier}")

# 资源表共用同一分页契约；及时发现功能重新引入不同本地默认值，避免形成新的 CRUD 实现分叉。
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

# 清单必须保持低导入成本，页面模块有意懒加载；否则注册表只是形式上模块化，初始客户端包仍会将所有功能拉入工作区外壳。
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
    frontend_path = WEB_ROOT / "features" / relative
    if not frontend_path.exists():
        pkg, filename = relative.split("/",1)
        frontend_path = ROOT / "plugins" / {"sensitive":"sensitive-words", "audit":"audit-console", "communication":"delivery-console"}.get(pkg,pkg) / "frontend" / filename
    source = frontend_path.read_text()
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
page_store = (WEB_ROOT / "platform/modules/page-store.ts").read_text()
if "initializeHostPages" not in registry or "createFrontendPageRegistry" not in page_store or "Duplicate frontend route" not in registry_contracts or "Uncatalogued frontend page" not in registry_contracts:
    fail("frontend registry does not enforce route/page ownership")

print(
    "module boundary check passed: "
    f"{len(descriptors)} backend descriptors, "
    f"{len(list(JAVA_ROOT.rglob('*Controller.java')))} owned controllers, "
    "frontend registry contract"
)
