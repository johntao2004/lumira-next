package com.lumira.platform.feedback;

import com.lumira.platform.dictionaries.api.DictionaryLookupPort;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolve server-owned dictionaries; a missing/inconsistent policy must not silently fall back. */
@Service
public class FeedbackCatalog {
 public static final String STATUS="work_order_feedback_status",PRIORITY="work_order_feedback_priority",CATEGORY="work_order_feedback_category",DEFAULT="work_order_feedback_default";
 public record Choice(String value,String label,boolean terminal){}
 public record Catalog(FeedbackPolicy policy,List<Choice> statuses,List<Choice> priorities,List<Choice> categories){public Catalog{statuses=List.copyOf(statuses);priorities=List.copyOf(priorities);categories=List.copyOf(categories);}}
 private final DictionaryLookupPort dictionaries;
 public FeedbackCatalog(DictionaryLookupPort dictionaries){this.dictionaries=dictionaries;}
 @Transactional(readOnly=true)
 public Catalog load(){
  var items=dictionaries.policyItems(List.of(STATUS,PRIORITY,CATEGORY,DEFAULT));
  Map<String,Boolean> statuses=new LinkedHashMap<>();Set<String> priorities=new LinkedHashSet<>(),categories=new LinkedHashSet<>();Map<String,String> defaults=new HashMap<>();List<Choice> statusChoices=new ArrayList<>(),priorityChoices=new ArrayList<>(),categoryChoices=new ArrayList<>();
  for(var item:items){
   String value=item.value().strip().toUpperCase(Locale.ROOT),label=item.label().strip();
   if(label.isEmpty())throw unavailable();
   switch(item.dictionaryCode()){
    case STATUS->{boolean terminal="TERMINAL".equalsIgnoreCase(item.remark().strip());if(statuses.putIfAbsent(value,terminal)!=null)throw unavailable();statusChoices.add(new Choice(value,label,terminal));}
    case PRIORITY->{if(!priorities.add(value))throw unavailable();priorityChoices.add(new Choice(value,label,false));}
    case CATEGORY->{if(!categories.add(value))throw unavailable();categoryChoices.add(new Choice(value,label,false));}
    case DEFAULT->{if(defaults.putIfAbsent(value,label)!=null)throw unavailable();}
    default->throw unavailable();
   }
  }
  String initial=defaults.get("INITIAL_STATUS"),priority=defaults.get("DEFAULT_PRIORITY");
  if(initial==null||priority==null)throw unavailable();
  try{return new Catalog(new FeedbackPolicy(statuses,priorities,categories,initial.toUpperCase(Locale.ROOT),priority.toUpperCase(Locale.ROOT)),statusChoices,priorityChoices,categoryChoices);}
  catch(IllegalArgumentException ex){throw unavailable();}
 }
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"feedbackPolicyUnavailable");}
}
