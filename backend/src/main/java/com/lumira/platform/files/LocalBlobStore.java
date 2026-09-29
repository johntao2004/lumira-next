package com.lumira.platform.files;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
/** Local/dev adapter. Root and its parents must be owned exclusively by the application operator. */
public final class LocalBlobStore implements BlobStore {
 private final Path root;
 public LocalBlobStore(Path root)throws IOException{
  Objects.requireNonNull(root);Path absolute=root.toAbsolutePath().normalize();Files.createDirectories(absolute);
  if(!Files.isDirectory(absolute,NOFOLLOW_LINKS))throw new AccessDeniedException(absolute.toString());
  this.root=absolute.toRealPath();
 }
 private Path directory(UUID key,boolean create)throws IOException{
  Objects.requireNonNull(key);Path path=root.resolve(key.toString().substring(0,2));
  if(create)try{Files.createDirectory(path);}catch(FileAlreadyExistsException ignored){}
  if(!Files.exists(path,NOFOLLOW_LINKS))throw new NoSuchFileException(path.toString());
  if(!Files.isDirectory(path,NOFOLLOW_LINKS))throw new AccessDeniedException(path.toString());return path;
 }
 private Path path(UUID key)throws IOException{return directory(key,false).resolve(key+".blob");}
 @Override public Staged stage(UUID key,InputStream source,long maximumBytes)throws IOException{
  Objects.requireNonNull(source);if(maximumBytes<0||maximumBytes==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid upload limit");
  Path directory=directory(key,true),staging=directory.resolve(key+".upload"),destination=directory.resolve(key+".blob");
  boolean created=false;
  try{
   MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
   long size=0;byte[] buffer=new byte[64*1024];
   try(FileChannel channel=FileChannel.open(staging,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW_LINKS)){
    created=true;
    while(true){int count=source.read(buffer,0,(int)Math.min(buffer.length,maximumBytes-size+1));if(count<0)break;if(count==0)continue;
     if(count>maximumBytes-size)throw new BlobStore.TooLarge();
     digest.update(buffer,0,count);ByteBuffer bytes=ByteBuffer.wrap(buffer,0,count);while(bytes.hasRemaining())channel.write(bytes);size+=count;
    }
    channel.force(true);
   }
   var metadata=new Stored(key,size,HexFormat.of().formatHex(digest.digest()));
   return new Staged(){
    private boolean closed,published;
    public Stored metadata(){return metadata;}
    public synchronized void publish()throws IOException{
     if(closed||published)throw new IllegalStateException("Staged upload already finalized");
     // Atomic, no-overwrite publication. The coordinator must hold its metadata fence here.
     Files.createLink(destination,staging);published=true;
    }
    public synchronized void close()throws IOException{if(closed)return;deleteRegular(staging);closed=true;}
   };
  }catch(IOException|RuntimeException|Error failure){
   if(created)try{deleteRegular(staging);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
   throw failure;
  }
 }
 private static void deleteRegular(Path file)throws IOException{
  if(Files.exists(file,NOFOLLOW_LINKS)&&!Files.isRegularFile(file,NOFOLLOW_LINKS))throw new AccessDeniedException(file.toString());
  Files.deleteIfExists(file);
 }
 @Override public InputStream open(UUID key)throws IOException{
  Path file=path(key);if(!Files.isRegularFile(file,NOFOLLOW_LINKS))throw new NoSuchFileException(file.toString());
  return Channels.newInputStream(Files.newByteChannel(file,Set.of(StandardOpenOption.READ,NOFOLLOW_LINKS)));
 }
 @Override public void delete(UUID key)throws IOException{
  Path file;try{file=path(key);}catch(NoSuchFileException absent){return;}
  deleteRegular(file);deleteRegular(file.resolveSibling(key+".upload"));
 }
}
