package com.lumira.platform.modules;

import com.lumira.platform.TestMigration;

import com.lumira.platform.audit.AuditRepository;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LUMIRA_TEST_DB_URL",matches=".+")
class ModuleStateStoreTest {
 JdbcTemplate admin,jdbc;TransactionTemplate tx;ModuleStateStore store;String schema;
 @BeforeEach void setup()throws Exception{
  String url=System.getenv("LUMIRA_TEST_DB_URL"),user=System.getenv("LUMIRA_TEST_DB_USER"),password=System.getenv("LUMIRA_TEST_DB_PASSWORD");
  admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="qa_modules_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var client=JdbcClient.create(ds);
  var registry=new ModuleRegistry(List.of(new PlatformModule("identity","Identity","1.0",List.of(),true),new PlatformModule("platform","Platform","1.0",List.of("identity"),true),new PlatformModule("base","Base","1.0",List.of("identity")),new PlatformModule("extension","Extension","1.0",List.of("base")),new PlatformModule("audit-console","Audit console","1.0",List.of("platform"))));
  store=new ModuleStateStore(client,registry,new AuditRepository(client));
  jdbc.execute("CREATE TABLE platform_audit(id UUID PRIMARY KEY,actor TEXT,action TEXT,target_id UUID)");
  jdbc.execute(TestMigration.read("V30__module_state.sql"));
 }
 @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
 ModuleStateStore.State change(String id,boolean enabled,long version){return tx.execute(s->store.change(id,enabled,version,"admin","Module maintenance"));}
 int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
 void rejected(String code,Runnable action){assertEquals(code,assertThrows(ModuleStateStore.Rejected.class,action::run).getMessage());}
 @Test void historyIsOrderedPagedAndScoped(){
  assertEquals(0,store.history("base",new com.lumira.platform.common.PageRequest(1,20,"")).total());
  for(int i=0;i<105;i++)change("base",i%2==0,i);
  var first=store.history("base",new com.lumira.platform.common.PageRequest(1,100,""));var second=store.history("base",new com.lumira.platform.common.PageRequest(2,100,""));
  assertEquals(105,first.total());assertEquals(100,first.items().size());assertEquals(5,second.items().size());
  assertEquals(105,first.items().getFirst().version());assertEquals(1,second.items().getLast().version());assertEquals("admin",first.items().getFirst().changedBy());assertEquals("Module maintenance",first.items().getFirst().changeReason());
  assertEquals(0,store.history("extension",new com.lumira.platform.common.PageRequest(1,20,"")).total());
  rejected("moduleNotFound",()->store.history("unknown",new com.lumira.platform.common.PageRequest(1,20,"")));
 }
 @Test void protectsCoreAndDependenciesInBothDirections(){
  assertEquals(List.of(true,true,false,false,true),store.states().stream().map(ModuleStateStore.State::enabled).toList());
  rejected("requiredModule",()->change("identity",false,0));rejected("requiredModule",()->change("platform",false,0));rejected("requiredModule",()->change("audit-console",false,0));
  rejected("moduleDependencyConflict",()->change("extension",true,0));
  change("base",true,0);change("extension",true,0);
  rejected("moduleDependencyConflict",()->change("base",false,1));
  change("extension",false,1);change("base",false,1);
  rejected("moduleDependencyConflict",()->change("extension",true,2));
  change("base",true,2);change("extension",true,2);assertTrue(store.states().stream().allMatch(ModuleStateStore.State::enabled));
  assertEquals(6,count("platform_module_revision"));assertEquals(6,count("platform_audit"));
 }
 @Test void rejectsUnknownStaleAndInvalidInputAndKeepsNoopIdempotent(){
  rejected("moduleNotFound",()->change("unknown",false,0));rejected("invalidModuleChange",()->change("base",true,-1));
  rejected("invalidModuleChange",()->tx.execute(s->store.change("base",true,0,"admin"," ")));
  assertEquals(0,change("base",false,0).version());assertEquals(0,count("platform_module_state"));
  change("base",true,0);rejected("staleRecord",()->change("base",false,0));
  assertEquals(1,change("base",true,1).version());assertEquals(1,count("platform_module_revision"));
  jdbc.update("INSERT INTO platform_module_state VALUES(?, 'unloaded',false,1,now())",UUID.randomUUID());assertEquals(5,store.states().size());
 }
 @Test void auditConsoleRemainsEnabledWhenAStoredRowSaysDisabled(){
  jdbc.update("INSERT INTO platform_module_state VALUES(?, ?, false, 3, now())",UUID.randomUUID(),"audit-console");
  var audit=store.states().stream().filter(state->state.moduleId().equals("audit-console")).findFirst().orElseThrow();
  assertTrue(audit.enabled());assertEquals(3,audit.version());assertTrue(store.isEnabled("audit-console"));assertTrue(store.enabledModules().contains("audit-console"));
  rejected("requiredModule",()->change("audit-console",false,3));assertEquals(0,count("platform_module_revision"));
 }
 @Test void auditFailureRollsBackStateAndHistory(){
  jdbc.execute("ALTER TABLE platform_audit ADD CONSTRAINT reject_fixture CHECK(action<>'module.enabled')");
  assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->change("base",true,0));
  assertEquals(0,count("platform_module_state"));assertEquals(0,count("platform_module_revision"));assertEquals(0,count("platform_audit"));assertFalse(store.states().get(2).enabled());
 }
 @Test void concurrentSameVersionHasExactlyOneWinner()throws Exception{
  var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
   Callable<Boolean> attempt=()->{start.await();try{change("base",true,0);return true;}catch(ModuleStateStore.Rejected error){assertEquals("staleRecord",error.getMessage());return false;}};
   var first=pool.submit(attempt);var second=pool.submit(attempt);start.countDown();assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
  }
  assertEquals(1,count("platform_module_revision"));assertEquals(1,count("platform_audit"));
 }
 @Test void concurrentDependencyChangesCannotBreakGraph()throws Exception{
  change("base",true,0);var start=new CountDownLatch(1);
  try(var pool=Executors.newFixedThreadPool(2)){
   var first=pool.submit(()->attempt(start,"base",false,1));var second=pool.submit(()->attempt(start,"extension",true,0));start.countDown();
   assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
  }
  var states=store.states();assertTrue(!states.get(3).enabled()||states.get(2).enabled());assertEquals(2,count("platform_module_revision"));
 }
 boolean attempt(CountDownLatch start,String id,boolean enabled,long version)throws Exception{
  start.await();try{change(id,enabled,version);return true;}catch(ModuleStateStore.Rejected error){assertEquals("moduleDependencyConflict",error.getMessage());return false;}
 }
}
