package com.lumira.platform.settings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import com.lumira.platform.common.MailConfigurationPort;
import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.common.SecretStorePort;
import com.lumira.platform.common.AdministrationPort;
import com.lumira.platform.common.AuditPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SmtpSettingsService implements MailConfigurationPort {
    public record Credentials(String action,@com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String value){
        @Override public String toString(){return "Credentials[redacted]";}
        public void validate(){
            if(!("KEEP".equals(action)||"REPLACE".equals(action)||"CLEAR".equals(action)))invalid("invalidSmtpCredentials");
            if(action.equals("REPLACE")){
                if(value==null||value.isEmpty()||value.getBytes(StandardCharsets.UTF_8).length>4096||value.codePoints().anyMatch(Character::isISOControl))invalid("invalidSmtpCredentials");
            }else if(value!=null)invalid("invalidSmtpCredentials");
        }
    }
    public record Input(long version,SmtpSettings data,Credentials credentials,String changeReason,Long sourceVersion){}
    public record View(long version,SmtpSettings data,boolean passwordConfigured,boolean encryptionConfigured){}
    private final SettingStore settings;private final SecretStore secrets;private final SecretKeys keys;
    private final AdministrationPort guard;private final ObjectMapper mapper;private final AuditPort audit;
    public SmtpSettingsService(SettingStore settings,SecretStore secrets,SecretKeys keys,AdministrationPort guard,ObjectMapper mapper,AuditPort audit){
        this.settings=settings;this.secrets=secrets;this.keys=keys;this.guard=guard;this.mapper=mapper;this.audit=audit;
    }
    private static void invalid(String code){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,code);}
    private static String context(UUID id){return "settings:"+id+":smtp-password";}
    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public MailConfigurationPort.Settings readSettings(){
        SmtpSettings data=read().data();data.validate();
        return new MailConfigurationPort.Settings(data.enabled(),data.host(),data.port(),data.security(),
            data.authentication(),data.username(),data.fromAddress(),data.fromName());
    }
    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Configuration load(){
        var snapshot=settings.find("smtp").orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"smtpUnavailable"));
        var data=mapper.readValue(snapshot.payload(),SmtpSettings.class);data.validate();
        if(!data.enabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"smtpUnavailable");
        byte[] password=null;
        if(data.authentication()){
            try{password=secrets.reveal(context(snapshot.id())).orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"smtpPasswordRequired"));}
            catch(IllegalStateException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"secretKeyUnavailable");}
        }
        return new Configuration(new MailConfigurationPort.Settings(data.enabled(),data.host(),data.port(),data.security(),data.authentication(),data.username(),data.fromAddress(),data.fromName()),password);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public View read(){
        var current=settings.find("smtp");
        return current.map(s->new View(s.version(),mapper.readValue(s.payload(),SmtpSettings.class),secrets.state(context(s.id())).configured(),keys.configured()))
            .orElseGet(()->new View(0,SmtpSettings.defaults(),false,keys.configured()));
    }
    @Transactional
    public View save(String actor,Input input){
        guard.require(actor,"platform:settings:write");guard.requireGlobal(actor,"platform:settings:write");
        if(input==null||input.data()==null||input.credentials()==null)invalid("invalidSmtp");
        input.data().validate();input.credentials().validate();
        var current=settings.find("smtp");
        var state=current.map(s->secrets.state(context(s.id()))).orElse(new SecretStorePort.State(0,false));
        String action=input.credentials().action();
        if(action.equals("KEEP")&&state.configured()&&!input.data().sameCredentialDestination(mapper.readValue(current.orElseThrow().payload(),SmtpSettings.class)))invalid("smtpPasswordReplacementRequired");
        boolean willHavePassword=action.equals("REPLACE")||(action.equals("KEEP")&&state.configured());
        if(input.data().enabled()&&input.data().authentication()){
            if(!willHavePassword)invalid("smtpPasswordRequired");
            keys.require();
            if(action.equals("KEEP")){
                try{secrets.reveal(context(current.orElseThrow().id())).ifPresent(bytes->Arrays.fill(bytes,(byte)0));}
                catch(IllegalStateException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"secretKeyUnavailable");}
            }
        }
        var saved=settings.save("smtp",mapper.writeValueAsString(input.data()),actor,input.version(),input.changeReason(),input.sourceVersion());
        if(!action.equals("KEEP")){
            byte[] password=action.equals("REPLACE")?input.credentials().value().getBytes(StandardCharsets.UTF_8):null;
            try{secrets.save(context(saved.id()),state.version(),password);}
            finally{if(password!=null)Arrays.fill(password,(byte)0);}
            audit.append(actor,action.equals("REPLACE")?"setting.smtpPasswordUpdated":"setting.smtpPasswordCleared",saved.id());
        }
        return new View(saved.version(),input.data(),willHavePassword,keys.configured());
    }
}
