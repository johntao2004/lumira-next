package com.lumira.platform.settings;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public record WatermarkSettings(Boolean enabled,String mode,List<String> textLines,List<String> personalizedTextLines,String imageUrl,
 String fontColor,Integer fontSize,String fontWeight,Integer rotate,Integer gapX,Integer gapY,Integer offsetX,Integer offsetY,Integer zIndex,Double opacity){
 private static final Pattern TOKEN=Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)\\}\\}");
 private static final Set<String> VARIABLES=Set.of("username","nickname","realName","userId");
 public static WatermarkSettings defaults(){return new WatermarkSettings(false,"TEXT",List.of("Lumira"),List.of("{{username}}"),"","#808080",14,"normal",-22,100,100,50,50,9,0.15);}
 public void validate(){
  if(enabled==null||mode==null||!Set.of("TEXT","PERSONALIZED_TEXT","IMAGE").contains(mode)||!SettingUrls.valid(imageUrl)||enabled&&mode.equals("IMAGE")&&imageUrl.isBlank())throw invalid();
  lines(textLines);lines(personalizedTextLines);
  if(fontColor==null||!fontColor.matches("#[0-9a-fA-F]{6}")||fontWeight==null||!fontWeight.matches("normal|bold|[1-9]00"))throw invalid();
  range(fontSize,8,72);range(rotate,-180,180);range(gapX,0,500);range(gapY,0,500);range(offsetX,0,500);range(offsetY,0,500);range(zIndex,0,9999);
  if(opacity==null||!Double.isFinite(opacity)||opacity<0||opacity>1)throw invalid();
  for(String line:personalizedTextLines){var matcher=TOKEN.matcher(line);int end=0;while(matcher.find()){
   if(braces(line.substring(end,matcher.start()))||!VARIABLES.contains(matcher.group(1)))throw invalid();end=matcher.end();
  }if(braces(line.substring(end)))throw invalid();}
 }
 public List<String> content(Map<String,String> user){
  if(!"PERSONALIZED_TEXT".equals(mode)||user==null)return textLines;
  var result=new ArrayList<String>();for(String line:personalizedTextLines){var matcher=TOKEN.matcher(line);StringBuilder output=new StringBuilder();while(matcher.find())matcher.appendReplacement(output,java.util.regex.Matcher.quoteReplacement(user.getOrDefault(matcher.group(1),"")));matcher.appendTail(output);if(!output.toString().isBlank())result.add(output.toString());}
  return result.isEmpty()?textLines:List.copyOf(result);
 }
 private static boolean braces(String value){return value.contains("{")||value.contains("}");}
 private static void lines(List<String> lines){if(lines==null||lines.size()>6||lines.stream().anyMatch(s->s==null||s.length()>200))throw invalid();}
 private static void range(Integer value,int min,int max){if(value==null||value<min||value>max)throw invalid();}
 private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidWatermark");}
}
