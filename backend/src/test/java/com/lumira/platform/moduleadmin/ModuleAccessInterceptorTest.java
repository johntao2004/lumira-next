package com.lumira.platform.moduleadmin;
import com.lumira.platform.modules.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ModuleAccessInterceptorTest {
 @org.springframework.web.bind.annotation.RestController
 @ModuleEndpoint("optional") static class HttpController {
  int calls;
  @org.springframework.web.bind.annotation.GetMapping("/module-fixture") public String read(){calls++;return "ok";}
 }
 @Test void mvcRejectsBeforeControllerAndResumesAfterEnable()throws Exception{
  var controller=new HttpController();
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).addInterceptors(gate).setControllerAdvice(new com.lumira.platform.common.ApiExceptionHandler()).build();
  when(runtime.isEnabled("optional")).thenReturn(false,true,false);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/module-fixture")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());assertEquals(0,controller.calls);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/module-fixture")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());assertEquals(1,controller.calls);
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/module-fixture")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable());assertEquals(1,controller.calls);
 }

  @ModuleEndpoint("optional") static class Controller {
  public void read(){}
  @ModuleEndpoint("core") public void core(){}
  @ModuleEndpoint("audit-console") public void audit(){}
  @ModuleEndpoint("unknown") public void invalid(){}
 }
 final ModuleRuntime runtime=mock(ModuleRuntime.class);
 final ModuleRegistry registry=new ModuleRegistry(List.of(new PlatformModule("core","Core","1",List.of(),true),new PlatformModule("optional","Optional","1",List.of("core")),new PlatformModule("audit-console","Audit console","1",List.of("core"))));
 final ModuleAccessInterceptor gate=new ModuleAccessInterceptor(registry,runtime);
 HandlerMethod handler(String name)throws Exception{return new HandlerMethod(new Controller(),Controller.class.getMethod(name));}
 boolean call(String name)throws Exception{return gate.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),handler(name));}
 @Test void rereadsStateForEachAccessDecision()throws Exception{
  when(runtime.isEnabled("optional")).thenReturn(false,true,false);
  assertEquals(503,assertThrows(ResponseStatusException.class,()->call("read")).getStatusCode().value());assertTrue(call("read"));assertThrows(ResponseStatusException.class,()->call("read"));verify(runtime,times(3)).isEnabled("optional");
 }
 @Test void methodOwnerOverridesClassAndCoreSkipsStateLookup()throws Exception{assertTrue(call("core"));verifyNoInteractions(runtime);}
 @Test void auditConsoleIsFixedCoreAndSkipsStateLookup()throws Exception{assertTrue(call("audit"));verifyNoInteractions(runtime);}
 @Test void unknownRegistrationFailsValidationAndRuntimeClosed()throws Exception{assertThrows(IllegalStateException.class,()->ModuleAccessInterceptor.validate(handler("invalid"),registry));assertThrows(ResponseStatusException.class,()->call("invalid"));}
 @Test void missingOptionalStateIsDisabled()throws Exception{when(runtime.isEnabled("optional")).thenReturn(false);assertThrows(ResponseStatusException.class,()->call("read"));}
}
