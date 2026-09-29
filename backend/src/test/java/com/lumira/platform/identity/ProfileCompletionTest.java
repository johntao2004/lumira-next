package com.lumira.platform.identity;
import com.lumira.platform.identity.api.ProfileFields;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProfileCompletionTest {
 @Test void incompleteSmallWeightNeverRoundsToComplete(){
  var schema=new ProfileFields(List.of(f("a","g",100,true,0),f("b","g",100,true,1),f("missing","g",1,true,2)));
  var incomplete=ProfileCompletion.calculate(schema,Set.of("a","b"));
  assertEquals(200,incomplete.earnedWeight());assertEquals(201,incomplete.totalWeight());
  assertEquals(99,incomplete.percent());assertEquals(99,incomplete.groups().getFirst().percent());
  var complete=ProfileCompletion.calculate(schema,Set.of("a","b","missing"));
  assertEquals(100,complete.percent());assertEquals(100,complete.groups().getFirst().percent());
 }
 private ProfileFields.Field f(String key,String group,int weight,boolean visible,int sort){return new ProfileFields.Field(key,key,"","TEXT",visible,false,weight,"",group,group,sort,true,List.of());}
 @Test void sumsWeightsBeforeRounding(){var schema=new ProfileFields(List.of(f("a","g",1,true,0),f("b","g",1,true,1),f("c","g",1,true,2)));var score=ProfileCompletion.calculate(schema,Set.of("a","b"));assertEquals(67,score.percent());assertEquals(2,score.earnedWeight());assertEquals(100,ProfileCompletion.calculate(schema,Set.of("a","b","c")).percent());}
 @Test void hiddenFieldsDoNotAffectScoresOrGroups(){var score=ProfileCompletion.calculate(new ProfileFields(List.of(f("hidden","hidden",100,false,0),f("shown","g",5,true,1))),Set.of("hidden"));assertEquals(5,score.totalWeight());assertEquals(0,score.percent());assertEquals(1,score.groups().size());}
 @Test void stableGroupOrderAndEmptySchema(){var score=ProfileCompletion.calculate(new ProfileFields(List.of(f("late","b",10,true,20),f("early","a",30,true,1))),Set.of("late"));assertEquals(25,score.percent());assertEquals(List.of("a","b"),score.groups().stream().map(ProfileCompletion.Group::key).toList());assertEquals(100,score.groups().get(1).percent());assertEquals(0,ProfileCompletion.calculate(new ProfileFields(List.of()),Set.of()).percent());}
}
