import {mkdirSync,writeFileSync,existsSync,symlinkSync,readdirSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const find=(dir)=>readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()&&e.name!=='dist'&&e.name!=='node_modules'?find(path.join(dir,e.name)):e.isFile()&&e.name==='plugin.json'?[dir]:[]);
const preparation=spawnSync(process.execPath,[path.join(root,'scripts/web-typecheck.mjs')],{cwd:root,stdio:'inherit'});if(preparation.status!==0)process.exit(preparation.status??1);
const dirs=find(path.join(root,'plugins'));
for(const dir of dirs){const target=path.join(dir,'frontend/node_modules');if(!existsSync(target))symlinkSync(path.join(root,'apps/web/node_modules'),target,'dir');}
const dir=path.join(root,'.runtime/plugin-work');mkdirSync(dir,{recursive:true});const config=path.join(dir,'plugins-tsconfig.json');
 writeFileSync(config,JSON.stringify({extends:path.join(root,'apps/web/tsconfig.json'),include:[path.join(root,'apps/web/src/.umi/**/*.ts'),path.join(root,'apps/web/src/**/*.d.ts'),path.join(root,'apps/web/src/features/monitoring/swagger-ui.ts'),...dirs.map(d=>path.join(d,'frontend/**/*'))],exclude:dirs.flatMap(d=>[path.join(d,'dist'),path.join(d,'frontend/node_modules')])},null,2));
const result=spawnSync(process.execPath,[path.join(root,'apps/web/node_modules/typescript/bin/tsc'),'--noEmit','-p',config],{cwd:root,stdio:'inherit'});process.exit(result.status??1);
