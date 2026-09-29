package com.lumira.platform.identity;
import com.lumira.platform.common.ClientAddressResolver;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lumira.platform.common.BoundedBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
@RequestMapping("/api/auth/password-recovery")
public class PasswordRecoveryController {
 private final PasswordRecoveryService service;private final PasswordRecoveryLimiter limiter;private final ClientAddressResolver addresses;
 public PasswordRecoveryController(PasswordRecoveryService service,PasswordRecoveryLimiter limiter,ClientAddressResolver addresses){this.service=service;this.limiter=limiter;this.addresses=addresses;}
 public record Request(String username,String locale){}
 public record Confirmation(String username,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String token,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY) String newPassword){
  @Override public String toString(){return "PasswordRecoveryConfirmation[redacted]";}
 }
 private void check(String username,HttpServletRequest request){
  if(!limiter.allow(username,addresses.resolve(request)))throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"passwordRecoveryLimited");
  if(username==null||username.isBlank()||username.length()>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidRecoveryRequest");
 }
 @PostMapping("/request") @ResponseStatus(HttpStatus.ACCEPTED)
 public void request(@BoundedBody(maxBytes=2048,errorCode="invalidRecoveryRequest") @RequestBody Request input,HttpServletRequest request){
  check(input.username(),request);service.request(input.username(),input.locale());
 }
 @PostMapping("/confirm") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void confirm(@BoundedBody(maxBytes=2048,errorCode="invalidRecoveryRequest") @RequestBody Confirmation input,HttpServletRequest request){
  check(input.username(),request);
  // Map failure after service commit, preserving failed-proof counts and audit.
  if(!service.confirm(input.username(),input.token(),input.newPassword()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalidRecoveryToken");
 }
}
