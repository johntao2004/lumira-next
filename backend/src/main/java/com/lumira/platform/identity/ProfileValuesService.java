package com.lumira.platform.identity;
import com.lumira.platform.common.AuditPort;
import com.lumira.platform.identity.api.ProfileDictionaryPort;
import com.lumira.platform.identity.api.ProfileFields;
import com.lumira.platform.identity.api.ProfileSchemaPort;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
@Service
public class ProfileValuesService {
 public record Input(Long version,Long schemaVersion,Map<String,String> values){}
 public record View(long version,long schemaVersion,List<ProfileFields.Field> fields,Map<String,String> values){}
 private final ProfileDictionaryPort dictionaries;private final JdbcClient jdbc;private final ProfileRepository profiles;private final ProfileSchemaPort schemas;private final ObjectMapper mapper;private final AuditPort audit;
 public ProfileValuesService(JdbcClient jdbc,ProfileRepository profiles,ProfileSchemaPort schemas,ObjectMapper mapper,AuditPort audit,ProfileDictionaryPort dictionaries){this.dictionaries=dictionaries;this.jdbc=jdbc;this.profiles=profiles;this.schemas=schemas;this.mapper=mapper;this.audit=audit;}
 Map<String,String> storedValues(UUID id){String json=jdbc.sql("SELECT profile_values::text FROM platform_user WHERE id=:id").param("id",id).query(String.class).single();return mapper.readValue(json,mapper.getTypeFactory().constructMapType(LinkedHashMap.class,String.class,String.class));}
 private View view(ProfileRepository.Profile profile,ProfileSchemaPort.Snapshot schema,Map<String,String> values){return new View(profile.version(),schema.version(),schema.data().fields().stream().filter(f->f.custom()&&f.visible()).sorted(Comparator.comparingInt(ProfileFields.Field::sortNo).thenComparing(ProfileFields.Field::key)).toList(),ProfileValues.visible(schema.data(),values));}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public View read(String actor){var profile=profiles.find(actor,false);return view(profile,schemas.read(),storedValues(profile.id()));}
 @Transactional public View write(String actor,Input input){
  jdbc.sql("SELECT pg_advisory_xact_lock(481026)").query(rs->{rs.next();return 0;});
  var profile=profiles.find(actor,true);var schema=schemas.read();
  if(input.version()==null||input.version()!=profile.version()||input.schemaVersion()==null||input.schemaVersion()!=schema.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"staleRecord");
  var merged=ProfileValues.merge(schema.data(),storedValues(profile.id()),input.values());
  var requested=schema.data().fields().stream().filter(f->f.custom()&&f.visible()&&f.usesDictionary()&&merged.containsKey(f.key())).map(f->new ProfileDictionaryPort.Selection(f.dictionaryCode(),UUID.fromString(merged.get(f.key())))).toList();
  if(!dictionaries.enabledSelections(requested).containsAll(requested))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidProfileValues");
  jdbc.sql("UPDATE platform_user SET profile_values=CAST(:values AS jsonb),version=version+1 WHERE id=:id").param("values",mapper.writeValueAsString(merged)).param("id",profile.id()).update();
  var editable=schema.data().fields().stream().filter(f->f.custom()&&f.visible()).toList();
  jdbc.sql("DELETE FROM platform_profile_dictionary_selection WHERE user_id=:user AND field_key IN (SELECT jsonb_array_elements_text(CAST(:keys AS jsonb)))").param("user",profile.id()).param("keys",mapper.writeValueAsString(editable.stream().map(ProfileFields.Field::key).toList())).update();
  var references=editable.stream().filter(f->f.usesDictionary()&&merged.containsKey(f.key())).map(f->Map.of("field_key",f.key(),"item_id",merged.get(f.key()))).toList();
  jdbc.sql("INSERT INTO platform_profile_dictionary_selection(user_id,field_key,item_id) SELECT :user,r.field_key,r.item_id FROM jsonb_to_recordset(CAST(:refs AS jsonb)) AS r(field_key text,item_id uuid)").param("user",profile.id()).param("refs",mapper.writeValueAsString(references)).update();
  audit.append(actor,"profile.values",profile.id());return view(profiles.find(actor,false),schema,merged);
 }
}
