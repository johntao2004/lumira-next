package com.lumira.platform.common;
import org.junit.jupiter.api.Test;
import org.apache.coyote.http11.Http11NioProtocol;
import static org.junit.jupiter.api.Assertions.*;
class UploadTransportConfigurationTest {
 @Test void enablesTheSeparateBoundedUploadReadTimeout(){
  var protocol=new Http11NioProtocol();UploadTransportConfiguration.configure(protocol,30000);
  assertFalse(protocol.getDisableUploadTimeout());assertEquals(30000,protocol.getConnectionUploadTimeout());
 }
 @Test void rejectsInfiniteOrExcessiveConfiguration(){
  var config=new UploadTransportConfiguration();
  for(int value:new int[]{-1,0,999,120001})assertThrows(IllegalArgumentException.class,()->config.uploadReadTimeout(value));
 }
 @Test void socketTimeoutIsDistinctFromDatabaseOrOtherIoFailure(){
  var handler=new ApiExceptionHandler();var timeout=new java.net.SocketTimeoutException("private socket");
  assertEquals(408,handler.infrastructure(new java.io.IOException(timeout)).getStatus());
  assertEquals("uploadTimeout",handler.infrastructure(timeout).getDetail());
  assertEquals(500,handler.infrastructure(new org.springframework.dao.DataAccessResourceFailureException("database",timeout)).getStatus());
 }
}
