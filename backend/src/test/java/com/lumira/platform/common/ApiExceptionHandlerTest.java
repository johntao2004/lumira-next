package com.lumira.platform.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ApiExceptionHandlerTest {
 @Test void infrastructureErrorsDoNotExposeDetails(){
  var handler=new ApiExceptionHandler();
  for(Exception error:java.util.List.of(new java.io.IOException("private path"),new org.springframework.dao.DataAccessResourceFailureException("private SQL"),new org.springframework.transaction.TransactionSystemException("private database"))){
   var problem=handler.infrastructure(error);assertEquals(500,problem.getStatus());assertEquals("unavailable",problem.getDetail());
  }
 }
}
