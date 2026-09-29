package com.lumira.platform.drafts;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageResult;
import org.springframework.transaction.annotation.Isolation;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Owner-scoped reusable storage. Draft content is never copied into audit records. */
@Service
public class AccountDraftService {
 public static final int MAX_PAYLOAD_BYTES=1_000_000,MAX_DRAFTS=50,MAX_TOTAL_BYTES=5_000_000;
 public record Summary(UUID id,String draftKey,UUID revision,int payloadBytes,OffsetDateTime updatedAt){}
 public record Input(UUID revision,JsonNode payload){}
 public record Draft(UUID revision,JsonNode payload,OffsetDateTime updatedAt){}
 private record Stored(UUID id,UUID revision,String payload,OffsetDateTime updatedAt,int payloadBytes){}
 private final JdbcClient jdbc;
 private final ObjectMapper json;
 private final AuditPort audit;
 public AccountDraftService(JdbcClient jdbc,ObjectMapper json,AuditPort audit){this.jdbc=jdbc;this.json=json;this.audit=audit;}
 private UUID owner(String actor,boolean lock){
  return jdbc.sql("SELECT id FROM platform_user WHERE username=:actor AND enabled=true"+(lock?" FOR UPDATE":""))
   .param("actor",actor).query(UUID.class).optional().orElseThrow(()->error(HttpStatus.UNAUTHORIZED,"unauthorized"));
 }
 private String key(String value){
  if(value==null||!value.matches("[a-z0-9][a-z0-9._:-]{0,127}"))throw error(HttpStatus.BAD_REQUEST,"invalidDraft");
  return value;
 }
 private Optional<Stored> stored(UUID owner,String key){
  return jdbc.sql("SELECT id,revision,payload,updated_at,payload_bytes FROM platform_account_draft WHERE owner_id=:owner AND draft_key=:key")
   .param("owner",owner).param("key",key).query(Stored.class).optional();
 }
 private Draft view(Stored row){return new Draft(row.revision(),json.readTree(row.payload()),row.updatedAt());}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public PageResult<Summary> list(String actor,PageRequest page){
  UUID owner=owner(actor,false);
  String filter=" WHERE owner_id=:owner AND (:search='' OR strpos(draft_key,lower(:search))>0)";
  var clause=PageFilterSql.build(page,Map.of("draftKey","draft_key","payloadBytes","payload_bytes","updatedAt","updated_at"));
  var countParams=new HashMap<String,Object>();countParams.put("owner",owner);countParams.put("search",page.search());countParams.putAll(clause.parameters());
  long total=jdbc.sql("SELECT count(*) FROM platform_account_draft"+filter+clause.sql()).params(countParams).query(Long.class).single();
  var listParams=new HashMap<>(countParams);listParams.put("limit",page.pageSize());listParams.put("offset",page.offset());
  var rows=jdbc.sql("SELECT id,draft_key,revision,payload_bytes,updated_at FROM platform_account_draft"+filter+clause.sql()+" ORDER BY updated_at DESC,id DESC LIMIT :limit OFFSET :offset")
   .params(listParams).query(Summary.class).list();
  return new PageResult<>(rows,total,page.page(),page.pageSize());
 }
 @Transactional(readOnly=true)
 public Draft find(String actor,String draftKey){return stored(owner(actor,false),key(draftKey)).map(this::view).orElse(null);}
 @Transactional
 public Draft save(String actor,String draftKey,Input input){
  String key=key(draftKey);
  if(input==null||input.payload()==null||input.payload().isNull())throw error(HttpStatus.BAD_REQUEST,"invalidDraft");
  String payload=json.writeValueAsString(input.payload());int bytes=payload.getBytes(StandardCharsets.UTF_8).length;
  if(bytes>MAX_PAYLOAD_BYTES)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"draftTooLarge");
  UUID owner=owner(actor,true);var old=stored(owner,key).orElse(null);
  if(!Objects.equals(old==null?null:old.revision(),input.revision()))throw error(HttpStatus.CONFLICT,"staleRecord");
  long count=jdbc.sql("SELECT count(*) FROM platform_account_draft WHERE owner_id=:owner").param("owner",owner).query(Long.class).single();
  long total=jdbc.sql("SELECT coalesce(sum(payload_bytes),0) FROM platform_account_draft WHERE owner_id=:owner").param("owner",owner).query(Long.class).single();
  if((old==null&&count>=MAX_DRAFTS)||total-(old==null?0:old.payloadBytes())+bytes>MAX_TOTAL_BYTES)throw error(HttpStatus.CONFLICT,"draftQuotaExceeded");
  UUID id=old==null?UUID.randomUUID():old.id(),revision=UUID.randomUUID();
  jdbc.sql("""
   INSERT INTO platform_account_draft(id,owner_id,draft_key,revision,payload,payload_bytes)
   VALUES(:id,:owner,:key,:revision,:payload,:bytes)
   ON CONFLICT(owner_id,draft_key) DO UPDATE SET revision=excluded.revision,payload=excluded.payload,
    payload_bytes=excluded.payload_bytes,updated_at=clock_timestamp()
   """).param("id",id).param("owner",owner).param("key",key).param("revision",revision).param("payload",payload).param("bytes",bytes).update();
  audit.append(actor,"draft.saved",id);
  return view(stored(owner,key).orElseThrow());
 }
 @Transactional
 public void delete(String actor,String draftKey,UUID revision){
  String key=key(draftKey);UUID owner=owner(actor,true);var old=stored(owner,key).orElse(null);
  if(revision==null)throw error(HttpStatus.BAD_REQUEST,"invalidDraft");
  if(old==null) return;
  if(!revision.equals(old.revision()))throw error(HttpStatus.CONFLICT,"staleRecord");
  jdbc.sql("DELETE FROM platform_account_draft WHERE owner_id=:owner AND draft_key=:key").param("owner",owner).param("key",key).update();
  audit.append(actor,"draft.deleted",old.id());
 }
 private static ResponseStatusException error(HttpStatus status,String code){return new ResponseStatusException(status,code);}
}
