package com.lumira.platform.web;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
@RestController
@com.lumira.platform.modules.ModuleEndpoint("platform")
public class PlatformController {
 private final JdbcClient jdbc;private final com.lumira.platform.modules.ModuleCapabilityPort modules;
 public PlatformController(JdbcClient jdbc,com.lumira.platform.modules.ModuleCapabilityPort modules){this.jdbc=jdbc;this.modules=modules;}
 @GetMapping("/api/auth/csrf") public Map<String,String> csrf(CsrfToken token){return Map.of("headerName",token.getHeaderName(),"token",token.getToken());}
 @GetMapping("/api/auth/me") public Map<String,Object> me(Authentication auth){
  String displayName=jdbc.sql("SELECT display_name FROM platform_user WHERE username=:username AND enabled=true").param("username",auth.getName()).query(String.class).optional().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
  return Map.of("enabledModules",modules.enabledModules(),"username",auth.getName(),"displayName",displayName,"globalAuthorities",jdbc.sql("SELECT a.authority FROM platform_global_authority a JOIN platform_user u ON u.id=a.user_id WHERE u.username=:username AND u.enabled ORDER BY a.authority").param("username",auth.getName()).query(String.class).list(),"authorities",auth.getAuthorities().stream().map(a->a.getAuthority()).toList());
 }

}
