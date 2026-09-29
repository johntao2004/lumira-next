package com.lumira.platform.identity;

import com.lumira.platform.TestMigration;
import com.lumira.platform.mail.*;
import com.lumira.platform.common.MailConfigurationPort;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.audit.AuditRepository;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="LUMIRA_TEST_DB_URL",matches=".+")
class AccountEmailServiceTest {
    JdbcTemplate admin,jdbc;TransactionTemplate tx;String schema;UUID owner=UUID.randomUUID();AccountEmailService service;AccountEmailStore emails;MailDeliveryStore mail;
    SmtpConnectionSource smtp=mock(SmtpConnectionSource.class);ProfileRepository profiles=mock(ProfileRepository.class);PasswordEncoder encoder=mock(PasswordEncoder.class);
    @BeforeEach void setup()throws Exception{
        String url=System.getenv("LUMIRA_TEST_DB_URL"),user=System.getenv("LUMIRA_TEST_DB_USER"),password=System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="qa_email_service_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);var client=JdbcClient.create(ds);
        jdbc.execute("CREATE TABLE platform_user(id UUID PRIMARY KEY,password_hash TEXT)");jdbc.update("INSERT INTO platform_user VALUES(?,'fixture-hash')",owner);
        for(String migration:List.of("V24__mail_delivery_queue.sql","V25__account_email_verification.sql"))jdbc.execute(TestMigration.read(migration));
        String ddl=TestMigration.read("V2__user_management.sql");int start=ddl.indexOf("CREATE TABLE platform_audit");jdbc.execute(ddl.substring(start,ddl.indexOf(';',start)+1));
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));emails=new AccountEmailStore(client);
        mail=new MailDeliveryStore(client,new SecretKeys("primary="+Base64.getEncoder().encodeToString(new byte[32]),"primary"),new ObjectMapper());
        when(profiles.find(eq("fixture"),anyBoolean())).thenReturn(new ProfileRepository.Profile(owner,"fixture","Fixture","","","",0));when(encoder.matches("current","fixture-hash")).thenReturn(true);
        when(smtp.load()).thenAnswer(i->new SmtpConnection(new MailConfigurationPort.Settings(true,"localhost",587,"STARTTLS",true,"fixture","sender@example.test",""),new byte[]{1}));
        service=new AccountEmailService(profiles,emails,new MailSenderAdapter(mail,smtp),new AuditRepository(client),client,encoder);
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
    AccountEmailService.Request input(){return new AccountEmailService.Request(0,"recipient@example.test","current","zh-CN");}
    @Test void requestQueuesEncryptedCodeAndConfirmationConsumesIt(){
        var view=tx.execute(s->service.request("fixture",input()));assertNull(view.email());assertEquals("recipient@example.test",view.pendingEmail());
        var claim=tx.execute(s->mail.claim().orElseThrow());var outgoing=mail.message(claim).orElseThrow();assertEquals("recipient@example.test",outgoing.recipient());assertEquals("验证账号邮箱",outgoing.subject());
        String token=outgoing.text().substring(outgoing.text().lastIndexOf('\n')+1);assertEquals(43,token.length());assertFalse(new ObjectMapper().writeValueAsString(view).contains(token));
        assertEquals(Boolean.TRUE,tx.execute(s->service.confirm("fixture",token)));assertEquals("recipient@example.test",emails.read(owner).email());assertEquals(Boolean.FALSE,tx.execute(s->service.confirm("fixture",token)));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM platform_audit",Integer.class));
    }
    @Test void failedConfirmationCommitsItsCounter(){
        tx.execute(s->service.request("fixture",input()));assertEquals(Boolean.FALSE,tx.execute(s->service.confirm("fixture","wrong")));
        assertEquals(1,jdbc.queryForObject("SELECT failures FROM platform_account_email",Integer.class));assertNull(emails.read(owner).email());
    }
    @Test void invalidPasswordOrUnavailableSmtpCreatesNoChallengeOrMail(){
        assertThrows(ResponseStatusException.class,()->tx.execute(s->service.request("fixture",new AccountEmailService.Request(0,"recipient@example.test","wrong","en"))));verifyNoInteractions(smtp);
        when(smtp.load()).thenThrow(new IllegalStateException("Fixture unavailable"));assertThrows(IllegalStateException.class,()->tx.execute(s->service.request("fixture",input())));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_account_email",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_mail_delivery",Integer.class));
    }
    @Test void lateAuditFailureRollsBackChallengeAndQueuedMail(){
        jdbc.execute("CREATE FUNCTION fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Fixture failure'; END $$; CREATE TRIGGER fail_audit BEFORE INSERT ON platform_audit FOR EACH ROW EXECUTE FUNCTION fail_audit()");
        assertThrows(org.springframework.dao.DataAccessException.class,()->tx.execute(s->service.request("fixture",input())));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_account_email",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM platform_mail_delivery",Integer.class));
    }
}
