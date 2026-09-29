package com.lumira.platform.identity;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Replace visible custom values while preserving hidden/retired values for later schema restoration. */
public final class ProfileValues {
 private ProfileValues(){}
 private static void invalid(){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidProfileValues");}
 public static Map<String,String> merge(ProfileFields schema,Map<String,String> old,Map<String,String> input){
  if(input==null||input.size()>100){invalid();return Map.of();}
  var editable=new LinkedHashMap<String,ProfileFields.Field>();
  for(var field:schema.fields())if(field.custom()&&field.visible())editable.put(field.key(),field);
  if(!editable.keySet().containsAll(input.keySet()))invalid();
  var result=new LinkedHashMap<>(old);
  for(var entry:editable.entrySet()){
   var field=entry.getValue();String raw=input.getOrDefault(entry.getKey(),"");if(raw==null){invalid();return Map.of();}
   String value=raw.strip();if(value.length()>(field.type().equals("TEXTAREA")?2000:200))invalid();
   if(value.isEmpty()){if(field.required())invalid();result.remove(entry.getKey());continue;}
   try{
    switch(field.type()){
     case "NUMBER" -> {if(!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))invalid();var number=new BigDecimal(value);if(number.precision()>30||number.scale()>10)invalid();}
     case "DATE" -> {if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))invalid();LocalDate.parse(value);}
     case "SELECT" -> {if(field.usesDictionary()){UUID.fromString(value);}else if(!field.options().contains(value))invalid();}
     default -> {}
    }
   }catch(IllegalArgumentException|java.time.DateTimeException e){invalid();}
   result.put(entry.getKey(),value);
  }
  return Map.copyOf(result);
 }
 public static Map<String,String> visible(ProfileFields schema,Map<String,String> stored){var result=new LinkedHashMap<String,String>();for(var f:schema.fields())if(f.custom()&&f.visible()&&stored.containsKey(f.key()))result.put(f.key(),stored.get(f.key()));return Map.copyOf(result);}
}
