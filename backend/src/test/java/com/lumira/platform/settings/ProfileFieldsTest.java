package com.lumira.platform.settings;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProfileFieldsTest {
 private ProfileFields.Field field(String key,String type,List<String> options){return new ProfileFields.Field(key,"Custom","",type,true,false,5,"","custom","Custom",100,true,options);}
 private ProfileFields schema(ProfileFields.Field... extra){var fields=new ArrayList<>(ProfileFields.defaults().fields());fields.addAll(Arrays.asList(extra));return new ProfileFields(fields);}
 @Test void dictionaryChoicesAreExclusive(){
  var base=field("choice","SELECT",List.of());
  var bound=new ProfileFields.Field(base.key(),base.label(),base.description(),base.type(),base.visible(),base.required(),base.weight(),base.placeholder(),base.groupKey(),base.groupLabel(),base.sortNo(),base.custom(),base.options(),"dictionary_code");
  assertDoesNotThrow(()->schema(bound).validate());
  var invalid=new ProfileFields.Field(base.key(),base.label(),base.description(),"TEXT",base.visible(),base.required(),base.weight(),base.placeholder(),base.groupKey(),base.groupLabel(),base.sortNo(),base.custom(),List.of(),"dictionary_code");
   assertThrows(ProfileFields.Invalid.class,()->schema(invalid).validate());
 }
 @Test void defaultsAndEveryCustomType(){assertDoesNotThrow(()->ProfileFields.defaults().validate());for(String type:List.of("TEXT","TEXTAREA","NUMBER","DATE","SELECT"))assertDoesNotThrow(()->schema(field("customField",type,type.equals("SELECT")?List.of("One","Two"):List.of())).validate());}
 @Test void protectsBuiltInsAndReservedKeys(){
  assertThrows(ProfileFields.Invalid.class,()->new ProfileFields(List.of()).validate());
  for(String key:List.of("password","authorities","constructor","displayName","invalid-key"))assertThrows(ProfileFields.Invalid.class,()->schema(field(key,"TEXT",List.of())).validate());
  var fields=new ArrayList<>(ProfileFields.defaults().fields());fields.remove(0);fields.add(new ProfileFields.Field("displayName","Name","","TEXT",false,false,5,"","basic","Basic information",10,false,List.of()));assertThrows(ProfileFields.Invalid.class,()->new ProfileFields(fields).validate());
 }
 @Test void rejectsAmbiguousOptionsAndDuplicateKeys(){
  for(List<String> options:List.of(List.<String>of(),List.of("A","A"),List.of(" A")))assertThrows(ProfileFields.Invalid.class,()->schema(field("choice","SELECT",options)).validate());
  assertThrows(ProfileFields.Invalid.class,()->schema(field("text","TEXT",List.of("Option"))).validate());
  assertThrows(ProfileFields.Invalid.class,()->schema(field("same","TEXT",List.of()),field("same","DATE",List.of())).validate());
 }
 @Test void boundsSchemaAndRejectsNullFields(){var fields=new ArrayList<>(ProfileFields.defaults().fields());fields.add(null);assertThrows(ProfileFields.Invalid.class,()->new ProfileFields(fields).validate());assertThrows(ProfileFields.Invalid.class,()->new ProfileFields(Collections.nCopies(101,field("many","TEXT",List.of()))).validate());}
}
