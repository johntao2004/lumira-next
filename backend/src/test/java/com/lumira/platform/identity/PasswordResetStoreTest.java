package com.lumira.platform.identity;

import com.lumira.platform.TestMigration;

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
class PasswordResetStoreTest {
 JdbcTemplate admin,jdbc;TransactionTemplate tx;PasswordResetStore store;String schema;UUID id=UUID.randomUUID();
 @BeforeEach void setup()throws Exception{
  String url=System.getenv("LUMIRA_TEST_DB_URL"),user=System.getenv("LUMIRA_TEST_DB_USER"),password=System.getenv("LUMIRA_TEST_DB_PASSWORD");
  admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="qa_reset_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
  var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new PasswordResetStore(JdbcClient.create(ds));
  jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,username TEXT NOT NULL,password_hash TEXT NOT NULL,enabled BOOLEAN NOT NULL)");
  jdbc.execute("CREATE TABLE spring_session(principal_name TEXT)");
  jdbc.update("INSERT INTO platform_user VALUES(?,'owner','old-hash',true)",id);
  jdbc.execute(TestMigration.read("V25__account_email_verification.sql"));
  jdbc.execute(TestMigration.read("V26__password_reset_challenges.sql"));
  jdbc.update("INSERT INTO platform_account_email(user_id,email,verified_at) VALUES(?,'verified@example.test',now())",id);
 }
 @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
 VerificationToken.Issued issue(){var token=VerificationToken.issue();assertEquals(Optional.of("verified@example.test"),tx.execute(s->store.request(id,token)));return token;}
 boolean consume(String token){return Boolean.TRUE.equals(tx.execute(s->store.consume(id,token,"new-hash")));}
 int scalar(String query){return jdbc.queryForObject(query,Integer.class);}
 @Test void resetsOnceAndRevokesOnlyOwnerSessions(){
  jdbc.update("INSERT INTO spring_session VALUES('owner'),('owner'),('other')");var token=issue();
  assertEquals(token.digest(),jdbc.queryForObject("SELECT token_hash FROM platform_password_reset",String.class));
  assertTrue(consume(token.value()));assertFalse(consume(token.value()));
  assertEquals("new-hash",jdbc.queryForObject("SELECT password_hash FROM platform_user",String.class));
  assertEquals(List.of("other"),jdbc.queryForList("SELECT principal_name FROM spring_session",String.class));
  assertNull(jdbc.queryForObject("SELECT password_snapshot FROM platform_password_reset",String.class));
 }
 @Test void requiresEnabledAccountAndVerifiedEmail(){
  jdbc.update("UPDATE platform_user SET enabled=false");assertTrue(tx.execute(s->store.request(id,VerificationToken.issue())).isEmpty());
  jdbc.update("UPDATE platform_user SET enabled=true");jdbc.update("UPDATE platform_account_email SET email=NULL,verified_at=NULL,pending_email='pending@example.test',token_hash=?,expires_at=now()+interval '1 hour'",VerificationToken.issue().digest());
  assertTrue(tx.execute(s->store.request(id,VerificationToken.issue())).isEmpty());assertEquals(0,scalar("SELECT count(*) FROM platform_password_reset"));
 }
 @Test void expiresReplacesAndCountsFailedAttempts(){
  var first=issue();for(int n=0;n<5;n++)assertFalse(consume("bad"));assertFalse(consume(first.value()));
  assertEquals(5,scalar("SELECT failures FROM platform_password_reset"));jdbc.update("UPDATE platform_password_reset SET requested_at=now()-interval '2 minutes'");
  var next=issue();assertFalse(consume(first.value()));jdbc.update("UPDATE platform_password_reset SET expires_at=now()-interval '1 second'");assertFalse(consume(next.value()));
 }
 @Test void passwordOrEmailChangesInvalidateOutstandingTokens(){
  var first=issue();jdbc.update("UPDATE platform_user SET password_hash='changed-hash'");assertFalse(consume(first.value()));
  jdbc.update("UPDATE platform_password_reset SET requested_at=now()-interval '2 minutes'");var next=issue();jdbc.update("UPDATE platform_account_email SET version=version+1");assertFalse(consume(next.value()));
  assertEquals("changed-hash",jdbc.queryForObject("SELECT password_hash FROM platform_user",String.class));
 }
 @Test void cooldownAndDailyLimitsSurviveConsumption(){
  var token=issue();assertTrue(tx.execute(s->store.request(id,VerificationToken.issue())).isEmpty());assertTrue(consume(token.value()));
  assertTrue(tx.execute(s->store.request(id,VerificationToken.issue())).isEmpty());jdbc.update("UPDATE platform_password_reset SET requested_at=now()-interval '2 minutes',request_count=10");
  assertTrue(tx.execute(s->store.request(id,VerificationToken.issue())).isEmpty());jdbc.update("UPDATE platform_password_reset SET request_day=CURRENT_DATE-1");issue();assertEquals(1,scalar("SELECT request_count FROM platform_password_reset"));
 }
 @Test void callerFailureRollsBackPasswordSessionsAndChallenge(){
  jdbc.update("INSERT INTO spring_session VALUES('owner')");var token=issue();
  assertThrows(IllegalStateException.class,()->tx.execute(s->{assertTrue(store.consume(id,token.value(),"new-hash"));throw new IllegalStateException("audit failed");}));
  assertEquals("old-hash",jdbc.queryForObject("SELECT password_hash FROM platform_user",String.class));assertEquals(1,scalar("SELECT count(*) FROM spring_session"));assertTrue(consume(token.value()));
 }
 @Test void concurrentConsumptionHasOneWinnerAndForeignTokensFail()throws Exception{
  var token=issue();UUID other=UUID.randomUUID();jdbc.update("INSERT INTO platform_user VALUES(?,'other','other-hash',true)",other);jdbc.update("INSERT INTO platform_account_email(user_id,email,verified_at) VALUES(?,'other@example.test',now())",other);
  assertEquals(Boolean.FALSE,tx.execute(s->store.consume(other,token.value(),"new-hash")));
  var gate=new CyclicBarrier(2);Callable<Boolean> call=()->{gate.await(5,TimeUnit.SECONDS);return consume(token.value());};
  try(var pool=Executors.newFixedThreadPool(2)){var one=pool.submit(call);var two=pool.submit(call);assertNotEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));}
  jdbc.update("DELETE FROM platform_user WHERE id=?",id);assertEquals(0,scalar("SELECT count(*) FROM platform_password_reset"));
 }
}
