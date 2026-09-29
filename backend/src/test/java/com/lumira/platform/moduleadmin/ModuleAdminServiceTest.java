package com.lumira.platform.moduleadmin;
import com.lumira.platform.identity.AdministrationGuard;
import com.lumira.platform.modules.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ModuleAdminServiceTest {
 final AdministrationGuard guard=mock(AdministrationGuard.class);
 final ModuleStateStore states=mock(ModuleStateStore.class);
 final ModuleRegistry registry=new ModuleRegistry(List.of(new PlatformModule("core","Core","1.0",List.of(),true),new PlatformModule("audit-console","Audit console","1.0",List.of("core")),new PlatformModule("optional","Optional","1.0",List.of("core"))));
 final ModuleAdminService service=new ModuleAdminService(registry,states,guard);
 @Test void checksGlobalScopeBeforeStateEngine(){
  doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(guard).requireGlobal("limited",ModuleAdminService.WRITE);
  assertThrows(ResponseStatusException.class,()->service.change("limited","core",new ModuleAdminService.Change(false,0L,"reason")));
  var order=inOrder(guard);order.verify(guard).require("limited",ModuleAdminService.WRITE);order.verify(guard).requireGlobal("limited",ModuleAdminService.WRITE);verifyNoInteractions(states);
 }
 @Test void mapsDomainFailuresAndValidatesMissingValues(){
  assertEquals(400,assertThrows(ResponseStatusException.class,()->service.change("admin","core",new ModuleAdminService.Change(null,0L,"reason"))).getStatusCode().value());
  when(states.change("core",false,0,"admin","reason")).thenThrow(new ModuleStateStore.Rejected("requiredModule"));
  assertEquals(409,assertThrows(ResponseStatusException.class,()->service.change("admin","core",new ModuleAdminService.Change(false,0L,"reason"))).getStatusCode().value());
  when(states.change("audit-console",false,0,"admin","reason")).thenThrow(new ModuleStateStore.Rejected("requiredModule"));
  assertEquals(409,assertThrows(ResponseStatusException.class,()->service.change("admin","audit-console",new ModuleAdminService.Change(false,0L,"reason"))).getStatusCode().value());
 }
 @Test void separatesCodeVersionAndStateVersion(){
  when(states.states()).thenReturn(List.of(new ModuleStateStore.State("core",true,7),new ModuleStateStore.State("audit-console",true,0),new ModuleStateStore.State("optional",false,0)));
  var view=service.list().getFirst();assertEquals("1.0",view.version());assertEquals(7,view.stateVersion());assertTrue(view.required());assertTrue(view.core());assertTrue(view.enabled());
  var audit=service.list().stream().filter(row->row.id().equals("audit-console")).findFirst().orElseThrow();assertFalse(audit.required());assertTrue(audit.core());assertTrue(audit.enabled());
  var optional=service.list().stream().filter(row->row.id().equals("optional")).findFirst().orElseThrow();assertFalse(optional.core());assertFalse(optional.enabled());
 }
}
