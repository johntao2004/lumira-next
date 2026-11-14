"""核对核心清单和双语职责注释；语义仍需代码审查。
Check the core inventory and bilingual responsibility comments; semantic accuracy still requires review.
"""
import json
from pathlib import Path
import re
import ast
import io
import tokenize

ROOT = Path(__file__).resolve().parents[1]
entries = json.loads((ROOT / 'docs/core-file-inventory.json').read_text())

# 跳过字符串与 SQL 文本，避免把路由通配符误当注释；连续行注释作为一个中英说明块。
# Skip strings and SQL text so route wildcards are not comments; group adjacent line comments as one bilingual explanation.
def comment_blocks(file, source):
    if file.suffix == '.py':
        lines = source.splitlines(keepends=True)
        offsets = [0]
        for line in lines:
            offsets.append(offsets[-1] + len(line))
        spans = []
        for token in tokenize.generate_tokens(io.StringIO(source).readline):
            if token.type == tokenize.COMMENT and not token.string.startswith('#!'):
                spans.append((offsets[token.start[0] - 1] + token.start[1], offsets[token.end[0] - 1] + token.end[1]))
        for node in ast.walk(ast.parse(source)):
            if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)) and node.body:
                first = node.body[0]
                if isinstance(first, ast.Expr) and isinstance(first.value, ast.Constant) and isinstance(first.value.value, str):
                    spans.append((offsets[first.lineno - 1] + first.col_offset, offsets[first.end_lineno - 1] + first.end_col_offset))
        spans.sort()
    elif file.suffix in ('.sh', '.Dockerfile') or file.name == 'Dockerfile':
        spans = [(m.start(), m.end()) for m in re.finditer(r'(?m)^[ \t]*#(?!\!)[^\n]*', source)]
    else:
        token = r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`|/\*.*?\*/|//[^\n]*'
        spans = [(m.start(), m.end()) for m in re.finditer(token, source, re.S) if m[0].startswith(('/*', '//'))]
    groups = []
    for start, end in spans:
        if groups and not source[groups[-1][1]:start].strip():
            groups[-1] = (groups[-1][0], end)
        else:
            groups.append((start, end))
    return [(source.count('\n', 0, start) + 1, source[start:end]) for start, end in groups]

errors = []
comment_count = 0
for relative in entries:
    file = ROOT / relative
    if not file.is_file():
        errors.append('missing core file: ' + relative)
        continue
    source = file.read_text()
    for line, block in comment_blocks(file, source):
        comment_count += 1
        if not re.search(r'[\u4e00-\u9fff]', block) or not re.search(r'[A-Za-z]{3,}', block):
            errors.append(f'missing bilingual comment block: {relative}:{line}')
    comments = [block for _, block in comment_blocks(file, source)] if file.suffix in ('.sh', '.Dockerfile') or file.name == 'Dockerfile' else re.findall(r'/\*.*?\*/|""".*?"""', source, re.S)
    if not any(re.search(r'[\u4e00-\u9fff]', c) and re.search(r'[A-Za-z]{3,}', c) for c in comments):
        errors.append('missing bilingual responsibility: ' + relative)
if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS: {len(entries)} core files and {comment_count} bilingual comment blocks; semantic review required')

# 额外检查顶层公开接口的方法（含默认实现）；格式与语义仍由 Javadoc/人工审查负责。
# Also require bilingual contracts on top-level interface methods, including defaults; format and semantics remain review responsibilities.
method_count = 0
for file in (ROOT / 'framework').glob('*/src/main/java/**/*.java'):
    text = file.read_text()
    if not re.search(r'^public\s+interface\s+', text, re.M):
        continue
    clean = re.sub(r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"', lambda m: ' ' * len(m[0]), text, flags=re.S)
    pattern = r'(?m)^\s*(?:public\s+)?(?:default\s+)?(?:<[^>]+>\s+)?[\w.<>, ?\[\]]+\s+(\w+)\([^;{}]*\)(?:\s+throws [\w., ]+)?\s*[;{]'
    for method in re.finditer(pattern, clean):
        if re.search(r'\brecord\s+', method[0]):
            continue
        prefix = clean[:method.start()]
        if prefix.count('{') - prefix.count('}') != 1:
            continue
        before = text[:method.start()].rstrip()
        # 正则可能把注释后的空白一并吃入；按方法首个非空字符定位。
        # Locate the first declaration token because whitespace may include a preceding stripped comment.
        declaration = method.start() + len(method[0]) - len(method[0].lstrip())
        before = text[:declaration].rstrip()
        match = re.search(r'/\*\*((?:(?!/\*).)*?)\*/\s*(?:@[\w.]+\s*)?$', before, re.S)
        if not match or not re.search(r'[\u4e00-\u9fff]',match[1]) or not re.search(r'[A-Za-z]{3,}',match[1]):
            errors.append('missing bilingual interface method contract: '+str(file.relative_to(ROOT))+'#'+method[1])
        method_count += 1
if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS: {method_count} top-level public interface method contracts')
