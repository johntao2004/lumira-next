package com.lumira.platform.common;
import java.io.*;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.function.LongSupplier;
/** Absolute elapsed-time guard. The transport must separately bound a blocking read. */
public final class DeadlineInputStream extends FilterInputStream {
 private final long started,limit;private final LongSupplier clock;
 public DeadlineInputStream(InputStream input,Duration duration){this(input,duration,System::nanoTime);}
 DeadlineInputStream(InputStream input,Duration duration,LongSupplier clock){
  super(input);this.limit=duration.toNanos();if(limit<=0)throw new IllegalArgumentException("Positive stream deadline required");
  this.clock=clock;this.started=clock.getAsLong();
 }
 private void check()throws SocketTimeoutException{if(clock.getAsLong()-started>=limit)throw new SocketTimeoutException("Upload duration exceeded");}
 @Override public int read()throws IOException{check();int value=in.read();check();return value;}
 @Override public int read(byte[] bytes,int offset,int length)throws IOException{check();int count=in.read(bytes,offset,length);check();return count;}
 @Override public long skip(long count)throws IOException{check();long skipped=in.skip(count);check();return skipped;}
}
