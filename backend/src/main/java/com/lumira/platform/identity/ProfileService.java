package com.lumira.platform.identity;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@Service
public class ProfileService {
 public record Input(String displayName,String realName,String birthMonth,String region,Long version,Long schemaVersion){}
 public record View(UUID id,String username,String displayName,String realName,String birthMonth,String region,long version,long schemaVersion,List<ProfileFields.Field> fields){}
 private final ProfileRepository repository;private final AuditPort audit;private final ProfileSchemaPort schemas;private final JdbcClient jdbc;
 public ProfileService(ProfileRepository repository,AuditPort audit,ProfileSchemaPort schemas,JdbcClient jdbc){this.repository=repository;this.audit=audit;this.schemas=schemas;this.jdbc=jdbc;}
 private View view(ProfileRepository.Profile p,ProfileSchemaPort.Snapshot s){return new View(p.id(),p.username(),p.displayName(),p.realName(),p.birthMonth(),p.region(),p.version(),s.version(),s.data().fields().stream().filter(f->!f.custom()&&f.visible()&&!f.key().equals("avatarUrl")).sorted(Comparator.comparingInt(ProfileFields.Field::sortNo).thenComparing(ProfileFields.Field::key)).toList());}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public View find(String actor){return view(repository.find(actor,false),schemas.read());}
 private static void invalid(){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidProfile");}
 private String value(ProfileFields schema,String key,String incoming,String previous){
  var field=schema.fields().stream().filter(f->f.key().equals(key)).findFirst().orElseThrow();
  String text=incoming==null?"":incoming.strip();
  if(!field.visible()){if(incoming!=null&&!text.equals(previous))invalid();return previous;}
  if(text.length()>200||(field.required()&&text.isBlank()))invalid();return text;
 }
 @Transactional public View update(String actor,Input input){
  jdbc.sql("SELECT pg_advisory_xact_lock(481026)").query(rs->{rs.next();return 0;});
  var old=repository.find(actor,true);var schema=schemas.read();
  if(input.version()==null||input.version()!=old.version()||(input.schemaVersion()==null?schema.version()!=0:input.schemaVersion()!=schema.version()))throw new ResponseStatusException(HttpStatus.CONFLICT,"staleRecord");
  String display=value(schema.data(),"displayName",input.displayName(),old.displayName()),real=value(schema.data(),"realName",input.realName(),old.realName()),region=value(schema.data(),"region",input.region(),old.region()),birth=value(schema.data(),"birthDate",input.birthMonth(),old.birthMonth());
  if(!birth.isEmpty()){try{if(!birth.matches("\\d{4}-\\d{2}")||YearMonth.parse(birth).isAfter(YearMonth.now()))throw new IllegalArgumentException();}catch(RuntimeException error){invalid();}}
  repository.update(actor,display,real,birth,region);audit.append(actor,"profile.update",old.id());return view(repository.find(actor,false),schema);
 }
}
