package com.lumira.platform.identity;
import java.io.IOException;
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
   // One round trip: an enabled account always yields at least one row (authority is NULL when it holds no grants).
   var grants=new java.util.ArrayList<SimpleGrantedAuthority>();
   boolean[] active={false};
   jdbc.sql("SELECT a.authority FROM platform_user u LEFT JOIN platform_effective_authority a ON a.user_id=u.id WHERE u.username=:name AND u.enabled=true")
    .param("name",authentication.getName()).query(rs->{active[0]=true;String authority=rs.getString(1);if(authority!=null)grants.add(new SimpleGrantedAuthority(authority));});
   if(!active[0]){
    context.setAuthentication(null);var session=request.getSession(false);if(session!=null)session.invalidate();
    response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"detail\":\"账号已停用或会话已失效\"}");return;
   }
   var refreshed=UsernamePasswordAuthenticationToken.authenticated(authentication.getPrincipal(),null,grants);
   refreshed.setDetails(authentication.getDetails());context.setAuthentication(refreshed);
  }
  chain.doFilter(request,response);
 }
}
