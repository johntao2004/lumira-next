package com.lumira.platform.content;

import java.util.List;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.HtmlSanitizer;
import org.owasp.html.PolicyFactory;

/** Shared limited HTML policy. No CSS, scripts, forms, frames or embedded data URLs. */
public final class RichText {
 private RichText(){}
 private static final PolicyFactory POLICY=new HtmlPolicyBuilder()
  .allowElements("p","br","div","span","strong","b","em","i","u","s","ul","ol","li","blockquote","pre","code","h1","h2","h3","h4","h5","h6","a","img")
  .allowAttributes("href","title").onElements("a")
  .allowAttributes("src","alt","title","loading","width","height").onElements("img")
  .allowStandardUrlProtocols().requireRelNofollowOnLinks().toFactory();
 public record Clean(String html,boolean meaningful){}
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
