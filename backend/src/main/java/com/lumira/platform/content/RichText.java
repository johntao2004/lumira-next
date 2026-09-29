package com.lumira.platform.content;

import java.util.List;
import java.util.Set;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.PolicyFactory;

/** Shared limited HTML policy. No CSS, scripts, forms, frames or embedded data URLs. */
public final class RichText {
 private RichText(){}
 private static final PolicyFactory POLICY=new HtmlPolicyBuilder()
  .allowElements("p","br","div","span","strong","b","em","i","u","s","ul","ol","li","blockquote","pre","code","h1","h2","h3","h4","h5","h6","a","img")
  .allowAttributes("href","title").onElements("a")
  // Images may only point at this application's own API; remote images would leak the reader's address.
  .allowAttributes("src").matching(java.util.regex.Pattern.compile("/api/[A-Za-z0-9/_-]{1,200}")).onElements("img")
  .allowAttributes("alt","title","loading","width","height").onElements("img")
  .allowStandardUrlProtocols().requireRelNofollowOnLinks().toFactory();
 public record Clean(String html,boolean meaningful){}
 private static final Set<String> INLINE=Set.of("span","strong","b","em","i","u","s","code","a");
 /**
  * Text a reader actually sees, for moderation. Entities arrive decoded; inline formatting
  * (<b>, <i>, <span> ...) does not split a word, while block-level boundaries do.
  */
 public static String visibleText(String html){
  var text=new StringBuilder();
  HtmlSanitizer.sanitize(html==null?"":html,new HtmlSanitizer.Policy(){
   public void openDocument(){}
   public void closeDocument(){}
   public void openTag(String name,List<String> attributes){if(!INLINE.contains(name))text.append(' ');}
   public void closeTag(String name){if(!INLINE.contains(name))text.append(' ');}
   public void text(String value){text.append(value);}
  });
  return text.toString();
 }
 public static Clean clean(String source){
  String html=POLICY.sanitize(source==null?"":source);
  var content=new Content();HtmlSanitizer.sanitize(html,content);
  return new Clean(html,content.meaningful);
 }
 /** Parser callbacks see decoded entities; regex/tag stripping would mistake blank entities for text. */
 private static final class Content implements HtmlSanitizer.Policy {
  boolean meaningful;
  public void openDocument(){}
  public void closeDocument(){}
  public void closeTag(String name){}
  public void openTag(String name,List<String> attributes){
   if(name.equals("img"))for(int i=0;i+1<attributes.size();i+=2)if(attributes.get(i).equals("src")&&!attributes.get(i+1).isBlank())meaningful=true;
  }
  public void text(String value){
   if(value.codePoints().anyMatch(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&Character.getType(c)!=Character.FORMAT&&Character.getType(c)!=Character.CONTROL))meaningful=true;
  }
 }
}
