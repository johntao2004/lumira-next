package com.lumira.platform.settings;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class SmtpSettingsTest {
 @Test void defaultsAndSecureAuthenticatedConfigurationsAreValid(){
  assertDoesNotThrow(()->SmtpSettings.defaults().validate());
  for(String mode:new String[]{"STARTTLS","TLS"})assertDoesNotThrow(()->new SmtpSettings(true,"smtp.example.test",587,mode,true,"sender","sender@example.test","Platform").validate());
 }
 @Test void rejectsHeaderInjectionInvalidDestinationsAndInsecureModes(){
  for(String host:new String[]{"smtp.example.test\r\nX: injected","https://example.test","a..test","-host.test"})assertThrows(ResponseStatusException.class,()->new SmtpSettings(true,host,587,"TLS",true,"user","from@example.test","").validate());
  for(String from:new String[]{"a@b@test",".a@example.test","a..b@example.test","a@example.test\nBcc: x@test"})assertThrows(ResponseStatusException.class,()->new SmtpSettings(true,"smtp.test",587,"TLS",true,"user",from,"").validate());
  assertThrows(ResponseStatusException.class,()->new SmtpSettings(true,"smtp.test",25,"NONE",false,"","a@test","").validate());
  assertThrows(ResponseStatusException.class,()->new SmtpSettings(true,"",587,"TLS",true,"","","").validate());
 }
 @Test void credentialActionsAreExplicitAndNeverSerializedBack(){
  for(String action:new String[]{"KEEP","CLEAR"}){
   assertDoesNotThrow(()->new SmtpSettingsService.Credentials(action,null).validate());
   assertThrows(ResponseStatusException.class,()->new SmtpSettingsService.Credentials(action,"******").validate());
  }
  assertThrows(ResponseStatusException.class,()->new SmtpSettingsService.Credentials("REPLACE","").validate());
  var credential=new SmtpSettingsService.Credentials("REPLACE","test-fixture-value");credential.validate();
  assertFalse(credential.toString().contains("test-fixture-value"));
  String json=new ObjectMapper().writeValueAsString(credential);assertFalse(json.contains("test-fixture-value"));
  assertEquals("test-fixture-value",new ObjectMapper().readValue("{\"action\":\"REPLACE\",\"value\":\"test-fixture-value\"}",SmtpSettingsService.Credentials.class).value());
 }
}
