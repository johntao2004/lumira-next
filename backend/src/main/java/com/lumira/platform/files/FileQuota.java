package com.lumira.platform.files;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Byte reservations and file slots include pending uploads and pending physical deletions. */
public record FileQuota(long maximumBytes,long maximumFiles) {
 public FileQuota {if(maximumBytes<0||maximumFiles<0)throw new IllegalArgumentException("Negative file quota");}
 public void requireCapacity(long chargedBytes,long fileCount,long reservation){
  if(chargedBytes<0||fileCount<0||reservation<0)throw new IllegalArgumentException("Negative file usage");
  if(fileCount>=maximumFiles||chargedBytes>maximumBytes||reservation>maximumBytes-chargedBytes)
   throw new ResponseStatusException(HttpStatus.CONFLICT,"fileQuotaExceeded");
 }
}
