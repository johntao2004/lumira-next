package com.lumira.platform.identity;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import com.lumira.platform.identity.api.PasswordContract;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountEmailService {
    public record Request(long version,String email,@com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String currentPassword,String locale){
        @Override public String toString(){return "EmailVerificationRequest[redacted]";}
    }
    private final ProfileRepository profiles;private final AccountEmailStore emails;private final MailSender mail;private final AuditPort audit;private final JdbcClient jdbc;private final PasswordEncoder passwords;private final SecurityPolicyPort security;
    @org.springframework.beans.factory.annotation.Autowired
    public AccountEmailService(ProfileRepository profiles,AccountEmailStore emails,MailSender mail,AuditPort audit,JdbcClient jdbc,PasswordEncoder passwords,SecurityPolicyPort security){this.profiles=profiles;this.emails=emails;this.mail=mail;this.audit=audit;this.jdbc=jdbc;this.passwords=passwords;this.security=security;}
    public AccountEmailService(ProfileRepository profiles,AccountEmailStore emails,MailSender mail,AuditPort audit,JdbcClient jdbc,PasswordEncoder passwords){this(profiles,emails,mail,audit,jdbc,passwords,null);}
    private SecurityPolicyPort.Policy policy(){return security==null?SecurityPolicyPort.Policy.defaults():security.currentPolicy();}
    @Transactional(readOnly=true)
    public AccountEmailStore.View read(String actor){return emails.read(profiles.find(actor,false).id());}
    @Transactional
    public AccountEmailStore.View request(String actor,Request input){
        if(input==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidEmail");
        try{mail.validateRecipient(input.email());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidEmail");}
        var account=profiles.find(actor,true);
        String hash=jdbc.sql("SELECT password_hash FROM platform_user WHERE id=:id").param("id",account.id()).query(String.class).single();
        if(!PasswordContract.compatibleWithStoredHash(input.currentPassword(),hash)||!passwords.matches(input.currentPassword(),hash))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"wrongCurrentPassword");
        mail.ensureAvailable();
        var token=VerificationToken.issue();var view=emails.request(account.id(),input.version(),input.email(),token);
        var copy=COPY.getOrDefault(input.locale()==null?"zh-CN":input.locale(),COPY.get("en"));
        if(!mail.enqueue(java.util.UUID.randomUUID(),input.email(),copy[0],copy[1]+"\n\n"+token.value(),policy().verificationCodeExpireSeconds()))throw new IllegalStateException("Mail task collision");
        audit.append(actor,"account.emailVerificationRequested",account.id());return view;
    }
    @Transactional
    public boolean confirm(String actor,String token){
        var account=profiles.find(actor,true);boolean confirmed=emails.confirm(account.id(),token);
        audit.append(actor,confirmed?"account.emailVerified":"account.emailVerificationFailed",account.id());return confirmed;
    }
    private static final Map<String,String[]> COPY=Map.of(
        "zh-CN",new String[]{"验证账号邮箱","请在账号邮箱验证中粘贴以下验证码，验证码按当前安全策略有效。如非本人操作，请忽略此邮件。"},
        "zh-TW",new String[]{"驗證帳號信箱","請在帳號信箱驗證中貼上以下驗證碼，驗證碼依目前安全策略有效。如非本人操作，請忽略此郵件。"},
        "en",new String[]{"Verify your account email","Paste this code into account email verification. The code follows the current security policy. Ignore this message if you did not request it."},
        "ja",new String[]{"アカウントメールの確認","アカウントのメール確認欄に以下のコードを貼り付けてください。コードは現在のセキュリティポリシーに従います。心当たりがない場合は無視してください。"},
        "ko",new String[]{"계정 이메일 확인","계정 이메일 확인란에 아래 코드를 붙여 넣으세요. 코드는 현재 보안 정책을 따릅니다. 요청하지 않았다면 이 메일을 무시하세요."},
        "fr",new String[]{"Vérifiez votre adresse email","Collez ce code dans la vérification de votre compte. Le code suit la politique de sécurité actuelle. Ignorez ce message si vous ne l’avez pas demandé."},
        "es",new String[]{"Verifique el correo de su cuenta","Pegue este código en la verificación de su cuenta. El código sigue la política de seguridad actual. Ignore el mensaje si no lo solicitó."},
        "de",new String[]{"E-Mail-Adresse bestätigen","Fügen Sie diesen Code in die E-Mail-Bestätigung Ihres Kontos ein. Der Code folgt der aktuellen Sicherheitsrichtlinie. Ignorieren Sie unangeforderte Nachrichten."}
    );
}
