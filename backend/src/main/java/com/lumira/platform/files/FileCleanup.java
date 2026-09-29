package com.lumira.platform.files;

import com.lumira.platform.common.AuditPort;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fences expired uploads, then retires terminal deletions without holding locks during storage IO. */
@Component
public class FileCleanup {
 private static final Logger log=LoggerFactory.getLogger(FileCleanup.class);
 private final FileRepository files;private final BlobStore blobs;private final AuditPort audit;private final TransactionTemplate transaction;private final FileRetentionPolicy retention;
 public FileCleanup(FileRepository files,BlobStore blobs,AuditPort audit,PlatformTransactionManager manager){
  this(files,blobs,audit,manager,null);
 }
 @org.springframework.beans.factory.annotation.Autowired
 public FileCleanup(FileRepository files,BlobStore blobs,AuditPort audit,PlatformTransactionManager manager,FileRetentionPolicy retention){
  this.files=files;this.blobs=blobs;this.audit=audit;this.transaction=new TransactionTemplate(manager);this.retention=retention;
 }
 @Scheduled(fixedDelay=60000,initialDelay=60000)
 public void sweep(){
  transaction.executeWithoutResult(status->{for(var id:files.expireUploads())audit.append("system:file-cleanup","file.uploadExpired",id);});
  transaction.executeWithoutResult(status->{for(var id:files.expireOrphanedFiles())audit.append("system:file-cleanup","file.orphanExpired",id);});
  if(retention!=null){
   var policy=retention.current();
   if(policy.mode()==FileRetentionPolicy.Mode.AUTOMATIC){
    transaction.executeWithoutResult(status->{for(var id:files.expireTrashed(policy.retentionDays()))audit.append("system:file-cleanup","file.retentionExpired",id);});
   }
  }
  for(var entry:files.pendingCleanup()){
   try{
    // UUIDs are never reused; duplicate attempts (including manual deletion) are safe.
    blobs.delete(entry.id());
    transaction.executeWithoutResult(status->{if(files.finishDelete(entry.id(),entry.version()))audit.append("system:file-cleanup","file.cleanup",entry.id());});
   }catch(IOException|RuntimeException failure){
    try{files.deferCleanup(entry.id(),entry.version());}
    catch(RuntimeException retryFailure){log.warn("Could not defer file cleanup {} ({})",entry.id(),retryFailure.getClass().getSimpleName());}
    log.warn("File cleanup will retry {} ({})",entry.id(),failure.getClass().getSimpleName());
   }
  }
 }
}
