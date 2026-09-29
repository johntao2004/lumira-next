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
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="LUMIRA_TEST_DB_URL",matches=".+")
class AccountEmailStoreTest {
    JdbcTemplate admin,jdbc;TransactionTemplate tx;AccountEmailStore store;String schema;UUID owner=UUID.randomUUID();
    @BeforeEach void setup()throws Exception{
        String url=System.getenv("LUMIRA_TEST_DB_URL"),user=System.getenv("LUMIRA_TEST_DB_USER"),password=System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="qa_email_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY)");jdbc.update("INSERT INTO platform_user(id) VALUES(?)",owner);
        jdbc.execute(TestMigration.read("V25__account_email_verification.sql"));
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new AccountEmailStore(JdbcClient.create(ds));
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    VerificationToken.Issued request(long version,String email){var token=VerificationToken.issue();tx.execute(s->store.request(owner,version,email,token));return token;}
    boolean confirm(String token){return Boolean.TRUE.equals(tx.execute(s->store.confirm(owner,token)));}
    @Test void pendingAddressDoesNotChangeVerifiedAddressAndTokensAreSingleUse(){
        var token=request(0,"first@example.test");assertNull(store.read(owner).email());assertEquals("first@example.test",store.read(owner).pendingEmail());
        assertEquals(token.digest(),jdbc.queryForObject("SELECT token_hash FROM platform_account_email",String.class));
        assertTrue(confirm(token.value()));assertEquals("first@example.test",store.read(owner).email());assertNull(store.read(owner).pendingEmail());assertFalse(confirm(token.value()));
        jdbc.update("UPDATE platform_account_email SET requested_at=now()-interval '2 minutes'");
        var next=request(2,"next@example.test");assertEquals("first@example.test",store.read(owner).email());assertTrue(confirm(next.value()));assertEquals("next@example.test",store.read(owner).email());
    }
    @Test void expiryFailedAttemptsAndReplacementInvalidateChallenges(){
        var first=request(0,"first@example.test");
        for(int i=0;i<5;i++)assertFalse(confirm("invalid"));assertFalse(confirm(first.value()));
        jdbc.update("UPDATE platform_account_email SET requested_at=now()-interval '2 minutes'");
        var next=request(1,"next@example.test");assertFalse(confirm(first.value()));
        jdbc.update("UPDATE platform_account_email SET expires_at=now()-interval '1 second'");assertFalse(confirm(next.value()));assertNull(store.read(owner).email());
    }
    @Test void cooldownDailyLimitAndVersionProtectRequest(){
        request(0,"first@example.test");
        assertEquals(429,assertThrows(ResponseStatusException.class,()->request(1,"next@example.test")).getStatusCode().value());
        jdbc.update("UPDATE platform_account_email SET requested_at=now()-interval '2 minutes',request_count=10");
        assertEquals(429,assertThrows(ResponseStatusException.class,()->request(1,"next@example.test")).getStatusCode().value());
        jdbc.update("UPDATE platform_account_email SET request_day=CURRENT_DATE-1");request(1,"next@example.test");
        assertEquals(1,jdbc.queryForObject("SELECT request_count FROM platform_account_email",Integer.class));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->request(0,"stale@example.test")).getStatusCode().value());
    }
    @Test void mailOrAuditFailureRollsBackPendingAddress(){
        assertThrows(IllegalStateException.class,()->tx.execute(s->{store.request(owner,0,"first@example.test",VerificationToken.issue());throw new IllegalStateException("Mail enqueue failure");}));
        assertEquals(0,store.read(owner).version());assertNull(store.read(owner).pendingEmail());
    }
    @Test void concurrentConfirmationSucceedsExactlyOnce()throws Exception{
        var token=request(0,"first@example.test");var gate=new CyclicBarrier(2);
        Callable<Boolean> action=()->{gate.await(5,TimeUnit.SECONDS);return confirm(token.value());};
        try(var pool=Executors.newFixedThreadPool(2)){var one=pool.submit(action);var two=pool.submit(action);assertNotEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));}
        assertEquals(2,store.read(owner).version());
    }
    @Test void tokensCannotConfirmAnotherAccountAndEmailUniquenessIsCaseInsensitive(){
        var token=request(0,"first@example.test");UUID other=UUID.randomUUID();jdbc.update("INSERT INTO platform_user(id) VALUES(?)",other);
        assertEquals(Boolean.FALSE,tx.execute(s->store.confirm(other,token.value())));assertTrue(confirm(token.value()));
        var second=VerificationToken.issue();tx.execute(s->store.request(other,0,"FIRST@example.test",second));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.execute(s->store.confirm(other,second.value())));
        assertNull(store.read(other).email());assertNotNull(store.read(other).pendingEmail());
        jdbc.update("DELETE FROM platform_user WHERE id=?",other);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM platform_account_email",Integer.class));
    }
}
