#!/usr/bin/env python3
"""Read the existing pnpm store from JSON or YAML workspace metadata."""
import json
from pathlib import Path
import re
import sys

metadata, fallback = Path(sys.argv[1]), sys.argv[2]
if not metadata.exists():
    print(fallback)
    sys.exit(0)
text = metadata.read_text()
try:
    value = json.loads(text).get('storeDir')
except json.JSONDecodeError:
    match = re.search(r'''^\s*(?:storeDir|"storeDir"|'storeDir'):\s*(.+)$''', text, re.M)
    value = match[1].strip() if match else None
    if value and value.startswith('"'):
        value = json.loads(value)
    elif value and value.startswith("'") and value.endswith("'"):
        value = value[1:-1].replace("''", "'")
if not isinstance(value, str) or not value.startswith('/'):
    raise ValueError('Could not determine the existing pnpm store; refusing to replace modules.')
print(re.sub(r'/v[0-9]+$', '', value))
