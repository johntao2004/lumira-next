import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

test('all plugin packages and SDK examples obey source contracts; negative cases fail closed', () => {
  const result = spawnSync('python3', ['-c', String.raw`
import json,sys,tempfile
from pathlib import Path
sys.path.insert(0,'scripts')
from plugin_boundaries import validate_plugin_sources
assert validate_plugin_sources(Path.cwd())[0] == 28
with tempfile.TemporaryDirectory() as directory:
 root=Path(directory)
 def put(base,id,package,body):
  base=root/base; base.mkdir(parents=True,exist_ok=True)
  (base/'plugin.json').write_text(json.dumps({'id':id}))
  src=base/'backend/src/main/java'; src.mkdir(parents=True,exist_ok=True)
  (src/'Probe.java').write_text('package '+package+';\n'+body)
 put('plugins/first','first','com.lumira.plugins.first','import com.lumira.platform.common.AuditPort;')
 put('plugins/sdk/examples/second','second','com.lumira.plugins.second','import com.lumira.plugin.sdk.PluginEntry;')
 assert validate_plugin_sources(root)==(2,2)
 bad=[
 'import com.lumira.platform.users.UserRepository;',
 'import com.lumira.platform.common.SecretKeys;',
 'import com.lumira.platform.common.SecretCipher;',
 'import com.lumira.platform.modules.ModuleRegistry;',
 'import com.lumira.platform.identity.api.AccountOwnerPort;',
 'class Probe { com.lumira.platform.identity.AdministrationGuard guard; }',
 'import com.lumira.plugins.first.Probe;',
 '@RestController class Probe {}',
 '@ModuleEndpoint("first") @RestController class Probe {}',
 ]
 for body in bad:
  put('plugins/sdk/examples/second','second','com.lumira.plugins.second',body)
  try: validate_plugin_sources(root)
  except ValueError: pass
  else: raise AssertionError('accepted forbidden source: '+body)
 put('plugins/sdk/examples/second','second','com.lumira.plugins.second','@ModuleEndpoint("second") @RestController class Probe {}')
 assert validate_plugin_sources(root)==(2,2)
 host=root/'backend/src/main/java/Guard.java';host.parent.mkdir(parents=True);host.write_text('package com.lumira.platform.identity; class Guard {}')
 for package in ['com.lumira.platform.identity','com.lumira.platform.modules.fake']:
  put('plugins/sdk/examples/second','second',package,'import '+package+'.Guard;')
  try: validate_plugin_sources(root)
  except ValueError: pass
  else: raise AssertionError('accepted host package impersonation: '+package)
`], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stdout + result.stderr);
});
