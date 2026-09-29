package com.lumira.platform.identity;

import com.lumira.platform.common.FixedWindowBuckets;
import com.lumira.platform.identity.api.SecurityPolicyPort;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class PasswordRecoveryLimiter {
 private final FixedWindowBuckets buckets;private final int accountLimit,sourceLimit;private final SecurityPolicyPort security;
 @Autowired
 public PasswordRecoveryLimiter(FixedWindowBuckets buckets,SecurityPolicyPort security){this(buckets,10,60,security);}
 public PasswordRecoveryLimiter(FixedWindowBuckets buckets,@Value("${lumira.recovery.account-per-minute:10}") int accountLimit,@Value("${lumira.recovery.source-per-minute:60}") int sourceLimit){
  this(buckets,accountLimit,sourceLimit,null);
 }
 private PasswordRecoveryLimiter(FixedWindowBuckets buckets,int accountLimit,int sourceLimit,SecurityPolicyPort security){FixedWindowBuckets.validateLimit(accountLimit);FixedWindowBuckets.validateLimit(sourceLimit);this.buckets=buckets;this.accountLimit=accountLimit;this.sourceLimit=sourceLimit;this.security=security;}
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public boolean allow(String username,String source){
  var policy=security==null?null:security.currentPolicy();int sourceCap=policy==null?sourceLimit:policy.recoverySourcePerMinute();int accountCap=policy==null?accountLimit:policy.recoveryAccountPerMinute();
  if(!buckets.consume(FixedWindowBuckets.key("recovery-source",source),sourceCap))return false;
  String account=username==null?"":username.strip().toLowerCase(Locale.ROOT);
  return buckets.consume(FixedWindowBuckets.key("recovery-user",account),accountCap);
 }
}
