import { build } from 'esbuild';
import { readFileSync, writeFileSync, mkdirSync, readdirSync, existsSync, copyFileSync, rmSync, utimesSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const javaHome=process.env.JAVA_HOME || (process.platform==='darwin'?'/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home':undefined);
if(!javaHome)throw new Error('Set JAVA_HOME to JDK 25');
function run(command,args){const r=spawnSync(command,args,{cwd:root,stdio:'inherit'});if(r.status!==0)throw new Error(`${command} failed`);}
if(!process.argv.includes('--no-host'))run(path.join(root,'backend/mvnw'),['-f','backend/pom.xml','-q','-DskipTests','compile','dependency:build-classpath','-Dmdep.outputFile=target/plugin-classpath.txt']);
const classpath=readFileSync(path.join(root,'backend/target/plugin-classpath.txt'),'utf8').trim();
const walk=p=>readdirSync(p,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(p,e.name)):[path.join(p,e.name)]);
const sdk=JSON.parse(readFileSync(path.join(root,'plugins/sdk/frontend-modules.json'),'utf8'));
function validateFrontendManifest(id, plugin){
 const frontendManifest=path.join(root,'plugins',id,'frontend/manifest.ts');
 if(!existsSync(frontendManifest))throw new Error(`Plugin ${id} is missing frontend/manifest.ts`);
 const source=readFileSync(frontendManifest,'utf8');
 const descriptor=source.match(/\bexport\s+const\s+[A-Za-z_$][\w$]*\s*=\s*\{\s*id:\s*["']([^"']+)["']\s*,\s*version:\s*["']([^"']+)["']/);
 if(!descriptor)throw new Error(`Plugin ${id} frontend manifest must declare literal id and version fields`);
 if(descriptor[1]!==plugin.id||descriptor[2]!==plugin.version)throw new Error(`Plugin ${id} frontend manifest ${descriptor[1]}@${descriptor[2]} does not match plugin.json ${plugin.id}@${plugin.version}`);
}
const requested=process.argv.slice(2).filter(arg=>arg!=='--no-host');
const ids=requested.length?requested:readdirSync(path.join(root,'plugins')).filter(id=>existsSync(path.join(root,'plugins',id,'plugin.json')));
const ordered=[],visited=new Set(),visiting=new Set();
function visit(id){if(visited.has(id))return;if(visiting.has(id))throw new Error('Plugin dependency cycle: '+id);visiting.add(id);const m=JSON.parse(readFileSync(path.join(root,'plugins',id,'plugin.json'),'utf8'));for(const d of m.dependencies||[])if(existsSync(path.join(root,'plugins',d.id,'plugin.json')))visit(d.id);visiting.delete(id);visited.add(id);ordered.push(id);}
ids.forEach(visit);
for(const id of ordered){
 const dir=path.join(root,'plugins',id),m=JSON.parse(readFileSync(path.join(dir,'plugin.json'),'utf8'));
 validateFrontendManifest(id,m);
 const dist=path.join(dir,'dist');mkdirSync(dist,{recursive:true});const staging=path.join(dist,'package');rmSync(staging,{recursive:true,force:true});mkdirSync(path.join(staging,'backend'),{recursive:true});mkdirSync(path.join(staging,'frontend'),{recursive:true});
 const libraries=existsSync(path.join(dir,'backend/lib'))?walk(path.join(dir,'backend/lib')).filter(p=>p.endsWith('.jar')):[];
 const classes=path.join(dist,'classes');rmSync(classes,{recursive:true,force:true});mkdirSync(classes,{recursive:true});
 run(path.join(javaHome,'bin/javac'),['--release','25','-parameters','-cp',`${root}/backend/target/classes${path.delimiter}${classpath}${libraries.length?path.delimiter+libraries.join(path.delimiter):''}`,'-d',classes,...walk(path.join(dir,'backend/src/main/java')).filter(p=>p.endsWith('.java'))]);
 const manifest=path.join(dist,'MANIFEST.MF');writeFileSync(manifest,`Manifest-Version: 1.0\nPlugin-Id: ${m.id}\nPlugin-Version: ${m.version}\nPlugin-Class: ${m.entryClass}\nPlugin-Provider: Lumira\n\n`);
 run(path.join(javaHome,'bin/jar'),['--create','--date','2020-01-01T00:00:00Z','--file',path.join(staging,m.backend),'--manifest',manifest,'-C',classes,'.']);
 const entry=path.join(dir,'frontend/module.tsx');
 await build({entryPoints:[entry],outfile:path.join(staging,m.frontend),bundle:true,format:'iife',globalName:'LumiraPluginBundle',target:'es2022',jsx:'automatic',minify:false,loader:{'.svg':'dataurl','.png':'dataurl','.jpg':'dataurl','.webp':'dataurl','.woff':'dataurl','.woff2':'dataurl','.ttf':'dataurl'},
  nodePaths:[path.join(root,'apps/web/node_modules')],plugins:[{name:'lumira-shared-sdk',setup(b){b.onResolve({filter:/^(react(?:\/.*)?|antd(?:\/.*)?|@[^.].*)$/},args=>{
    if(args.path.startsWith('@/features/'))throw new Error('Plugins must use SDK contracts, not other feature implementations');
    if(!sdk.includes(args.path)){if(args.path.startsWith('@/')||args.path.startsWith('antd')||args.path.startsWith('react')||args.path.startsWith('@tanstack/react-query'))throw new Error(`Unsupported plugin SDK import: ${args.path}`);return;}
    return {path:args.path,namespace:'host'};
   });b.onLoad({filter:/.*/,namespace:'host'},args=>({contents:`module.exports=window.__lumiraPluginSdk[${JSON.stringify(args.path)}];`,loader:'js'}));}}],
  footer:{js:`window.__lumiraRegisterPlugin(${JSON.stringify(m.id)}, LumiraPluginBundle);`}});
 if(existsSync(path.join(dir,'migrations'))){mkdirSync(path.join(staging,'migrations'));for(const f of walk(path.join(dir,'migrations')))copyFileSync(f,path.join(staging,'migrations',path.basename(f)));}
 if(libraries.length){mkdirSync(path.join(staging,'backend/lib'));for(const lib of libraries)copyFileSync(lib,path.join(staging,'backend/lib',path.basename(lib)));}
 for(const d of m.dependencies||[]){const dependencyDir=path.join(root,'plugins',d.id);if(existsSync(path.join(dependencyDir,'plugin.json'))){const other=JSON.parse(readFileSync(path.join(dependencyDir,'plugin.json'),'utf8'));if(other.version!==d.version)throw new Error('Plugin dependency version conflict: '+d.id);mkdirSync(path.join(staging,'dependencies'),{recursive:true});copyFileSync(path.join(dependencyDir,'dist',`${d.id}-${d.version}.lumira-plugin.zip`),path.join(staging,'dependencies',`${d.id}-${d.version}.lumira-plugin.zip`));}}
 const files={};for(const f of walk(staging)){const key=path.relative(staging,f).split(path.sep).join('/');files[key]=createHash('sha256').update(readFileSync(f)).digest('hex');}
 writeFileSync(path.join(staging,'plugin.json'),JSON.stringify({...m,files},null,2)+'\n');
 const archive=path.join(dist,`${m.id}-${m.version}.lumira-plugin.zip`);rmSync(archive,{force:true});
 for(const file of walk(staging))utimesSync(file,new Date('2020-01-01T00:00:00Z'),new Date('2020-01-01T00:00:00Z'));
 const r=spawnSync('zip',['-X','-q',archive,...walk(staging).map(f=>path.relative(staging,f))],{cwd:staging,stdio:'inherit'});if(r.status!==0)throw new Error('zip failed');console.log(`Built ${path.relative(root,archive)}`);
}
