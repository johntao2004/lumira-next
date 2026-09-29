package com.lumira.platform.files;
import com.lumira.platform.identity.ProfileRepository;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FileServiceTest {
 private FileRepository.Entry entry(UUID id,UUID owner,String state,long version){return new FileRepository.Entry(id,owner,"postgres","report.pdf",state,12L,"a".repeat(64),version,java.time.Instant.EPOCH,java.time.Instant.EPOCH,state.equals("TRASHED")?java.time.Instant.EPOCH:null);}
 @Test void lostUploadStatePreventsPhysicalPublication()throws Exception{
  var repo=mock(FileRepository.class);when(repo.usage(any())).thenReturn(new FileRepository.Usage(0,0));
  var profile=mock(ProfileRepository.class);var store=mock(BlobStore.class);var stage=mock(BlobStore.Staged.class);var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(profile.find("actor",true)).thenReturn(new ProfileRepository.Profile(UUID.randomUUID(),"actor","Actor",null,null,null,0));
  when(store.stage(any(),any(),anyLong())).thenReturn(stage);when(stage.metadata()).thenReturn(new BlobStore.Stored(UUID.randomUUID(),1,"a".repeat(64)));
  var service=new FileService(repo,profile,store,manager,mock(com.lumira.platform.audit.AuditRepository.class),new FileQuota(Long.MAX_VALUE,1000));
  assertThrows(ResponseStatusException.class,()->service.upload("actor","file",InputStream.nullInputStream()));
  verify(stage,never()).publish();verify(stage).close();verify(repo,never()).complete(any(),any(),anyLong(),anyLong(),anyString());
 }
 @Test void rejectsUnsafeNames(){for(String name:List.of(""," ","..","../a","a/b","a\\b","a\r\nb","a".repeat(256)))assertThrows(ResponseStatusException.class,()->FileService.filename(name));assertEquals("report.pdf",FileService.filename(" report.pdf "));}
 @Test void failedStreamingDeletesBytesBeforeMetadata()throws Exception{
  var repo=mock(FileRepository.class);when(repo.usage(any())).thenReturn(new FileRepository.Usage(0,0));var profile=mock(ProfileRepository.class);var store=mock(BlobStore.class);var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
  when(profile.find("actor",true)).thenReturn(new ProfileRepository.Profile(UUID.randomUUID(),"actor","Actor",null,null,null,0));
  when(store.stage(any(UUID.class),any(),anyLong())).thenThrow(new IOException("failed stream"));when(repo.abandonUpload(any())).thenReturn(true);
  var service=new FileService(repo,profile,store,manager,mock(com.lumira.platform.audit.AuditRepository.class),new FileQuota(Long.MAX_VALUE,1000));
  assertThrows(IOException.class,()->service.upload("actor","file",InputStream.nullInputStream()));
  var order=inOrder(repo,store);order.verify(repo).reserve(any(),any(),eq("postgres"),eq("file"),eq(FileService.MAXIMUM_BYTES));order.verify(store).stage(any(UUID.class),any(),anyLong());order.verify(repo).abandonUpload(any());order.verify(store).delete(any());order.verify(repo).finishDelete(any(),eq(1L));
 }
 @Test void failedPhysicalCleanupRetainsMetadata()throws Exception{
  var repo=mock(FileRepository.class);when(repo.usage(any())).thenReturn(new FileRepository.Usage(0,0));var profile=mock(ProfileRepository.class);var store=mock(BlobStore.class);var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(profile.find("actor",true)).thenReturn(new ProfileRepository.Profile(UUID.randomUUID(),"actor","Actor",null,null,null,0));
  when(store.stage(any(UUID.class),any(),anyLong())).thenThrow(new IOException("stream"));when(repo.abandonUpload(any())).thenReturn(true);doThrow(new IOException("cleanup")).when(store).delete(any());
  var failure=assertThrows(IOException.class,()->new FileService(repo,profile,store,manager,mock(com.lumira.platform.audit.AuditRepository.class),new FileQuota(Long.MAX_VALUE,1000)).upload("actor","file",InputStream.nullInputStream()));
  assertEquals(1,failure.getSuppressed().length);verify(repo,never()).finishDelete(any(),anyLong());
 }
 @Test void doesNotDeleteWhenUploadCannotBeAbandoned()throws Exception{
  var repo=mock(FileRepository.class);when(repo.usage(any())).thenReturn(new FileRepository.Usage(0,0));var profile=mock(ProfileRepository.class);var store=mock(BlobStore.class);var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));when(profile.find("actor",true)).thenReturn(new ProfileRepository.Profile(UUID.randomUUID(),"actor","Actor",null,null,null,0));
  when(store.stage(any(UUID.class),any(),anyLong())).thenThrow(new IOException("uncertain result"));when(repo.abandonUpload(any())).thenReturn(false);
  assertThrows(IOException.class,()->new FileService(repo,profile,store,manager,mock(com.lumira.platform.audit.AuditRepository.class),new FileQuota(Long.MAX_VALUE,1000)).upload("actor","file",InputStream.nullInputStream()));verify(store,never()).delete(any());verify(repo,never()).finishDelete(any(),anyLong());
 }
 @Test void ownerDeleteMovesFileToTrashWithoutDeletingBlob()throws Exception{
  var repo=mock(FileRepository.class);var accounts=mock(com.lumira.platform.identity.api.AccountOwnerPort.class);var blobs=mock(BlobStore.class);var audit=mock(com.lumira.platform.common.AuditPort.class);var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));var owner=UUID.randomUUID();var id=UUID.randomUUID();when(accounts.id("actor",false)).thenReturn(owner);when(repo.findOwned(id,owner)).thenReturn(Optional.of(entry(id,owner,"READY",4)));when(repo.requestTrash(id,owner,4)).thenReturn(true);
  var service=new FileService(repo,accounts,blobs,manager,audit,new FileQuota(Long.MAX_VALUE,1000));service.delete("actor",id,4);
  verify(repo).requestTrash(id,owner,4);verify(blobs,never()).delete(any());verify(audit).append("actor","file.trash",id);
 }
 @Test void restoreAndPurgeRemainOwnerScopedAndVersioned()throws Exception{
  var repo=mock(FileRepository.class);var accounts=mock(com.lumira.platform.identity.api.AccountOwnerPort.class);var blobs=mock(BlobStore.class);var audit=mock(com.lumira.platform.common.AuditPort.class);var manager=mock(PlatformTransactionManager.class);
  when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));var owner=UUID.randomUUID();var id=UUID.randomUUID();when(accounts.id("actor",true)).thenReturn(owner);when(repo.findOwned(id,owner)).thenReturn(Optional.of(entry(id,owner,"TRASHED",5)));when(repo.restoreOwned(id,owner,5)).thenReturn(true);when(repo.purgeOwned(id,owner,5)).thenReturn(true);
  var service=new FileService(repo,accounts,blobs,manager,audit,new FileQuota(Long.MAX_VALUE,1000));service.restore("actor",id,5);service.purge("actor",id,5);
  verify(repo).restoreOwned(id,owner,5);verify(repo).purgeOwned(id,owner,5);verify(repo,times(2)).findOwned(id,owner);verify(blobs,never()).delete(any());
 }
 @Test void previewCannotReadAnotherOwnersFile() throws Exception {
  var repo=mock(FileRepository.class);var accounts=mock(com.lumira.platform.identity.api.AccountOwnerPort.class);var blobs=mock(BlobStore.class);var manager=mock(PlatformTransactionManager.class);
  var owner=UUID.randomUUID();var id=UUID.randomUUID();when(accounts.id("actor",false)).thenReturn(owner);when(repo.findOwned(id,owner)).thenReturn(Optional.empty());
  var service=new FileService(repo,accounts,blobs,manager,mock(com.lumira.platform.common.AuditPort.class),new FileQuota(Long.MAX_VALUE,1000));
  var error=assertThrows(ResponseStatusException.class,()->service.preview("actor",id));
  assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());verify(blobs,never()).open(any());
 }
 @Test void previewRejectsTrashedFileBeforeOpeningBlob() throws Exception {
  var repo=mock(FileRepository.class);var accounts=mock(com.lumira.platform.identity.api.AccountOwnerPort.class);var blobs=mock(BlobStore.class);var manager=mock(PlatformTransactionManager.class);
  var owner=UUID.randomUUID();var id=UUID.randomUUID();when(accounts.id("actor",false)).thenReturn(owner);when(repo.findOwned(id,owner)).thenReturn(Optional.of(entry(id,owner,"TRASHED",4)));
  var service=new FileService(repo,accounts,blobs,manager,mock(com.lumira.platform.common.AuditPort.class),new FileQuota(Long.MAX_VALUE,1000));
  var error=assertThrows(ResponseStatusException.class,()->service.preview("actor",id));
  assertEquals(HttpStatus.CONFLICT,error.getStatusCode());assertEquals("fileNotReady",error.getReason());verify(blobs,never()).open(any());
 }
 @Test void staleTrashVersionCannotRestore(){
  var repo=mock(FileRepository.class);var accounts=mock(com.lumira.platform.identity.api.AccountOwnerPort.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));var owner=UUID.randomUUID();var id=UUID.randomUUID();when(accounts.id("actor",true)).thenReturn(owner);when(repo.findOwned(id,owner)).thenReturn(Optional.of(entry(id,owner,"TRASHED",8)));
  var service=new FileService(repo,accounts,mock(BlobStore.class),manager,mock(com.lumira.platform.common.AuditPort.class),new FileQuota(Long.MAX_VALUE,1000));
  assertThrows(ResponseStatusException.class,()->service.restore("actor",id,7));verify(repo,never()).restoreOwned(any(),any(),anyLong());
 }
 @Test void managedTrashRequiresGlobalFileWrite(){
  var repo=mock(FileRepository.class);var guard=mock(com.lumira.platform.common.AdministrationPort.class);var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));var id=UUID.randomUUID();var owner=UUID.randomUUID();
  when(repo.findManaged(id)).thenReturn(Optional.of(new FileRepository.ManagedEntry(id,owner,"user","User","postgres","report.pdf","READY",12L,"a".repeat(64),4,java.time.Instant.EPOCH,java.time.Instant.EPOCH,null)));when(repo.requestManagedTrash(id,4)).thenReturn(true);
  var service=new FileService(repo,mock(com.lumira.platform.identity.api.AccountOwnerPort.class),mock(BlobStore.class),manager,mock(com.lumira.platform.common.AuditPort.class),new FileQuota(Long.MAX_VALUE,1000),guard);
  service.deleteManaged("admin",id,4);verify(guard).require("admin","platform:files:write");verify(guard).requireGlobal("admin","platform:files:write");verify(repo).requestManagedTrash(id,4);
 }
}
