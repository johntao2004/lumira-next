package com.lumira.platform.drafts;

import com.lumira.platform.common.BoundedBody;
import com.lumira.platform.modules.ModuleEndpoint;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account/drafts")
@ModuleEndpoint("identity")
public class AccountDraftController {
 private final AccountDraftService service;
 public AccountDraftController(AccountDraftService service){this.service=service;}
 @GetMapping public com.lumira.platform.common.PageResult<AccountDraftService.Summary> list(Authentication auth,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String search,@RequestParam java.util.Map<String,String> parameters){return service.list(auth.getName(),com.lumira.platform.common.PageRequest.fromQueryParameters(page,pageSize,search,parameters));}
 @GetMapping("/{key}") public ResponseEntity<AccountDraftService.Draft> find(Authentication auth,@PathVariable String key){var draft=service.find(auth.getName(),key);return draft==null?ResponseEntity.noContent().build():ResponseEntity.ok(draft);}
 @PutMapping("/{key}") public AccountDraftService.Draft save(Authentication auth,@PathVariable String key,
  @BoundedBody(maxBytes=1004096,errorCode="draftTooLarge") @RequestBody AccountDraftService.Input input){return service.save(auth.getName(),key,input);}
 @DeleteMapping("/{key}") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void delete(Authentication auth,@PathVariable String key,@RequestParam UUID revision){service.delete(auth.getName(),key,revision);}
}
