package com.lumira.platform.common;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;
/** Atomic PostgreSQL minute buckets shared across application instances. */
@Repository
public class FixedWindowBuckets {
 private final JdbcClient jdbc;
 public FixedWindowBuckets(JdbcClient jdbc){this.jdbc=jdbc;}
 public static void validateLimit(int limit){if(limit<1||limit>10000)throw new IllegalArgumentException("Rate limit must be between 1 and 10000");}
 public static String key(String namespace,String value){
  if(!namespace.matches("[a-z-]{1,15}"))throw new IllegalArgumentException("Invalid rate namespace");
  try{return namespace+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
  catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
 }
 public boolean consume(String key,int limit){
  validateLimit(limit);
  int attempts=jdbc.sql("INSERT INTO platform_rate_bucket(bucket_key,window_start,attempts) VALUES(:key,date_trunc('minute',CURRENT_TIMESTAMP),1) ON CONFLICT(bucket_key) DO UPDATE SET window_start=date_trunc('minute',CURRENT_TIMESTAMP),attempts=CASE WHEN platform_rate_bucket.window_start<date_trunc('minute',CURRENT_TIMESTAMP) THEN 1 ELSE LEAST(platform_rate_bucket.attempts+1,:cap) END RETURNING attempts")
   .param("key",key).param("cap",limit+1).query(Integer.class).single();
  return attempts<=limit;
 }
 @Scheduled(fixedDelay=3600000,initialDelay=3600000)
 public void cleanup(){jdbc.sql("DELETE FROM platform_rate_bucket WHERE window_start<CURRENT_TIMESTAMP-INTERVAL '1 day'").update();}
}
