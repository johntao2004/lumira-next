package com.lumira.platform.identity;
import com.lumira.platform.common.FixedWindowBuckets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PasswordRecoveryLimiterTest {
 @Test void sourceDenialDoesNotCreateAttackerControlledAccountBuckets(){
  var buckets=mock(FixedWindowBuckets.class);var limiter=new PasswordRecoveryLimiter(buckets,10,60);
  assertFalse(limiter.allow("new-user","127.0.0.1"));verify(buckets).consume(FixedWindowBuckets.key("recovery-source","127.0.0.1"),60);verifyNoMoreInteractions(buckets);
 }
 @Test void accountsAreNormalizedAndLimitsValidated(){
  var buckets=mock(FixedWindowBuckets.class);when(buckets.consume(anyString(),anyInt())).thenReturn(true);
  assertTrue(new PasswordRecoveryLimiter(buckets,10,60).allow(" User ","127.0.0.1"));verify(buckets).consume(FixedWindowBuckets.key("recovery-user","user"),10);
  assertThrows(IllegalArgumentException.class,()->new PasswordRecoveryLimiter(buckets,0,60));
 }
}
