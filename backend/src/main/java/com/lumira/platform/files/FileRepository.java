package com.lumira.platform.files;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.lumira.platform.common.PageRequest;
import com.lumira.platform.common.PageFilterSql;
import com.lumira.platform.common.PageResult;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Internal metadata access. Every user-facing lookup is constrained by its resolved owner ID. */
@Repository
public class FileRepository {
 public record Entry(UUID id,UUID ownerId,String storageBackend,String originalName,String state,
                     Long sizeBytes,String sha256,long version,Instant createdAt,Instant updatedAt,Instant trashedAt){}
 public record ManagedEntry(UUID id,UUID ownerId,String ownerUsername,String ownerDisplayName,String storageBackend,
                            String originalName,String state,Long sizeBytes,String sha256,long version,
                            Instant createdAt,Instant updatedAt,Instant trashedAt){}
 public record QuotaOwner(UUID userId,String username,String displayName,long version){}
 private final JdbcClient jdbc;
 private static final RowMapper<Entry> MAPPER=(rs,n)->new Entry(rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),
   rs.getString("storage_backend"),rs.getString("original_name"),rs.getString("state"),rs.getObject("size_bytes",Long.class),
   rs.getString("sha256"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
   rs.getTimestamp("trashed_at")==null?null:rs.getTimestamp("trashed_at").toInstant());
 private static final RowMapper<ManagedEntry> MANAGED_MAPPER=(rs,n)->new ManagedEntry(rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),
   rs.getString("owner_username"),rs.getString("owner_display_name"),rs.getString("storage_backend"),rs.getString("original_name"),
   rs.getString("state"),rs.getObject("size_bytes",Long.class),rs.getString("sha256"),rs.getLong("version"),
   rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
   rs.getTimestamp("trashed_at")==null?null:rs.getTimestamp("trashed_at").toInstant());
 public FileRepository(JdbcClient jdbc){this.jdbc=jdbc;}
 public record Usage(long chargedBytes,long fileCount){}
 public QuotaOwner quotaOwner(UUID userId,boolean lock){
  return jdbc.sql("SELECT id,username,display_name,version FROM platform_user WHERE id=:id AND deleted_at IS NULL"+(lock?" FOR UPDATE":""))
   .param("id",userId).query((rs,n)->new QuotaOwner(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("display_name"),rs.getLong("version")))
   .optional().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"userNotFound"));
 }
 public Usage usage(UUID owner){
  return jdbc.sql("SELECT coalesce(sum(coalesce(size_bytes,reserved_bytes)),0)::bigint AS bytes,count(*) AS files FROM platform_file WHERE owner_id=:owner")
   .param("owner",owner).query((rs,n)->new Usage(rs.getLong("bytes"),rs.getLong("files"))).single();
 }
 public void reserve(UUID id,UUID ownerId,String backend,String name,long bytes){
  jdbc.sql("INSERT INTO platform_file(id,owner_id,storage_backend,original_name,reserved_bytes) VALUES(:id,:owner,:backend,:name,:bytes)")
   .param("id",id).param("owner",ownerId).param("backend",backend).param("name",name).param("bytes",bytes).update();
 }
 public Optional<String> storageBackend(UUID id){
  return jdbc.sql("SELECT storage_backend FROM platform_file WHERE id=:id").param("id",id).query(String.class).optional();
 }
 public java.util.List<Entry> readyLocalFiles(int limit){
  return jdbc.sql("SELECT * FROM platform_file WHERE storage_backend='local' AND state='READY' ORDER BY id LIMIT :limit")
   .param("limit",limit).query(MAPPER).list();
 }
 public java.util.List<Entry> pendingLegacyLocalCleanup(int limit){
  return jdbc.sql("SELECT * FROM platform_file WHERE storage_backend='postgres' AND legacy_local_cleanup_pending=true ORDER BY id LIMIT :limit")
   .param("limit",limit).query(MAPPER).list();
 }
 public boolean legacyLocalCleanupPending(UUID id){
  return jdbc.sql("SELECT legacy_local_cleanup_pending FROM platform_file WHERE id=:id AND storage_backend='postgres'")
   .param("id",id).query(Boolean.class).optional().orElse(false);
 }
 public boolean lockBlobWrite(UUID id,String backend,String state,long version,boolean requireUnexpiredUpload){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND storage_backend=:backend AND state=:state AND version=:version FOR UPDATE")
      .param("id",id).param("backend",backend).param("state",state).param("version",version).query(UUID.class).optional().isEmpty())return false;
  return !requireUnexpiredUpload||jdbc.sql("SELECT upload_expires_at>clock_timestamp() FROM platform_file WHERE id=:id")
      .param("id",id).query(Boolean.class).single();
 }
 public boolean markLegacyMigrated(UUID id,long version,long size,String digest){
  return jdbc.sql("UPDATE platform_file SET storage_backend='postgres',legacy_local_cleanup_pending=true WHERE id=:id AND storage_backend='local' AND state='READY' AND version=:version AND size_bytes=:size AND sha256=:digest")
   .param("id",id).param("version",version).param("size",size).param("digest",digest).update()==1;
 }
 public void finishLegacyLocalCleanup(UUID id){
  jdbc.sql("UPDATE platform_file SET legacy_local_cleanup_pending=false WHERE id=:id AND storage_backend='postgres'")
   .param("id",id).update();
 }
 public Optional<Entry> findOwned(UUID id,UUID ownerId){
  return jdbc.sql("SELECT * FROM platform_file WHERE id=:id AND owner_id=:owner")
   .param("id",id).param("owner",ownerId).query(MAPPER).optional();
 }
 /** Resolve an image that is explicitly referenced by an owner's feedback. */
 public Optional<Entry> findFeedbackReference(UUID feedbackId,UUID fileId,UUID ownerId){
  return jdbc.sql("SELECT f.* FROM platform_file f JOIN platform_feedback_file r ON r.file_id=f.id WHERE r.feedback_id=:feedback AND r.file_id=:file AND f.owner_id=:owner AND f.state='READY' AND f.media_type='image/png'")
   .param("feedback",feedbackId).param("file",fileId).param("owner",ownerId).query(MAPPER).optional();
 }
 /** Management reads are still constrained to a feedback reference and a ready normalized image. */
 public Optional<Entry> findManagedFeedbackReference(UUID feedbackId,UUID fileId){
  return jdbc.sql("SELECT f.* FROM platform_file f JOIN platform_feedback_file r ON r.file_id=f.id WHERE r.feedback_id=:feedback AND r.file_id=:file AND f.state='READY' AND f.media_type='image/png'")
   .param("feedback",feedbackId).param("file",fileId).query(MAPPER).optional();
 }
 /** Ready normalized images can be used by the generic inline-content adapters. */
 public Optional<Entry> findReadyImage(UUID id,UUID ownerId){
  return jdbc.sql("SELECT * FROM platform_file WHERE id=:id AND owner_id=:owner AND state='READY' AND media_type='image/png'")
   .param("id",id).param("owner",ownerId).query(MAPPER).optional();
 }
 /** A management read still requires at least one durable feedback reference. */
 public Optional<Entry> findManagedFeedbackImage(UUID id){
  return jdbc.sql("SELECT f.* FROM platform_file f WHERE f.id=:id AND f.state='READY' AND f.media_type='image/png' AND EXISTS(SELECT 1 FROM platform_feedback_file r WHERE r.file_id=f.id)")
   .param("id",id).query(MAPPER).optional();
 }
 public PageResult<Entry> listOwned(UUID ownerId,PageRequest page){
  String where=" WHERE owner_id=:owner AND state<>'TRASHED' AND (:search='' OR strpos(lower(original_name),lower(:search))>0)";
  var filter=PageFilterSql.build(page,java.util.Map.of("originalName","original_name","sizeBytes","size_bytes","state","state","createdAt","created_at"));
  var countParams=new java.util.HashMap<String,Object>();countParams.put("owner",ownerId);countParams.put("search",page.search());countParams.putAll(filter.parameters());
  long total=jdbc.sql("SELECT count(*) FROM platform_file"+where+filter.sql()).params(countParams).query(Long.class).single();
  var listParams=new java.util.HashMap<>(countParams);listParams.put("limit",page.pageSize());listParams.put("offset",page.offset());
  var items=jdbc.sql("SELECT * FROM platform_file"+where+filter.sql()+" ORDER BY created_at DESC,id DESC LIMIT :limit OFFSET :offset")
   .params(listParams).query(MAPPER).list();
  return new PageResult<>(items,total,page.page(),page.pageSize());
 }
 /** Global file-center reads include an explicit owner projection and never expose file payloads. */
 public PageResult<ManagedEntry> listManaged(PageRequest page){
  String where=" WHERE f.state<>'TRASHED' AND (:search='' OR strpos(lower(f.original_name),lower(:search))>0 OR strpos(lower(coalesce(u.username,'')),lower(:search))>0 OR strpos(lower(coalesce(u.display_name,'')),lower(:search))>0)";
  var filter=PageFilterSql.build(page,java.util.Map.of("originalName","f.original_name","ownerUsername","CASE WHEN u.username IS NULL THEN '—' ELSE coalesce(u.display_name,u.username) || ' (' || u.username || ')' END","sizeBytes","f.size_bytes","state","f.state","createdAt","f.created_at"));
  var countParams=new java.util.HashMap<String,Object>();countParams.put("search",page.search());countParams.putAll(filter.parameters());
  long total=jdbc.sql("SELECT count(*) FROM platform_file f LEFT JOIN platform_user u ON u.id=f.owner_id"+where+filter.sql()).params(countParams).query(Long.class).single();
  var listParams=new java.util.HashMap<>(countParams);listParams.put("limit",page.pageSize());listParams.put("offset",page.offset());
  var items=jdbc.sql("SELECT f.*,u.username AS owner_username,u.display_name AS owner_display_name FROM platform_file f LEFT JOIN platform_user u ON u.id=f.owner_id"+where+filter.sql()+" ORDER BY f.created_at DESC,f.id DESC LIMIT :limit OFFSET :offset")
   .params(listParams).query(MANAGED_MAPPER).list();
  return new PageResult<>(items,total,page.page(),page.pageSize());
 }
 public PageResult<Entry> listTrashOwned(UUID ownerId,PageRequest page){
  String where=" WHERE owner_id=:owner AND state='TRASHED' AND (:search='' OR strpos(lower(original_name),lower(:search))>0)";
  var filter=PageFilterSql.build(page,java.util.Map.of("originalName","original_name","sizeBytes","size_bytes","trashedAt","trashed_at"));
  var countParams=new java.util.HashMap<String,Object>();countParams.put("owner",ownerId);countParams.put("search",page.search());countParams.putAll(filter.parameters());
  long total=jdbc.sql("SELECT count(*) FROM platform_file"+where+filter.sql()).params(countParams).query(Long.class).single();
  var listParams=new java.util.HashMap<>(countParams);listParams.put("limit",page.pageSize());listParams.put("offset",page.offset());
  var items=jdbc.sql("SELECT * FROM platform_file"+where+filter.sql()+" ORDER BY trashed_at DESC,id DESC LIMIT :limit OFFSET :offset")
   .params(listParams).query(MAPPER).list();
  return new PageResult<>(items,total,page.page(),page.pageSize());
 }
 public PageResult<ManagedEntry> listManagedTrash(PageRequest page){
  String where=" WHERE f.state='TRASHED' AND (:search='' OR strpos(lower(f.original_name),lower(:search))>0 OR strpos(lower(coalesce(u.username,'')),lower(:search))>0 OR strpos(lower(coalesce(u.display_name,'')),lower(:search))>0)";
  var filter=PageFilterSql.build(page,java.util.Map.of("originalName","f.original_name","ownerUsername","CASE WHEN u.username IS NULL THEN '—' ELSE coalesce(u.display_name,u.username) || ' (' || u.username || ')' END","sizeBytes","f.size_bytes","trashedAt","f.trashed_at"));
  var countParams=new java.util.HashMap<String,Object>();countParams.put("search",page.search());countParams.putAll(filter.parameters());
  long total=jdbc.sql("SELECT count(*) FROM platform_file f LEFT JOIN platform_user u ON u.id=f.owner_id"+where+filter.sql()).params(countParams).query(Long.class).single();
  var listParams=new java.util.HashMap<>(countParams);listParams.put("limit",page.pageSize());listParams.put("offset",page.offset());
  var items=jdbc.sql("SELECT f.*,u.username AS owner_username,u.display_name AS owner_display_name FROM platform_file f LEFT JOIN platform_user u ON u.id=f.owner_id"+where+filter.sql()+" ORDER BY f.trashed_at DESC,f.id DESC LIMIT :limit OFFSET :offset")
   .params(listParams).query(MANAGED_MAPPER).list();
  return new PageResult<>(items,total,page.page(),page.pageSize());
 }
 public Optional<ManagedEntry> findManaged(UUID id){
  return jdbc.sql("SELECT f.*,u.username AS owner_username,u.display_name AS owner_display_name FROM platform_file f LEFT JOIN platform_user u ON u.id=f.owner_id WHERE f.id=:id")
   .param("id",id).query(MANAGED_MAPPER).optional();
 }
 /** Management deletion keeps the same reference protections as an owner deletion. */
 public boolean requestManagedDelete(UUID id,long version){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='DELETING',version=version+1,updated_at=now() WHERE id=:id AND version=:version AND state='READY' AND NOT EXISTS(SELECT 1 FROM platform_account_media WHERE avatar_file_id=:id) AND NOT EXISTS(SELECT 1 FROM platform_feedback_file WHERE file_id=:id)")
   .param("id",id).param("version",version).update()==1;
 }
 public boolean requestManagedTrash(UUID id,long version){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='TRASHED',trashed_at=clock_timestamp(),version=version+1,updated_at=now() WHERE id=:id AND version=:version AND state='READY' AND NOT EXISTS(SELECT 1 FROM platform_account_media WHERE avatar_file_id=:id) AND NOT EXISTS(SELECT 1 FROM platform_feedback_file WHERE file_id=:id)")
   .param("id",id).param("version",version).update()==1;
 }
 /** Only the reserved upload version may publish metadata. */
 public boolean lockUpload(UUID id,UUID owner){
  boolean locked=jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND owner_id=:owner AND state='UPLOADING' AND version=0 FOR UPDATE")
   .param("id",id).param("owner",owner).query(UUID.class).optional().isPresent();
  // Check wall time after obtaining the lock, including time spent waiting for it.
  return locked&&jdbc.sql("SELECT upload_expires_at>clock_timestamp() FROM platform_file WHERE id=:id")
   .param("id",id).query(Boolean.class).single();
 }
 public boolean complete(UUID id,UUID ownerId,long version,long size,String digest){
  return jdbc.sql("UPDATE platform_file SET state='READY',size_bytes=:size,sha256=:digest,version=version+1,updated_at=now() WHERE id=:id AND owner_id=:owner AND version=:version AND state='UPLOADING'")
   .param("id",id).param("owner",ownerId).param("version",version).param("size",size).param("digest",digest).update()==1;
 }
 public boolean requestDelete(UUID id,UUID ownerId,long version){
  // Lock first; the following statement sees references committed while waiting.
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND owner_id=:owner FOR UPDATE").param("id",id).param("owner",ownerId).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='DELETING',version=version+1,updated_at=now() WHERE id=:id AND owner_id=:owner AND version=:version AND state='READY' AND NOT EXISTS(SELECT 1 FROM platform_account_media WHERE avatar_file_id=:id) AND NOT EXISTS(SELECT 1 FROM platform_feedback_file WHERE file_id=:id)")
   .param("id",id).param("owner",ownerId).param("version",version).update()==1;
 }
 public boolean requestTrash(UUID id,UUID ownerId,long version){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND owner_id=:owner FOR UPDATE").param("id",id).param("owner",ownerId).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='TRASHED',trashed_at=clock_timestamp(),version=version+1,updated_at=now() WHERE id=:id AND owner_id=:owner AND version=:version AND state='READY' AND NOT EXISTS(SELECT 1 FROM platform_account_media WHERE avatar_file_id=:id) AND NOT EXISTS(SELECT 1 FROM platform_feedback_file WHERE file_id=:id)")
   .param("id",id).param("owner",ownerId).param("version",version).update()==1;
 }
 public boolean restoreOwned(UUID id,UUID ownerId,long version){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND owner_id=:owner FOR UPDATE").param("id",id).param("owner",ownerId).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='READY',trashed_at=NULL,version=version+1,updated_at=now() WHERE id=:id AND owner_id=:owner AND version=:version AND state='TRASHED'")
   .param("id",id).param("owner",ownerId).param("version",version).update()==1;
 }
 public boolean restoreManaged(UUID id,long version){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='READY',trashed_at=NULL,version=version+1,updated_at=now() WHERE id=:id AND version=:version AND state='TRASHED'")
   .param("id",id).param("version",version).update()==1;
 }
 public boolean purgeOwned(UUID id,UUID ownerId,long version){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND owner_id=:owner FOR UPDATE").param("id",id).param("owner",ownerId).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='DELETING',trashed_at=NULL,version=version+1,updated_at=now() WHERE id=:id AND owner_id=:owner AND version=:version AND state='TRASHED'")
   .param("id",id).param("owner",ownerId).param("version",version).update()==1;
 }
 public boolean purgeManaged(UUID id,long version){
  if(jdbc.sql("SELECT id FROM platform_file WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().isEmpty())return false;
  return jdbc.sql("UPDATE platform_file SET state='DELETING',trashed_at=NULL,version=version+1,updated_at=now() WHERE id=:id AND version=:version AND state='TRASHED'")
   .param("id",id).param("version",version).update()==1;
 }
 public java.util.List<UUID> expireTrashed(int retentionDays){
  return jdbc.sql("WITH expired AS (SELECT id FROM platform_file WHERE state='TRASHED' AND storage_backend IN ('local','postgres') AND trashed_at<=clock_timestamp()-(CAST(:days AS integer)*interval '1 day') ORDER BY trashed_at,id LIMIT 100 FOR UPDATE SKIP LOCKED) UPDATE platform_file f SET state='DELETING',trashed_at=NULL,version=version+1,updated_at=now() FROM expired e WHERE f.id=e.id RETURNING f.id")
   .param("days",retentionDays).query(UUID.class).list();
 }
 public void markNormalizedImage(UUID id){
  jdbc.sql("UPDATE platform_file SET media_type='image/png' WHERE id=:id AND state='READY'").param("id",id).update();
 }
 /** Called by the uploading request only after streaming has terminated. */
 public boolean abandonUpload(UUID id){
  return jdbc.sql("UPDATE platform_file SET state='DELETING',version=version+1,updated_at=now() WHERE id=:id AND state='UPLOADING' AND version=0")
   .param("id",id).update()==1;
 }
 /** Called only after the storage adapter confirms the object is absent. */
 public boolean finishDelete(UUID id,long version){
  return jdbc.sql("DELETE FROM platform_file WHERE id=:id AND version=:version AND state='DELETING'")
   .param("id",id).param("version",version).update()==1;
 }
 public java.util.List<Entry> pendingCleanup(){
  return jdbc.sql("SELECT * FROM platform_file WHERE state='DELETING' AND storage_backend IN ('local','postgres') AND updated_at<now()-interval '1 minute' ORDER BY updated_at,id LIMIT 100")
   .query(MAPPER).list();
 }
 public java.util.List<UUID> expireUploads(){
  return jdbc.sql("WITH expired AS (SELECT id FROM platform_file WHERE state='UPLOADING' AND storage_backend IN ('local','postgres') AND upload_expires_at<=clock_timestamp() ORDER BY upload_expires_at,id LIMIT 100 FOR UPDATE SKIP LOCKED) UPDATE platform_file f SET state='DELETING',version=version+1,updated_at=now() FROM expired e WHERE f.id=e.id RETURNING f.id")
   .query(UUID.class).list();
 }
 public java.util.List<UUID> expireOrphanedFiles(){
  return jdbc.sql("WITH expired AS (SELECT pf.id FROM platform_file pf WHERE pf.owner_id IS NULL AND pf.state='READY' AND pf.storage_backend IN ('local','postgres') AND pf.orphaned_at<=clock_timestamp()-interval '7 days' AND NOT EXISTS(SELECT 1 FROM platform_feedback_file r WHERE r.file_id=pf.id) ORDER BY pf.orphaned_at,pf.id LIMIT 100 FOR UPDATE SKIP LOCKED) UPDATE public.platform_file f SET state='DELETING',version=version+1,updated_at=now() FROM expired e WHERE f.id=e.id RETURNING f.id")
   .query(UUID.class).list();
 }
 public void deferCleanup(UUID id,long version){
  jdbc.sql("UPDATE platform_file SET updated_at=now() WHERE id=:id AND version=:version AND state='DELETING'")
   .param("id",id).param("version",version).update();
 }
}
