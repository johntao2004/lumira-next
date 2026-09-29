package com.lumira.platform.mail;

import com.lumira.platform.common.MailConfigurationPort;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SmtpGatewayTest {
    private static MailConfigurationPort.Settings config(String security,boolean auth){return new MailConfigurationPort.Settings(true,"localhost",587,security,auth,"sender","sender@example.test","发件人");}
    private static OutgoingMail message(){return new OutgoingMail("recipient@example.test","测试主题","正文\nSecond line");}
    private static class FakeTransport extends Transport {
        Exception failure;boolean sent,closed;MimeMessage captured;String suppliedPassword;
        FakeTransport(Session session){super(session,null);}
        @Override protected boolean protocolConnect(String host,int port,String user,String password)throws MessagingException{
            suppliedPassword=password;
            if(failure instanceof AuthenticationFailedException e)throw e;
            if(failure instanceof java.net.ConnectException)throw new MessagingException("server secret",failure);
            return true;
        }
        @Override public void sendMessage(Message message,Address[] recipients)throws MessagingException{
            captured=(MimeMessage)message;sent=true;
            if(failure instanceof MessagingException e)throw e;
        }
        @Override public void close()throws MessagingException{closed=true;throw new MessagingException("QUIT failure must not change acceptance");}
    }
    @Test void sendsUtf8SingleRecipientAndClosesSecretsDespiteQuitFailure()throws Exception{
        var secret="credential".getBytes(StandardCharsets.UTF_8);var fake=new FakeTransport(Session.getInstance(new java.util.Properties()));
        var result=new SmtpGateway(s->fake).send(new SmtpConnection(config("TLS",true),secret),message());
        assertEquals(SmtpGateway.Result.ACCEPTED,result);assertTrue(fake.closed);assertArrayEquals(new byte[secret.length],secret);
        assertEquals("credential",fake.suppliedPassword);assertEquals("测试主题",fake.captured.getSubject());assertEquals("正文\nSecond line",fake.captured.getContent());
        assertEquals(1,fake.captured.getAllRecipients().length);assertTrue(fake.captured.getContentType().contains("text/plain"));
    }
    @Test void distinguishesConnectionAuthenticationAndUnknownDelivery(){
        for(var failure:new Exception[]{new AuthenticationFailedException("private server reply"),new java.net.ConnectException("private host"),new MessagingException("private recipient")}){
            var fake=new FakeTransport(Session.getInstance(new java.util.Properties()));fake.failure=failure;byte[] secret={1,2,3};
            var result=new SmtpGateway(s->fake).send(new SmtpConnection(config("STARTTLS",true),secret),message());
            assertEquals(failure instanceof AuthenticationFailedException?SmtpGateway.Result.AUTHENTICATION_FAILED:failure instanceof java.net.ConnectException?SmtpGateway.Result.CONNECTION_FAILED:SmtpGateway.Result.DELIVERY_UNCERTAIN,result);
            assertTrue(fake.closed);assertArrayEquals(new byte[3],secret);
        }
    }
    @Test void securePropertiesAndNoAuthenticationDoNotSupplyStoredPassword(){
        var tls=SmtpGateway.properties(config("TLS",false));var starttls=SmtpGateway.properties(config("STARTTLS",true));
        assertEquals("true",tls.getProperty("mail.smtp.ssl.enable"));assertEquals("false",tls.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true",starttls.getProperty("mail.smtp.starttls.required"));assertEquals("true",starttls.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(tls.getProperty("mail.smtp.ssl.trust"));assertEquals("5000",tls.getProperty("mail.smtp.connectiontimeout"));assertEquals("10000",tls.getProperty("mail.smtp.timeout"));
        var fake=new FakeTransport(Session.getInstance(new java.util.Properties()));byte[] unused={1};
        new SmtpGateway(s->fake).send(new SmtpConnection(config("TLS",false),unused),message());assertNull(fake.suppliedPassword);assertArrayEquals(new byte[1],unused);
    }
    @Test void rejectsHeaderInjectionListsAndOversizedMessages(){
        for(String recipient:new String[]{"a@example.test\r\nBcc:b@example.test","a@example.test,b@example.test","Name <a@example.test>","group:a@example.test;","local"})assertThrows(IllegalArgumentException.class,()->new OutgoingMail(recipient,"subject","body"));
        assertThrows(IllegalArgumentException.class,()->new OutgoingMail("a@example.test","subject\r\nBcc: b","body"));
        assertThrows(IllegalArgumentException.class,()->new OutgoingMail("a@example.test","subject","x".repeat(65537)));
        assertEquals("OutgoingMail[redacted]",message().toString());
    }
    @Test void actualProviderRefusesPlaintextServerWithoutStartTls()throws Exception{
        try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());var executor=Executors.newSingleThreadExecutor()){
            server.setSoTimeout(10000);
            var commands=executor.submit(()->{
                try(var socket=server.accept()){
                    socket.setSoTimeout(10000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));var writer=new PrintWriter(socket.getOutputStream(),true);
                    writer.print("220 localhost ESMTP\r\n");writer.flush();String ehlo=reader.readLine();assertTrue(ehlo.startsWith("EHLO "));
                    writer.print("250-localhost\r\n250 AUTH PLAIN LOGIN\r\n");writer.flush();
                    String next=reader.readLine();assertTrue(next==null||next.equals("QUIT"),"Client must not send credentials or mail without STARTTLS");
                    if(next!=null){writer.print("221 Bye\r\n");writer.flush();}return true;
                }
            });
            var cfg=new MailConfigurationPort.Settings(true,"localhost",server.getLocalPort(),"STARTTLS",true,"sender","sender@example.test","");byte[] secret={1,2,3};
            assertEquals(SmtpGateway.Result.CONNECTION_FAILED,new SmtpGateway().send(new SmtpConnection(cfg,secret),message()));
            assertTrue(commands.get(12,TimeUnit.SECONDS));assertArrayEquals(new byte[3],secret);
        }
    }
}
