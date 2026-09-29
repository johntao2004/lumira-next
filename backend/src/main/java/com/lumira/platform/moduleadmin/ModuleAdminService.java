package com.lumira.platform.moduleadmin;

import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.modules.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModuleAdminService {
 public static final String WRITE="platform:modules:write";
 public record View(String id,String name,String version,List<String> dependencies,boolean required,boolean core,boolean enabled,long stateVersion){}
 public record State(String moduleId,boolean enabled,long version){}
 public record Revision(String id,long version,boolean enabled,String changedBy,String changeReason,java.time.OffsetDateTime createdAt){}
 public record Change(Boolean enabled,Long version,String reason){}
 private final ModuleRegistry registry;private final ModuleStateStore states;private final AdministrationPort guard;
 public ModuleAdminService(ModuleRegistry registry,ModuleStateStore states,AdministrationPort guard){this.registry=registry;this.states=states;this.guard=guard;}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public List<View> list(){
  var current=new HashMap<String,ModuleStateStore.State>();states.states().forEach(state->current.put(state.moduleId(),state));
  return registry.modules().stream().map(module->{var state=current.get(module.id());return new View(module.id(),module.name(),module.version(),module.dependencies(),module.required(),module.core(),state.enabled(),state.version());}).toList();
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public com.lumira.platform.common.PageResult<Revision> history(String actor,String id,com.lumira.platform.common.PageRequest page){
  guard.requireGlobal(actor,"platform:modules:read");
  try{
   var result=states.history(id,page);
   return new com.lumira.platform.common.PageResult<>(result.items().stream().map(row->new Revision(row.id(),row.version(),row.enabled(),row.changedBy(),row.changeReason(),row.createdAt())).toList(),result.total(),result.page(),result.pageSize());
  }catch(ModuleStateStore.Rejected error){throw rejected(error);}
 }
 private static ResponseStatusException rejected(ModuleStateStore.Rejected error){return new ResponseStatusException(switch(error.getMessage()){
  case "moduleNotFound"->HttpStatus.NOT_FOUND;
  case "requiredModule","moduleDependencyConflict","staleRecord"->HttpStatus.CONFLICT;
  default->HttpStatus.BAD_REQUEST;
 },error.getMessage());}
 @Transactional
 public State change(String actor,String id,Change input){
  guard.require(actor,WRITE);guard.requireGlobal(actor,WRITE);
  if(input==null||input.enabled()==null||input.version()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidModuleChange");
  try{var state=states.change(id,input.enabled(),input.version(),actor,input.reason());return new State(state.moduleId(),state.enabled(),state.version());}
  catch(ModuleStateStore.Rejected error){throw rejected(error);}
 }
}
