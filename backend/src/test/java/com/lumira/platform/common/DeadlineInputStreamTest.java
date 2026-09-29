package com.lumira.platform.common;
import java.io.*;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DeadlineInputStreamTest {
 @Test void progressDoesNotExtendAbsoluteDeadline()throws Exception{
  var clock=new AtomicLong();var stream=new DeadlineInputStream(new ByteArrayInputStream(new byte[]{1,2}),Duration.ofNanos(10),clock::get);
  clock.set(9);assertEquals(1,stream.read());clock.set(10);assertThrows(SocketTimeoutException.class,stream::read);
 }
 @Test void checksAgainWhenABlockedReadReturns(){
  var clock=new AtomicLong();var input=new InputStream(){public int read(){clock.set(20);return 1;}};
  var stream=new DeadlineInputStream(input,Duration.ofNanos(10),clock::get);assertThrows(SocketTimeoutException.class,()->stream.read(new byte[1],0,1));
 }
 @Test void rejectsNonPositiveDuration(){assertThrows(IllegalArgumentException.class,()->new DeadlineInputStream(InputStream.nullInputStream(),Duration.ZERO));}
}
