package com.lumira.platform.mail;

import com.lumira.platform.common.MailConfigurationPort;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Properties;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.stereotype.Component;

/** One attempt only. ACCEPTED means the SMTP server accepted DATA, not inbox delivery. */
@Component
public class SmtpGateway {
    public enum Result { ACCEPTED, AUTHENTICATION_FAILED, CONNECTION_FAILED, DELIVERY_UNCERTAIN }
    @FunctionalInterface interface TransportFactory {Transport open(Session session)throws MessagingException;}
    private final TransportFactory factory;
    public SmtpGateway(){this(session->session.getTransport("smtp"));}
    SmtpGateway(TransportFactory factory){this.factory=factory;}
    static Properties properties(MailConfigurationPort.Settings data){
        var p=new Properties();
        p.setProperty("mail.smtp.auth",Boolean.toString(data.authentication()));
        p.setProperty("mail.smtp.ssl.enable",Boolean.toString(data.security().equals("TLS")));
        p.setProperty("mail.smtp.starttls.enable",Boolean.toString(data.security().equals("STARTTLS")));
        p.setProperty("mail.smtp.starttls.required",Boolean.toString(data.security().equals("STARTTLS")));
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");
        p.setProperty("mail.smtp.ssl.protocols","TLSv1.3 TLSv1.2");
        p.setProperty("mail.smtp.connectiontimeout","5000");p.setProperty("mail.smtp.timeout","10000");p.setProperty("mail.smtp.writetimeout","10000");
        p.setProperty("mail.smtp.sendpartial","false");p.setProperty("mail.smtp.quitwait","false");
        p.setProperty("mail.debug","false");p.setProperty("mail.debug.auth","false");
        return p;
    }
    /** Consumes and closes the supplied connection on all paths. Exceptions never expose server replies. */
    public Result send(SmtpConnection connection,OutgoingMail mail){
        return send(connection, mail, false);
    }

    /**
     * Test-only path. It accepts a self-signed loopback certificate while the
     * SMTP hostname check remains enabled; the persisted production settings
     * and the normal sender always use the JVM's regular CA trust store.
     */
    Result sendLocalTest(SmtpConnection connection, OutgoingMail mail) {
        var data = connection.settings();
        if (!data.enabled() || data.authentication() || !isLoopbackTestHost(data.host())) {
            connection.close();
            return Result.CONNECTION_FAILED;
        }
        return send(connection, mail, true);
    }

    static boolean isLoopbackTestHost(String host) {
        if (!"127.0.0.1".equals(host)) return false;
        try {
            var addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (var address : addresses) if (!address.isLoopbackAddress()) return false;
            return true;
        } catch (java.net.UnknownHostException invalid) {
            return false;
        }
    }

    private Result send(SmtpConnection connection, OutgoingMail mail, boolean loopbackTest) {
        Transport transport=null;boolean connected=false;
        try(connection){
            var data=connection.settings();
            if(!data.enabled()||mail==null||(data.authentication()&&(connection.password()==null||connection.password().length==0)))throw new IllegalArgumentException("Mail configuration unavailable");
            Properties transportProperties = properties(data);
            if (loopbackTest) transportProperties.put("mail.smtp.ssl.socketFactory", loopbackTestSocketFactory());
            Session session=Session.getInstance(transportProperties);session.setDebug(false);
            var message=new MimeMessage(session);
            message.setFrom(new InternetAddress(data.fromAddress(),data.fromName(),StandardCharsets.UTF_8.name()));
            message.setRecipient(Message.RecipientType.TO,new InternetAddress(mail.recipient(),true));
            message.setSubject(mail.subject(),StandardCharsets.UTF_8.name());message.setText(mail.text(),StandardCharsets.UTF_8.name());message.setSentDate(new Date());message.saveChanges();
            transport=factory.open(session);
            // Jakarta Mail requires a String for authentication; retain no sender/session cache.
            transport.connect(data.host(),data.port(),data.authentication()?data.username():null,data.authentication()?new String(connection.password(),StandardCharsets.UTF_8):null);
            connected=true;
            transport.sendMessage(message,message.getAllRecipients());
            return Result.ACCEPTED;
        }catch(AuthenticationFailedException e){return connected?Result.DELIVERY_UNCERTAIN:Result.AUTHENTICATION_FAILED;}
        catch(MessagingException|java.io.UnsupportedEncodingException|GeneralSecurityException e){return connected?Result.DELIVERY_UNCERTAIN:Result.CONNECTION_FAILED;}
        finally{if(transport!=null)try{transport.close();}catch(MessagingException ignored){/* DATA outcome takes precedence over QUIT failure. */}}
    }

    private static SSLSocketFactory loopbackTestSocketFactory() throws GeneralSecurityException {
        X509TrustManager testOnlyTrustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[] { testOnlyTrustManager }, null);
        return context.getSocketFactory();
    }
}
