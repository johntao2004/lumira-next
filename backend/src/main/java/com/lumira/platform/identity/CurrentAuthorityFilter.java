package com.lumira.platform.identity;
import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
/** Re-evaluates current database grants; session snapshots never retain revoked access. */
final class CurrentAuthorityFilter extends OncePerRequestFilter {
 private final JdbcClient jdbc;
 CurrentAuthorityFilter(JdbcClient jdbc){this.jdbc=jdbc;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  var context=SecurityContextHolder.getContext();var authentication=context.getAuthentication();
  if(authentication!=null&&authentication.isAuthenticated()&&!(authentication instanceof AnonymousAuthenticationToken)){
   var id=jdbc.sql("SELECT id FROM platform_user WHERE username=:name AND enabled=true").param("name",authentication.getName()).query(UUID.class).optional();
   if(id.isEmpty()){
    context.setAuthentication(null);var session=request.getSession(false);if(session!=null)session.invalidate();
    response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"detail\":\"账号已停用或会话已失效\"}");return;
   }
   var grants=jdbc.sql("SELECT authority FROM platform_effective_authority WHERE user_id=:id").param("id",id.get()).query(String.class).list().stream().map(SimpleGrantedAuthority::new).toList();
   var refreshed=UsernamePasswordAuthenticationToken.authenticated(authentication.getPrincipal(),null,grants);
   refreshed.setDetails(authentication.getDetails());context.setAuthentication(refreshed);
  }
  chain.doFilter(request,response);
 }
}
