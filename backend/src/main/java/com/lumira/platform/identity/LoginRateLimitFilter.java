package com.lumira.platform.identity;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.identity.api.PasswordContract;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;

final class LoginRateLimitFilter extends OncePerRequestFilter {
    private final LoginRateLimiter limiter;
    private final ClientAddressResolver addresses;
    private final LoginAudit audit;
    LoginRateLimitFilter(LoginRateLimiter limiter,ClientAddressResolver addresses) { this(limiter,addresses,null); }
    LoginRateLimitFilter(LoginRateLimiter limiter,ClientAddressResolver addresses,LoginAudit audit) { this.limiter=limiter;this.addresses=addresses;this.audit=audit; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !"/api/auth/login".equals(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        String account=request.getParameter("username");
        account=account==null||account.length()>100?"<invalid>":account.strip().toLowerCase(Locale.ROOT);
        if(!limiter.allow(account,addresses.resolve(request))) {
            record(account,"account.loginRateLimited",request);
            response.setStatus(429);response.setHeader("Retry-After","60");response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"detail\":\"loginRateLimited\"}");return;
        }
        String password=request.getParameter("password");
        if(!PasswordContract.withinMaximum(password)) {
            record(account,"account.loginRejected",request);
            response.setStatus(401);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"detail\":\"loginFailed\"}");return;
        }
        chain.doFilter(request,response);
    }
    private void record(String account,String action,HttpServletRequest request) {
        if(audit==null)return;
        try { audit.record(account,action,addresses.resolve(request),request.getHeader("User-Agent")); }
        catch(RuntimeException ignored) { /* authentication outcome remains fail-closed; audit is best effort here */ }
    }
}
