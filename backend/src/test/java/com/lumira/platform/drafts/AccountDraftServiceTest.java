package com.lumira.platform.drafts;

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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LUMIRA_TEST_DB_URL",matches=".+")
class AccountDraftServiceTest {
 JdbcTemplate admin,jdbc;TransactionTemplate tx;AccountDraftService service;String schema;JsonMapper json=new JsonMapper();
 @BeforeEach void setup()throws Exception{
  String url=System.getenv("LUMIRA_TEST_DB_URL"),user=System.getenv("LUMIRA_TEST_DB_USER"),password=System.getenv("LUMIRA_TEST_DB_PASSWORD");
  admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="qa_drafts_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));var client=JdbcClient.create(ds);service=new AccountDraftService(client,json,new AuditRepository(client));
  jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT UNIQUE,enabled BOOLEAN)");
  jdbc.execute("CREATE TABLE platform_audit(id UUID PRIMARY KEY,actor TEXT,action TEXT,target_id UUID)");
  jdbc.execute(TestMigration.read("V33__account_drafts.sql"));
  for(String name:List.of("alice","bob"))jdbc.update("INSERT INTO platform_user VALUES(?,?,true)",UUID.randomUUID(),name);
 }
 @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
 AccountDraftService.Draft save(String who,String key,UUID revision,String value){return tx.execute(s->service.save(who,key,new AccountDraftService.Input(revision,json.readTree(value))));}
 void delete(String who,String key,UUID revision){tx.executeWithoutResult(s->service.delete(who,key,revision));}
 void rejected(String code,Runnable action){assertEquals(code,assertThrows(ResponseStatusException.class,action::run).getReason());}
 @Test void listsOnlyOwnerMetadataWithStablePaginationAndLiteralSearch(){
  for(int i=0;i<25;i++)save("alice","form"+i,null,"{\"private\":true}");save("bob","form0",null,"{}");
  var first=service.list("alice",new com.lumira.platform.common.PageRequest(1,20,""));var second=service.list("alice",new com.lumira.platform.common.PageRequest(2,20,""));
  assertEquals(25,first.total());assertEquals(20,first.items().size());assertEquals(5,second.items().size());
  var ids=new HashSet<UUID>();first.items().forEach(row->ids.add(row.id()));second.items().forEach(row->ids.add(row.id()));assertEquals(25,ids.size());
  assertEquals(1,service.list("bob",new com.lumira.platform.common.PageRequest(1,20,"")).total());
  assertEquals(11,service.list("alice",new com.lumira.platform.common.PageRequest(1,20,"FORM1")).total());
  assertEquals(0,service.list("alice",new com.lumira.platform.common.PageRequest(1,20,"%")).total());
 }
 @Test void isolatesOwnersAndRejectsStaleSavesAndDeletes(){
  assertNull(service.find("alice","form"));var a=save("alice","form",null,"{\"text\":\"中文\"}");
  assertNull(service.find("bob","form"));var b=save("bob","form",null,"[]");
  rejected("staleRecord",()->save("alice","form",null,"{}"));
  var latest=save("alice","form",a.revision(),"{\"text\":\"new\"}");
  rejected("staleRecord",()->delete("alice","form",a.revision()));
  delete("alice","form",latest.revision());delete("alice","form",latest.revision());assertNull(service.find("alice","form"));
  var recreated=save("alice","form",null,"{}");assertNotEquals(latest.revision(),recreated.revision());
  rejected("staleRecord",()->save("alice","form",latest.revision(),"{}"));assertEquals(b,service.find("bob","form"));
 }
 @Test void limitsUtf8PayloadAndCountAndRestoresQuotaAfterDelete(){
  rejected("invalidDraft",()->save("alice","UPPER",null,"{}"));rejected("invalidDraft",()->save("alice","form",null,"null"));
  rejected("draftTooLarge",()->save("alice","form",null,"\""+"中".repeat(333334)+"\""));
  var first=save("alice","form0",null,"{}");for(int i=1;i<50;i++)save("alice","form"+i,null,"{}");
  rejected("draftQuotaExceeded",()->save("alice","extra",null,"{}"));
  save("alice","form0",first.revision(),"[]");var latest=service.find("alice","form0");delete("alice","form0",latest.revision());save("alice","extra",null,"{}");
 }
 @Test void limitsTotalBytesAndDoesNotChargeReplacementTwice(){
  String payload="\""+"x".repeat(999998)+"\"";for(int i=0;i<5;i++)save("alice","form"+i,null,payload);
  rejected("draftQuotaExceeded",()->save("alice","extra",null,"{}"));
  var old=service.find("alice","form0");save("alice","form0",old.revision(),payload);
  old=service.find("alice","form0");save("alice","form0",old.revision(),"{}");save("alice","extra",null,"{}");
 }
 @Test void preservesJsonEscapesWithoutDatabaseJsonCoercion(){
  var payload=json.createObjectNode().put("text","a"+(char)0+"b").put("numberAsText","1e999999");
  var saved=save("alice","form",null,json.writeValueAsString(payload));assertEquals(payload,saved.payload());assertEquals(payload,service.find("alice","form").payload());
 }
 @Test void accountDeletionDoesNotExposeDraftsToRecreatedUsername(){
  save("alice","form",null,"{}");jdbc.update("DELETE FROM platform_user WHERE username='alice'");jdbc.update("INSERT INTO platform_user VALUES(?,'alice',true)",UUID.randomUUID());assertNull(service.find("alice","form"));
  jdbc.update("UPDATE platform_user SET enabled=false WHERE username='alice'");rejected("unauthorized",()->service.find("alice","form"));
 }
 @Test void auditFailureRollsBackSaveAndDelete(){
  jdbc.execute("ALTER TABLE platform_audit ADD CONSTRAINT reject_write CHECK(action<>'draft.saved')");
  assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->save("alice","form",null,"{}"));assertNull(service.find("alice","form"));
  jdbc.execute("ALTER TABLE platform_audit DROP CONSTRAINT reject_write");var draft=save("alice","form",null,"{}");
  jdbc.execute("ALTER TABLE platform_audit ADD CONSTRAINT reject_delete CHECK(action<>'draft.deleted')");
  assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->delete("alice","form",draft.revision()));assertEquals(draft,service.find("alice","form"));
 }
 @Test void concurrentCreateHasOneWinner()throws Exception{
  var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
   Callable<Boolean> attempt=()->{start.await();try{save("alice","form",null,"{}");return true;}catch(ResponseStatusException ex){assertEquals("staleRecord",ex.getReason());return false;}};
   var a=pool.submit(attempt);var b=pool.submit(attempt);start.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
  }
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM platform_account_draft",Integer.class));
 }
}
