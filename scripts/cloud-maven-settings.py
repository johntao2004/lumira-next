#!/usr/bin/env python3
"""Generate private Maven settings from this VM's current proxy environment."""
import os
from pathlib import Path
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET

path = Path(os.environ['LUMIRA_CLOUD_MAVEN_SETTINGS'])
mirror = os.environ['MVNW_REPOURL']
assert mirror in {
    'https://repo.maven.apache.org/maven2',
    'https://repo1.maven.org/maven2',
    'https://maven-central.storage-download.googleapis.com/maven2',
}, 'Use a supported HTTPS Maven Central endpoint.'
settings = ET.Element('settings')
mirrors = ET.SubElement(settings, 'mirrors')
m = ET.SubElement(mirrors, 'mirror')
for key, value in [('id', 'lumira-cloud-central'), ('url', mirror), ('mirrorOf', 'central')]:
    ET.SubElement(m, key).text = value
proxies = ET.SubElement(settings, 'proxies')
for protocol in ['http', 'https']:
    value = os.environ.get(protocol.upper() + '_PROXY') or os.environ.get(protocol + '_proxy')
    if not value:
        continue
    url = urlparse(value)
    assert url.scheme in ['http', 'https'] and url.hostname, 'Invalid VM proxy configuration.'
    proxy = ET.SubElement(proxies, 'proxy')
    fields = [('id', 'lumira-cloud-' + protocol), ('active', 'true'),
              ('protocol', protocol), ('host', url.hostname),
              ('port', str(url.port or (443 if url.scheme == 'https' else 80))),
              ('nonProxyHosts', 'localhost|127.*|[::1]')]
    if url.username is not None:
        fields.append(('username', unquote(url.username)))
    if url.password is not None:
        fields.append(('password', unquote(url.password)))
    for key, text in fields:
        ET.SubElement(proxy, key).text = text
path.parent.mkdir(parents=True, exist_ok=True)
# Opening with O_TRUNC keeps repeated source/install calls private as well.
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
os.fchmod(fd, 0o600)
with os.fdopen(fd, 'wb') as f:
    f.write(ET.tostring(settings, encoding='utf-8', xml_declaration=True))
