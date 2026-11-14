#!/usr/bin/env bash
# 使用一致快照和内容摘要验证数据库恢复；密钥与遗留文件仍须独立演练。
# Verify database recovery with one snapshot and content digests; keys and legacy files need separate drills.
set -euo pipefail
exec python3 "$(cd -- "$(dirname -- "$0")" && pwd)/backup-restore-drill.py" "$@"
