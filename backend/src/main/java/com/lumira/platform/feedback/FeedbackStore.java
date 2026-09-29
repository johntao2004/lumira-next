package com.lumira.platform.feedback;

import com.lumira.platform.common.AuditPort;
import com.lumira.platform.common.*;
import com.lumira.platform.notifications.api.NotificationProducerPort;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Internal persistence engine. The future API adapter must enforce grants and module state. */
@Repository
public class FeedbackStore {
 public static final int MAX_IMAGES=20;
 public record Summary(UUID id,UUID ownerId,String ownerName,String title,String priority,String category,String status,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
 public record Detail(UUID id,UUID ownerId,String title,String detailHtml,String priority,String category,String status,String adminReply,UUID handledBy,OffsetDateTime handledAt,long version,OffsetDateTime createdAt,OffsetDateTime updatedAt){}
 private final JdbcClient jdbc;private final AuditPort audit;private final NotificationProducerPort notifications;
 public FeedbackStore(JdbcClient jdbc,AuditPort audit){this(jdbc,audit,null);}
 @Autowired public FeedbackStore(JdbcClient jdbc,AuditPort audit,NotificationProducerPort notifications){this.jdbc=jdbc;this.audit=audit;this.notifications=notifications;}
 private void active(UUID id,String actor){
  if(id==null||actor==null||jdbc.sql("SELECT id FROM platform_user WHERE id=:id AND username=:actor AND enabled FOR SHARE").param("id",id).param("actor",actor).query(UUID.class).optional().isEmpty())throw fail(HttpStatus.UNAUTHORIZED,"unauthorized");
 }
 @Transactional public Detail create(UUID owner,String actor,String title,String html,String priority,String category,FeedbackPolicy policy){
  var content=FeedbackContent.validate(title,html);active(owner,actor);
  String value=priority==null||priority.isBlank()?policy.defaultPriority():priority.strip().toUpperCase(Locale.ROOT);
  if(!policy.priorities().contains(value))throw fail(HttpStatus.BAD_REQUEST,"invalidFeedback");
  String categoryValue=category==null?"":category.strip().toUpperCase(Locale.ROOT);
  if(!policy.categories().contains(categoryValue))throw fail(HttpStatus.BAD_REQUEST,"invalidFeedback");
  UUID id=UUID.randomUUID();jdbc.sql("INSERT INTO platform_feedback(id,owner_id,title,detail_html,priority,category,status) VALUES(:id,:owner,:title,:html,:priority,:category,:status)")
   .param("id",id).param("owner",owner).param("title",content.title()).param("html",content.detailHtml()).param("priority",value).param("category",categoryValue).param("status",policy.initialStatus()).update();
  attachImages(id,owner,FeedbackContent.imageIds(content.detailHtml()));
  audit.append(actor,"feedback.created",id);if(notifications!=null)notifications.publishFeedback(owner,id,1,content.title(),policy.initialStatus(),false);return findOwned(owner,id);
 }
 /** Bind only ready normalized files owned by the feedback author. */
 @Transactional public void attachImages(UUID feedbackId,UUID owner,Collection<UUID> imageIds){
  if(feedbackId==null||owner==null||imageIds==null||imageIds.size()>MAX_IMAGES)throw fail(HttpStatus.BAD_REQUEST,"invalidFeedback");
  for(UUID fileId:new LinkedHashSet<>(imageIds)){
   if(fileId==null||jdbc.sql("SELECT id FROM platform_file WHERE id=:id AND owner_id=:owner AND state='READY' AND media_type='image/png' FOR UPDATE").param("id",fileId).param("owner",owner).query(UUID.class).optional().isEmpty())throw fail(HttpStatus.BAD_REQUEST,"invalidFeedback");
   jdbc.sql("INSERT INTO platform_feedback_file(feedback_id,file_id) VALUES(:feedback,:file)").param("feedback",feedbackId).param("file",fileId).update();
  }
 }
 @Transactional(readOnly=true) public Detail findOwned(UUID owner,UUID id){
  Objects.requireNonNull(owner,"owner");return jdbc.sql("SELECT * FROM platform_feedback WHERE id=:id AND owner_id=:owner").param("id",id).param("owner",owner).query(Detail.class).optional().orElseThrow(()->fail(HttpStatus.NOT_FOUND,"feedbackNotFound"));
 }
 @Transactional(readOnly=true) public Detail findForManagement(UUID id){return jdbc.sql("SELECT * FROM platform_feedback WHERE id=:id").param("id",id).query(Detail.class).optional().orElseThrow(()->fail(HttpStatus.NOT_FOUND,"feedbackNotFound"));}
 @Transactional public Detail handle(UUID handler,String actor,UUID id,long version,String status,String reply,FeedbackPolicy policy){
  active(handler,actor);if(status==null||!policy.statuses().containsKey(status)||version<1)throw fail(HttpStatus.BAD_REQUEST,"invalidFeedback");
  String normalized=FeedbackContent.reply(reply);
  var current=jdbc.sql("SELECT * FROM platform_feedback WHERE id=:id FOR UPDATE").param("id",id).query(Detail.class).optional().orElseThrow(()->fail(HttpStatus.NOT_FOUND,"feedbackNotFound"));
  if(current.version()!=version)throw fail(HttpStatus.CONFLICT,"staleRecord");
  boolean terminal=policy.statuses().get(status);
  jdbc.sql("UPDATE platform_feedback SET status=:status,admin_reply=:reply,handled_by=:handler,handled_at=CASE WHEN :terminal THEN clock_timestamp() ELSE NULL END,version=version+1,updated_at=clock_timestamp() WHERE id=:id")
   .param("status",status).param("reply",normalized).param("handler",handler).param("terminal",terminal).param("id",id).update();
  audit.append(actor,"feedback.handled",id);if(notifications!=null&&current.ownerId()!=null)notifications.publishFeedback(current.ownerId(),id,current.version()+1,current.title(),status,true);return findForManagement(id);
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public PageResult<Summary> listOwned(UUID owner,PageRequest page,String status,String priority,String category){return list(Objects.requireNonNull(owner,"owner"),false,page,status,priority,category);}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public PageResult<Summary> listForManagement(PageRequest page,String status,String priority,String category){return list(null,true,page,status,priority,category);}
 private PageResult<Summary> list(UUID owner,boolean all,PageRequest page,String status,String priority,String category){
  String from=" FROM platform_feedback f LEFT JOIN platform_user u ON u.id=f.owner_id";
  String filter=(all?" WHERE true":" WHERE f.owner_id=:owner")+" AND (:status='' OR f.status=:status) AND (:priority='' OR f.priority=:priority) AND (:category='' OR f.category=:category) AND (:search='' OR strpos(lower(f.title),lower(:search))>0)";
  var fieldFilter=PageFilterSql.build(page,Map.of("title","f.title","ownerName","coalesce(u.display_name,u.username)","status","f.status","priority","f.priority","category","f.category","updatedAt","f.updated_at"));
  Map<String,Object> params=new HashMap<>();if(!all)params.put("owner",owner);params.put("status",status==null?"":status);params.put("priority",priority==null?"":priority);params.put("category",category==null?"":category);params.put("search",page.search());params.putAll(fieldFilter.parameters());
  long count=jdbc.sql("SELECT count(*)"+from+filter+fieldFilter.sql()).params(params).query(Long.class).single();
  var rows=jdbc.sql("SELECT f.id,f.owner_id,coalesce(u.display_name,u.username) AS owner_name,f.title,f.priority,f.category,f.status,f.version,f.created_at,f.updated_at"+from+filter+fieldFilter.sql()+" ORDER BY f.created_at DESC,f.id DESC LIMIT :limit OFFSET :offset")
   .params(params).param("limit",page.pageSize()).param("offset",page.offset()).query(Summary.class).list();return new PageResult<>(rows,count,page.page(),page.pageSize());
 }
 private static ResponseStatusException fail(HttpStatus status,String code){return new ResponseStatusException(status,code);}
}
