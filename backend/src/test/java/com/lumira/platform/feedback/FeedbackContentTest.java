package com.lumira.platform.feedback;

import com.lumira.platform.content.RichText;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class FeedbackContentTest {
 @Test void preservesUsefulFormattingAndLinks(){
  var value=FeedbackContent.validate("  Network problem  ","<h2>Report</h2><p><strong>Steps</strong></p><ol><li>Open page</li></ol><a href='https://example.com/help'>Help</a><pre><code>error</code></pre>");
  assertEquals("Network problem",value.title());assertTrue(value.detailHtml().contains("<strong>Steps</strong>"));assertTrue(value.detailHtml().contains("<li>Open page</li>"));assertTrue(value.detailHtml().contains("rel=\"nofollow\""));assertEquals(value.detailHtml(),RichText.clean(value.detailHtml()).html());
 }
 @Test void stripsExecutableContentAndUnsafeUrls(){
  var html=FeedbackContent.validate("Report","<script>alert(1)</script><p style='position:fixed' onclick='alert(1)'>Text</p><iframe src='https://example.com'></iframe><a href='javascript:alert(1)'>link</a><img src='data:image/svg+xml,unsafe' onerror='alert(1)'><svg onload='alert(1)'></svg>").detailHtml();
  for(String forbidden:new String[]{"<script","<iframe","<svg","onclick","onerror","onload","style=","javascript:","data:"})assertFalse(html.contains(forbidden),forbidden);
  assertTrue(html.contains("Text"));
 }
 @Test void rejectsSanitizedEmptyAndDecodedWhitespaceButAllowsValidImage(){
  for(String source:new String[]{"","<p><br></p>","<script>alert(1)</script>","<p>&nbsp;&#160;&#xA0;&#32;&#8203;</p>","<img onerror='alert(1)'>","<img src='javascript:alert(1)'>"})assertThrows(ResponseStatusException.class,()->FeedbackContent.validate("Report",source),source);
  var imageId=java.util.UUID.randomUUID();var image=FeedbackContent.validate("Image","<img src='/api/feedback/images/"+imageId+"' alt='Screenshot'>");
  assertTrue(image.detailHtml().contains("<img"));assertEquals(java.util.Set.of(imageId),FeedbackContent.imageIds(image.detailHtml()));
  assertThrows(ResponseStatusException.class,()->FeedbackContent.validate("External","<img src='https://example.com/image.png'>"));
  assertTrue(FeedbackContent.validate("Text","<p>&amp; &#x1f600;</p>").detailHtml().contains("&amp;"));
 }
 @Test void boundsInputOutputAndReplies(){
  assertThrows(ResponseStatusException.class,()->FeedbackContent.validate(" ","<p>text</p>"));
  assertThrows(ResponseStatusException.class,()->FeedbackContent.validate("x".repeat(161),"<p>text</p>"));
  assertThrows(ResponseStatusException.class,()->FeedbackContent.validate("bad"+(char)0,"text"));
  assertThrows(ResponseStatusException.class,()->FeedbackContent.validate("Report","x".repeat(200001)));
  assertThrows(ResponseStatusException.class,()->FeedbackContent.validate("Report","&".repeat(50000)));
  assertNull(FeedbackContent.reply("  "));assertEquals("done",FeedbackContent.reply(" done "));
  assertThrows(ResponseStatusException.class,()->FeedbackContent.reply("x".repeat(4001)));
 }
}
