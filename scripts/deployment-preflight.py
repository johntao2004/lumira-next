"""只读检查标准 Compose 的生产配置，不连接服务、不打印配置值。
Read-only production configuration checks for the standard Compose; never connect or print values.
"""
import base64
import binascii
import ipaddress
import json
import os
import re
import sys


def inspect(environment):
    """只返回固定规则和字段名；有效配置不等于现场验收。
    Return fixed rule names and field names only; valid configuration is not deployment acceptance.
    """
    failures = []
    def fail(field, rule):
        failures.append({'field': field, 'rule': rule})

    password_fields = ['LUMIRA_DB_BOOTSTRAP_PASSWORD', 'LUMIRA_DB_MIGRATION_PASSWORD', 'LUMIRA_DB_PASSWORD', 'LUMIRA_BOOTSTRAP_PASSWORD']
    for field in password_fields:
        if not environment.get(field, '').strip():
            fail(field, 'required')
    database_passwords = [environment.get(field, '') for field in password_fields[:3]]
    if len(set(database_passwords)) != 3:
        fail('database-passwords', 'three-distinct-values-required')
    for field in ['LUMIRA_OFFICE_WORKER_TOKEN', 'LUMIRA_METRICS_TOKEN']:
        token = environment.get(field, '')
        if (field == 'LUMIRA_OFFICE_WORKER_TOKEN' or token) and len(token) < 32:
            fail(field, 'minimum-32-characters')
    if len(environment.get('LUMIRA_OFFICE_WORKER_TOKEN', '')) > 256:
        fail('LUMIRA_OFFICE_WORKER_TOKEN', 'maximum-256-characters')
    if environment.get('LUMIRA_COOKIE_SECURE') != 'true':
        fail('LUMIRA_COOKIE_SECURE', 'production-requires-true')

    # 与宿主的规范 Base64/ID/32字节规则保持一致；不生成或保存密钥。
    # Match the host canonical Base64, ID and 32-byte rules; never generate or store keys.
    encoded = environment.get('LUMIRA_SECRET_KEYS', '')
    active = environment.get('LUMIRA_SECRET_ACTIVE_KEY', '') or 'primary'
    keys = set()
    valid_keys = bool(encoded) and len(encoded) <= 2048
    for entry in encoded.split(','):
        pair = entry.split('=', 1)
        if len(pair) != 2 or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', pair[0]) or pair[0] in keys:
            valid_keys = False
            continue
        keys.add(pair[0])
        try:
            decoded = base64.b64decode(pair[1], validate=True)
            valid_keys = valid_keys and len(decoded) == 32 and base64.b64encode(decoded).decode() == pair[1]
        except (ValueError, binascii.Error):
            valid_keys = False
    if len(keys) > 16:
        valid_keys = False
    if not valid_keys:
        fail('LUMIRA_SECRET_KEYS', 'valid-canonical-32-byte-keyring-required')
    if active not in keys or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', active):
        fail('LUMIRA_SECRET_ACTIVE_KEY', 'active-key-must-exist')

    # 固定代理地址必须在子网内且在动态分配池外，避免启动顺序相关冲突。
    # Keep the fixed proxy address inside the subnet and outside its dynamic pool.
    try:
        subnet = ipaddress.IPv4Network(environment.get('LUMIRA_PROXY_SUBNET') or '172.30.78.0/24')
        pool = ipaddress.IPv4Network(environment.get('LUMIRA_PROXY_POOL') or '172.30.78.128/25')
        edge = ipaddress.IPv4Address(environment.get('LUMIRA_EDGE_ADDRESS') or '172.30.78.2')
        if not pool.subnet_of(subnet) or edge not in subnet or edge in pool or edge in (subnet.network_address, subnet.network_address + 1, subnet.broadcast_address):
            fail('proxy-network', 'fixed-address-and-pool-must-not-overlap')
    except ValueError:
        fail('proxy-network', 'valid-ipv4-subnet-pool-and-address-required')
    return {
        'status': 'failed' if failures else 'configuration-valid',
        'scope': 'standard-compose-environment-only',
        'failures': failures,
        'requiresOnSiteVerification': ['rendered-compose-and-overrides', 'tls-and-cookie-transport', 'database-effective-roles-and-history-permissions', 'approved-plugin-graph-and-image-digests', 'backup-key-custody-rotation-inventory-and-restore', 'provider-credentials-and-acceptance'],
    }


if __name__ == '__main__':
    if len(sys.argv) != 1:
        print('Usage: python3 scripts/deployment-preflight.py (reads existing environment only)', file=sys.stderr)
        sys.exit(2)
    result = inspect(os.environ)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    sys.exit(1 if result['failures'] else 0)
