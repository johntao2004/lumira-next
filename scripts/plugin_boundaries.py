"""从清单发现插件及 SDK 示例，检查包所有权、私有跨模块依赖和控制器归属，并要求公开宿主 API 的模块依赖声明。
Discover plugins and SDK examples from manifests, validate package ownership, private cross-module dependencies and controller ownership, and require declared module dependencies for designated public host APIs."""
import json
from pathlib import Path
import re

# 公开契约以独立 SDK 源码为准，不能按宿主包前缀整体放行。
# Derive public contracts from independent SDK sources, never from broad host-package prefixes.
SDK_ROOT = Path(__file__).resolve().parents[1] / "framework"
def public_types():
    result = set()
    for source in SDK_ROOT.glob("*/src/main/java/**/*.java"):
        match = re.search(r"\bpackage\s+([\w.]+)\s*;", source.read_text())
        if match:
            result.add(match[1] + "." + source.stem)
    # 宿主共享基础契约目录允许受控引用，同时排除私有实现类。
    # Allow controlled references to shared host foundation contracts while excluding private implementations.
    host_base = SDK_ROOT.parent / "backend/src/main/java/com/lumira/platform"
    excluded = {
        "SecretKeys",
        "SecretCipher",
        "ClientAddressResolver",
        "FixedWindowBuckets",
        "ModuleRegistry",
        "ModuleStateStore",
        "ModuleConfiguration",
        "SecretStore",
    }
    for sub in ("common", "dictionaries/api", "identity/api", "navigation/api", "modules", "settings"):
        base_dir = host_base / sub
        if not base_dir.is_dir():
            continue
        for source in base_dir.rglob("*.java"):
            if source.stem in excluded:
                continue
            match = re.search(r"\bpackage\s+([\w.]+)\s*;", source.read_text())
            if match:
                result.add(match[1] + "." + source.stem)
    return result

PUBLIC_MODULES = {
    "com.lumira.platform.identity.api.": "identity",
    "com.lumira.platform.dictionaries.api.": "dictionaries",
    "com.lumira.platform.notifications.api.": "notifications",
}



def plugin_manifests(root):
    plugins = Path(root) / "plugins"
    return sorted([*plugins.glob("*/plugin.json"), *plugins.glob("sdk/examples/*/plugin.json")])


def validate_plugin_sources(root):
    root = Path(root)
    contracts = public_types()
    contract_packages = {name.rsplit(".", 1)[0] for name in contracts}
    host_packages = {match[1] for source in (root / "backend/src/main/java").rglob("*.java")
                     if (match := re.search(r"\bpackage\s+([\w.]+)\s*;", source.read_text()))}
    descriptors = []
    packages = {}
    for manifest in plugin_manifests(root):
        data = json.loads(manifest.read_text())
        source_root = manifest.parent / "backend/src/main/java"
        sources = sorted(source_root.rglob("*.java"))
        if not sources:
            raise ValueError(f"plugin {data['id']} has no backend sources")
        descriptors.append((data, sources))
        for source in sources:
            match = re.search(r"\bpackage\s+([\w.]+)\s*;", source.read_text())
            if not match:
                raise ValueError(f"missing Java package: {source.relative_to(root)}")
            package = match[1]
            if package in host_packages or any(package == prefix or package.startswith(prefix + ".") for prefix in contract_packages):
                raise ValueError(f"plugin package overlaps host contract or implementation: {package}")
            previous = packages.setdefault(package, data['id'])
            if previous != data['id']:
                raise ValueError(f"plugin package shared by {previous} and {data['id']}: {package}")
    checked = 0
    for data, sources in descriptors:
        dependencies = {item['id'] for item in data.get('dependencies', [])}
        own = [package + "." for package, owner in packages.items() if owner == data['id']]
        foreign = [package + "." for package, owner in packages.items() if owner != data['id']]
        for source in sources:
            text = source.read_text()
            # 注释不算依赖；导入与全限定名引用均算依赖。
            # Comments are not dependencies; fully-qualified references as well as imports are.
            text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
            references = re.findall(r"\bcom\.lumira\.[\w.*]+", text)
            for target in references:
                for prefix, module in PUBLIC_MODULES.items():
                    if target.startswith(prefix) and module != data['id'] and module not in dependencies:
                        raise ValueError(f"undeclared public API dependency {module}: {source.relative_to(root)}")
                is_contract = any(target == name or target.startswith(name + ".") for name in contracts) or (
                    target.endswith(".*") and target[:-2] in contract_packages
                )
                if not is_contract and any(target.startswith(prefix) for prefix in foreign):
                    raise ValueError(f"private cross-plugin dependency {target}: {source.relative_to(root)}")
                if target.startswith(("com.lumira.platform.", "com.lumira.plugin.sdk.")) and not (
                    any(target.startswith(prefix) or target == prefix[:-1] for prefix in own)
                    or is_contract
                ):
                    raise ValueError(f"private host dependency {target}: {source.relative_to(root)}")
            if re.search(r"@(?:[\w.]+\.)?(?:RestController|Controller)\b", text):
                owners = re.findall(r'@(?:[\w.]+\.)?ModuleEndpoint\("([^\"]+)"\)', text)
                if not owners or set(owners) != {data['id']}:
                    raise ValueError(f"plugin endpoint owner mismatch: {source.relative_to(root)}")
            checked += 1
    return len(descriptors), checked


if __name__ == "__main__":
    count, sources = validate_plugin_sources(Path(__file__).resolve().parents[1])
    print(f"PASS: {count} plugins/examples, {sources} Java sources checked against public contracts")
