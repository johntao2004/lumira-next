package com.lumira.platform.identity;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;
class ProfileValuesTest {
 private ProfileFields schema(String type,boolean required,boolean visible){return new ProfileFields(List.of(new ProfileFields.Field("custom","Custom","",type,visible,required,5,"","custom","Custom",10,true,List.of("A","B"))));}
 @Test void preservesHiddenAndRetiredValues(){assertEquals(Map.of("custom","old","retired","keep"),ProfileValues.merge(schema("TEXT",false,false),Map.of("custom","old","retired","keep"),Map.of()));assertEquals(Map.of(),ProfileValues.visible(schema("TEXT",false,false),Map.of("custom","old")));assertThrows(ResponseStatusException.class,()->ProfileValues.merge(schema("TEXT",false,false),Map.of(),Map.of("custom","new")));}
 @Test void requiresVisibleValuesAndRejectsUnknownKeys(){assertThrows(ResponseStatusException.class,()->ProfileValues.merge(schema("TEXT",true,true),Map.of("custom","old"),Map.of()));assertThrows(ResponseStatusException.class,()->ProfileValues.merge(schema("TEXT",false,true),Map.of(),Map.of("password","new")));assertEquals(Map.of(),ProfileValues.merge(schema("TEXT",false,true),Map.of("custom","old"),Map.of()));}
 @Test void validatesDatesNumbersAndChoices(){for(var entry:Map.of("DATE","2026-02-30","NUMBER","NaN","SELECT","C").entrySet())assertThrows(ResponseStatusException.class,()->ProfileValues.merge(schema(entry.getKey(),false,true),Map.of(),Map.of("custom",entry.getValue())));for(var entry:Map.of("DATE","2024-02-29","NUMBER","-12.50","SELECT","A").entrySet())assertEquals(entry.getValue(),ProfileValues.merge(schema(entry.getKey(),true,true),Map.of(),Map.of("custom",entry.getValue())).get("custom"));}
 @Test void boundsAndTrimsText(){assertEquals("text",ProfileValues.merge(schema("TEXT",true,true),Map.of(),Map.of("custom"," text ")).get("custom"));assertThrows(ResponseStatusException.class,()->ProfileValues.merge(schema("TEXT",true,true),Map.of(),Map.of("custom","x".repeat(201))));assertEquals(2000,ProfileValues.merge(schema("TEXTAREA",true,true),Map.of(),Map.of("custom","x".repeat(2000))).get("custom").length());}
 @Test void renamingTheDisplayedLabelPreservesValuesByStableKey(){
  String key="profileField_0123456789abcdef0123456789abcdef";
  var before=new ProfileFields(List.of(new ProfileFields.Field(key,"Emergency contact","Who to contact","TEXT",true,false,5,"Name","custom","Additional information",100,true,List.of())));
  var after=new ProfileFields(List.of(new ProfileFields.Field(key,"Contact person","Who to contact","TEXT",true,false,5,"Name","custom","Additional information",100,true,List.of())));
  var stored=ProfileValues.merge(before,Map.of(),Map.of(key,"Alex"));
  assertEquals("Alex",ProfileValues.visible(after,stored).get(key));
  assertEquals("Morgan",ProfileValues.merge(after,stored,Map.of(key,"Morgan")).get(key));
 }
}
