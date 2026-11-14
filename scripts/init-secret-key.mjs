import {readFileSync,writeFileSync,renameSync,unlinkSync,openSync,closeSync,chmodSync} from 'node:fs';
import {randomBytes,randomUUID} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const file=path.join(root,'.runtime/local.json'),lock=file+'.secret-init.lock',temporary=file+'.'+randomUUID()+'.tmp';
const descriptor=openSync(lock,'wx',0o600);
try{
 const config=JSON.parse(readFileSync(file,'utf8'));
 if(config.LUMIRA_SECRET_KEYS){console.log('Existing local secret key configuration retained.');}
 else{
  const database=new URL(config.LUMIRA_DB_URL.replace(/^jdbc:/,''));
  if(!['127.0.0.1','localhost','[::1]'].includes(database.hostname))throw new Error('This initializer only supports the local development database.');
  const result=spawnSync(process.env.PSQL_BIN??'psql',['-X','-At','-v','ON_ERROR_STOP=1'],{
   env:{...process.env,PGHOST:database.hostname.replace(/[\[\]]/g,''),PGPORT:database.port||'5432',PGDATABASE:decodeURIComponent(database.pathname.slice(1)),PGUSER:config.LUMIRA_DB_USER,PGPASSWORD:config.LUMIRA_DB_PASSWORD},
   input:'SELECT count(*) FROM platform_secret WHERE ciphertext IS NOT NULL;',encoding:'utf8',timeout:20000,
  });
  if(result.status!==0)throw new Error('Cannot verify existing encrypted credentials; verify the current database initialization and connectivity before initializing a key.');
  if(result.stdout.trim()!=='0')throw new Error('Encrypted credentials already exist. Restore the original key configuration instead of generating a replacement.');
  config.LUMIRA_SECRET_KEYS='primary='+randomBytes(32).toString('base64');config.LUMIRA_SECRET_ACTIVE_KEY='primary';
  writeFileSync(temporary,JSON.stringify(config,null,2)+'\n',{mode:0o600,flag:'wx'});renameSync(temporary,file);chmodSync(file,0o600);
  console.log('Local secret key initialized in protected runtime configuration. Restart the scaffold to load it.');
 }
}finally{
 closeSync(descriptor);unlinkSync(lock);
 try{unlinkSync(temporary);}catch(error){if(error.code!=='ENOENT')throw error;}
}
