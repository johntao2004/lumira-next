"""Static backend module descriptor parsing and graph checks."""

from __future__ import annotations

import re
from collections.abc import Iterable


_MODULE_DESCRIPTOR = re.compile(
    r"""
    new\s+PlatformModule\s*\(\s*
    "(?P<id>[a-z][a-z0-9-]{0,99})"\s*,\s*
    "[^"]+"\s*,\s*
    "(?P<version>[^"]+)"\s*,\s*
    List\s*\.\s*of\s*\(\s*(?P<dependencies>[^)]*)\)\s*
    (?:,\s*(?P<required>true|false)\s*)?
    \)
    """,
    re.DOTALL | re.VERBOSE,
)
_DEPENDENCY_ID = re.compile(r'"([a-z][a-z0-9-]{0,99})"')

ModuleDescriptor = tuple[str, bool, tuple[str, ...]]


def parse_backend_modules(sources: Iterable[str]) -> dict[str, ModuleDescriptor]:
    """Extract literal module descriptors, tolerating Java formatter line breaks."""
    modules: dict[str, ModuleDescriptor] = {}
    for source in sources:
        for match in _MODULE_DESCRIPTOR.finditer(source):
            module_id = match.group("id")
            dependencies = tuple(_DEPENDENCY_ID.findall(match.group("dependencies")))
            descriptor = (match.group("version"), match.group("required") == "true", dependencies)
            previous = modules.get(module_id)
            if previous is not None and previous != descriptor:
                raise ValueError(f"backend module descriptor is declared inconsistently: {module_id}")
            modules[module_id] = descriptor
    if not modules:
        raise ValueError("no backend module descriptors found")
    return modules


def validate_backend_module_graph(modules: dict[str, ModuleDescriptor]) -> None:
    """Reject missing dependencies, required-to-optional edges, and cycles."""
    for module_id, (_, required, dependencies) in modules.items():
        for dependency in dependencies:
            if dependency not in modules:
                raise ValueError(f"backend module has missing dependency: {module_id} -> {dependency}")
            if required and not modules[dependency][1]:
                raise ValueError(f"required backend module depends on optional module: {module_id} -> {dependency}")

    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(module_id: str) -> None:
        if module_id in visited:
            return
        if module_id in visiting:
            raise ValueError(f"backend module dependency cycle includes: {module_id}")
        visiting.add(module_id)
        for dependency in modules[module_id][2]:
            visit(dependency)
        visiting.remove(module_id)
        visited.add(module_id)

    for module_id in modules:
        visit(module_id)
