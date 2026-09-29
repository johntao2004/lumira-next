package com.lumira.platform.identity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("identity")
public class ProfileCompletionController {
 private final ProfileCompletionService service;
 public ProfileCompletionController(ProfileCompletionService service){this.service=service;}
 @GetMapping("/api/account/profile-completion") public ProfileCompletionService.View read(Authentication auth){return service.read(auth.getName());}
}
