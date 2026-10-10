import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

test('module boundary descriptor parsing tolerates formatter line breaks and keeps graph guards', () => {
  const script = String.raw`
import sys
sys.path.insert(0, sys.argv[1])
from module_boundaries import parse_backend_modules, validate_backend_module_graph

single_line = '''new PlatformModule("platform", "Platform", "1.0.0", List.of(), true);'''
formatted = '''new PlatformModule (
    "files",
    "Files and media",
    "1.0.0",
    List . of (
        "platform"
    ),
    true
);'''
modules = parse_backend_modules([single_line, formatted])
assert modules == {
    "platform": ("1.0.0", True, ()),
    "files": ("1.0.0", True, ("platform",)),
}
validate_backend_module_graph(modules)

try:
    parse_backend_modules([single_line, 'new PlatformModule("platform", "Platform", "2.0.0", List.of(), true);'])
except ValueError as error:
    assert "declared inconsistently: platform" in str(error)
else:
    raise AssertionError("inconsistent duplicate descriptors must fail")

try:
    validate_backend_module_graph({"platform": ("1.0.0", True, ("missing",))})
except ValueError as error:
    assert "missing dependency: platform -> missing" in str(error)
else:
    raise AssertionError("unknown module dependencies must fail")
`;
  const result = spawnSync('python3', ['-c', script, path.join(root, 'scripts')], { cwd: root, encoding: 'utf8' });
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
});
