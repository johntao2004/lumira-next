package com.lumira.platform.settings;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class WatermarkSettingsTest {
 private WatermarkSettings template(String template){return new WatermarkSettings(true,"PERSONALIZED_TEXT",List.of("Visitor"),List.of(template),"","#808080",14,"normal",-22,100,100,50,50,9,0.15);}
 @Test void validatesTemplatesAndSubstitutesLiteralUserValues(){
  WatermarkSettings.defaults().validate();var setting=template("{{username}} / {{realName}}");setting.validate();
  assertEquals(List.of("A$1\\B / Person"),setting.content(Map.of("username","A$1\\B","realName","Person")));
  assertThrows(ResponseStatusException.class,()->template("{{email}}").validate());
  assertThrows(ResponseStatusException.class,()->template("{{mobile}}").validate());
  assertEquals(List.of("Visitor"),setting.content(null));
 }
 @Test void fixedModeUsesOnlyFixedTextEvenWhenAPersonalizedTemplateIsPresent(){
  var setting=new WatermarkSettings(true,"TEXT",List.of("Fixed"),List.of("{{username}}"),"","#808080",14,"normal",0,100,100,0,0,9,0.1);
  setting.validate();
  assertEquals(List.of("Fixed"),setting.content(Map.of("username","Ada")));
 }
 @Test void rejectsUnknownMalformedAndOversizedTemplates(){
  for(String value:List.of("{{password}}","{{ username }}","{{username}","{value}","x".repeat(201)))assertThrows(ResponseStatusException.class,()->template(value).validate());
 }
 @Test void rejectsInvalidImageAndNumericValues(){
  assertThrows(ResponseStatusException.class,()->new WatermarkSettings(true,"IMAGE",List.of(),List.of(),"javascript:alert(1)","#808080",14,"normal",0,100,100,0,0,9,0.1).validate());
  assertThrows(ResponseStatusException.class,()->new WatermarkSettings(true,"TEXT",List.of(),List.of(),"","#808080",14,"normal",0,100,100,0,0,9,Double.NaN).validate());
 }
}
