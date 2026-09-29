package com.lumira.platform.identity;

import java.util.*;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.MailSender;
import com.lumira.platform.identity.api.PasswordContract;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Internal orchestration. Public adapters must throttle before calling either operation. */
@Service
public class PasswordRecoveryService {
 private final JdbcClient jdbc;private final PasswordResetStore resets;private final MailSender mail;private final AuditPort audit;private final PasswordEncoder encoder;
 private final SecurityPolicyPort security;
 @org.springframework.beans.factory.annotation.Autowired
 public PasswordRecoveryService(JdbcClient jdbc,PasswordResetStore resets,MailSender mail,AuditPort audit,PasswordEncoder encoder,SecurityPolicyPort security){this.jdbc=jdbc;this.resets=resets;this.mail=mail;this.audit=audit;this.encoder=encoder;this.security=security;}
 public PasswordRecoveryService(JdbcClient jdbc,PasswordResetStore resets,MailSender mail,AuditPort audit,PasswordEncoder encoder){this(jdbc,resets,mail,audit,encoder,null);}
 private Optional<UUID> owner(String username){
  if(username==null||username.isBlank()||username.length()>100)return Optional.empty();
  return jdbc.sql("SELECT id FROM platform_user WHERE username=:name").param("name",username).query(UUID.class).optional();
 }
 /** Void for unknown, disabled, unverified and throttled accounts alike; never return address or token. */
 @Transactional
 public void request(String username,String locale){
  mail.ensureAvailable();
  var owner=owner(username);if(owner.isEmpty())return;
  var token=VerificationToken.issue();var recipient=resets.request(owner.get(),token);if(recipient.isEmpty())return;
  var copy=COPY.getOrDefault(locale==null?"zh-CN":locale,COPY.get("en"));
  if(!mail.enqueue(UUID.randomUUID(),recipient.get(),copy[0],copy[1]+"\n\n"+token.value(),policy().verificationCodeExpireSeconds()))throw new IllegalStateException("Mail task collision");
  audit.append(username,"account.passwordRecoveryRequested",owner.get());
 }
 private SecurityPolicyPort.Policy policy(){return security==null?SecurityPolicyPort.Policy.defaults():security.currentPolicy();}
 /** False must reach the adapter after commit, preserving failed-proof counters. */
 @Transactional
 public boolean confirm(String username,String token,String newPassword){
  if(!PasswordPolicy.valid(newPassword,security==null?SecurityPolicyPort.Policy.defaults():security.currentPolicy()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"passwordPolicy");
  // Perform the expensive hash for unknown usernames too; adapters still need request throttling.
  String encoded=encoder.encode(newPassword);var owner=owner(username);if(owner.isEmpty())return false;
  boolean consumed=resets.consume(owner.get(),token,oldHash->{
   if(PasswordContract.compatibleWithStoredHash(newPassword,oldHash) && encoder.matches(newPassword,oldHash))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"passwordMustDiffer");
   return encoded;
  });
  audit.append(username,consumed?"account.passwordRecovered":"account.passwordRecoveryFailed",owner.get());return consumed;
 }
 private static final Map<String,String[]> COPY=Map.of(
  "zh-CN",new String[]{"重置账号密码","请在找回密码页面输入账号、以下验证码和新密码。验证码按当前安全策略有效。如非本人操作，请忽略此邮件。"},
  "zh-TW",new String[]{"重設帳號密碼","請在找回密碼頁面輸入帳號、以下驗證碼和新密碼。驗證碼依目前安全策略有效。如非本人操作，請忽略此郵件。"},
  "en",new String[]{"Reset your account password","Enter your username, this code and a new password on the password recovery page. The code follows the current security policy. Ignore this message if you did not request it."},
  "ja",new String[]{"パスワードをリセット","パスワード再設定画面でユーザー名、以下のコード、新しいパスワードを入力してください。コードは現在のセキュリティポリシーに従います。心当たりがない場合は無視してください。"},
  "ko",new String[]{"계정 비밀번호 재설정","비밀번호 찾기 페이지에서 사용자 이름, 아래 코드, 새 비밀번호를 입력하세요. 코드는 현재 보안 정책을 따릅니다. 요청하지 않았다면 무시하세요."},
  "fr",new String[]{"Réinitialisez votre mot de passe","Saisissez votre identifiant, ce code et un nouveau mot de passe sur la page de récupération. Le code suit la politique de sécurité actuelle. Ignorez les demandes non sollicitées."},
  "es",new String[]{"Restablezca su contraseña","Introduzca su usuario, este código y una contraseña nueva en la página de recuperación. El código sigue la política de seguridad actual. Ignore el mensaje si no lo solicitó."},
  "de",new String[]{"Passwort zurücksetzen","Geben Sie auf der Wiederherstellungsseite Ihren Benutzernamen, diesen Code und ein neues Passwort ein. Der Code folgt der aktuellen Sicherheitsrichtlinie. Ignorieren Sie unangeforderte Nachrichten."}
 );
}
