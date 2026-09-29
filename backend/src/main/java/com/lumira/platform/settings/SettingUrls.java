package com.lumira.platform.settings;
import java.net.URI;
/** Public image/link values. Validation does not fetch remote resources. */
final class SettingUrls {
 static boolean valid(String value){
  if(value==null||value.length()>2000)return false;if(value.isEmpty())return true;
  if(value.contains("\\")||value.chars().anyMatch(Character::isISOControl))return false;
  try{var uri=URI.create(value);if(value.startsWith("/")&&!value.startsWith("//")&&uri.getRawAuthority()==null)return true;
   return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null&&uri.getUserInfo()==null;
  }catch(IllegalArgumentException ex){return false;}
 }
}
