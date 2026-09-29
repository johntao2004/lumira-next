package com.lumira.platform.modules;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageFilterSql;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Internal state engine. Administrative authorization and request gating belong in adapters. */
@Repository
public class ModuleStateStore implements ModuleRuntime, ModuleCapabilityPort {
 public record Revision(String id,long version,boolean enabled,String changedBy,String changeReason,java.time.OffsetDateTime createdAt){}
 public record State(String moduleId,boolean enabled,long version){}
 private record Stored(UUID id,String moduleId,boolean enabled,long version){}
 public static class Rejected extends IllegalStateException {
  public Rejected(String code){super(code);}
 }
 private final JdbcClient jdbc;
 private final ModuleRegistry registry;
 private final AuditPort audit;
 public ModuleStateStore(JdbcClient jdbc,ModuleRegistry registry,AuditPort audit){this.jdbc=jdbc;this.registry=registry;this.audit=audit;}
 private Map<String,Stored> stored(){
  var result=new HashMap<String,Stored>();
  jdbc.sql("SELECT id,module_id,enabled,version FROM platform_module_state").query(Stored.class).list().forEach(row->result.put(row.moduleId(),row));
  return result;
 }
 private State state(PlatformModule module,Map<String,Stored> records){
  var row=records.get(module.id());
  return new State(module.id(),module.core()||(row!=null&&row.enabled()),row==null?0:row.version());
 }
 @Transactional(readOnly=true)
 public List<State> states(){
  var records=stored();return registry.modules().stream().map(module->state(module,records)).toList();
 }
 @Override
 @Transactional(readOnly=true)
 public boolean isEnabled(String moduleId){
  var module=registry.require(moduleId);
  if(module.core())return true;
  return jdbc.sql("SELECT enabled FROM platform_module_state WHERE module_id=:module")
   .param("module",moduleId).query(Boolean.class).optional().orElse(false);
 }
 @Override
 @Transactional(readOnly=true)
 public List<String> enabledModules(){
  return states().stream().filter(State::enabled).map(State::moduleId).toList();
 }
 @Override
 @Transactional(readOnly=true)
 public int totalModules(){return registry.modules().size();}
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public com.lumira.platform.common.PageResult<Revision> history(String moduleId,com.lumira.platform.common.PageRequest page){
  if(registry.modules().stream().noneMatch(module->module.id().equals(moduleId)))throw new Rejected("moduleNotFound");
  String from=" FROM platform_module_revision r JOIN platform_module_state s ON s.id=r.module_state_id WHERE s.module_id=:module";
  var filter=PageFilterSql.build(page,Map.of("version","r.version","enabled","r.enabled","changedBy","r.changed_by","changeReason","r.change_reason","createdAt","r.created_at"));
  var countParams=new HashMap<String,Object>();countParams.put("module",moduleId);countParams.putAll(filter.parameters());
  var count=jdbc.sql("SELECT count(*)"+from+filter.sql()).params(countParams).query(Long.class).single();
  var rowParams=new HashMap<>(countParams);rowParams.put("limit",page.pageSize());rowParams.put("offset",page.offset());
  var rows=jdbc.sql("SELECT r.version::text AS id,r.version,r.enabled,r.changed_by,r.change_reason,r.created_at"+from+filter.sql()+" ORDER BY r.version DESC LIMIT :limit OFFSET :offset")
   .params(rowParams).query(Revision.class).list();
  return new com.lumira.platform.common.PageResult<>(rows,count,page.page(),page.pageSize());
 }
 /** Lock order: adapters acquire AdministrationPort first, then this graph-wide lock. */
 @Transactional
 public State change(String moduleId,boolean enabled,long expectedVersion,String actor,String reason){
  if(expectedVersion<0||actor==null||actor.isBlank()||actor.length()>100||reason==null||reason.isBlank()||reason.length()>1000)throw new Rejected("invalidModuleChange");
  var module=registry.modules().stream().filter(m->m.id().equals(moduleId)).findFirst().orElseThrow(()->new Rejected("moduleNotFound"));
  jdbc.sql("SELECT pg_advisory_xact_lock(481027)").query(rs->{rs.next();return 0;});
  var records=stored();var current=state(module,records);
  if(current.version()!=expectedVersion)throw new Rejected("staleRecord");
  if(module.core()&&!enabled)throw new Rejected("requiredModule");
  var proposed=new HashMap<String,Boolean>();
  registry.modules().forEach(m->proposed.put(m.id(),state(m,records).enabled()));proposed.put(moduleId,enabled);
  for(var candidate:registry.modules())if(proposed.get(candidate.id()))
   for(var dependency:candidate.dependencies())if(!proposed.get(dependency))throw new Rejected("moduleDependencyConflict");
  if(current.enabled()==enabled)return current;
  var previous=records.get(moduleId);var id=previous==null?UUID.randomUUID():previous.id();long version=Math.addExact(current.version(),1);
  jdbc.sql("INSERT INTO platform_module_state(id,module_id,enabled,version) VALUES(:id,:module,:enabled,:version) ON CONFLICT(module_id) DO UPDATE SET enabled=EXCLUDED.enabled,version=EXCLUDED.version,updated_at=now()")
   .param("id",id).param("module",moduleId).param("enabled",enabled).param("version",version).update();
  jdbc.sql("INSERT INTO platform_module_revision(module_state_id,version,enabled,changed_by,change_reason) VALUES(:id,:version,:enabled,:actor,:reason)")
   .param("id",id).param("version",version).param("enabled",enabled).param("actor",actor).param("reason",reason.strip()).update();
  audit.append(actor,enabled?"module.enabled":"module.disabled",id);
  return new State(moduleId,enabled,version);
 }
}
