package com.lumira.platform.identity;

import com.lumira.platform.common.AuthenticationPolicyWriteLock;
import com.lumira.platform.common.ClientAddressResolver;
import com.lumira.platform.common.CsrfTokenContract;
import com.lumira.platform.identity.api.MaintenancePolicyPort;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
public class SecurityConfiguration {
    private static void json(HttpServletResponse response, int status, String body)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            JdbcClient jdbc,
            LoginRateLimiter limiter,
            LoginAudit loginAudit,
            ClientAddressResolver addresses,
            @org.springframework.beans.factory.annotation.Value("${lumira.metrics.token:}")
                    String metricsToken,
            MaintenancePolicyPort maintenance,
            AuthenticationPolicyService authenticationPolicies,
            AuthenticationPolicyWriteLock authenticationPolicyWriteLock,
            RolePreviewContextService rolePreviewContexts,
            org.springframework.transaction.support.TransactionTemplate transactionTemplate,
            org.springframework.beans.factory.ObjectProvider<
                            com.lumira.platform.common.PluginAuthorizationPort>
                    plugins,
            SecondFactorLoginService secondFactor,
            CaptchaProofAccess captchaProofAccess,
            org.springframework.beans.factory.ObjectProvider<ApiDocsAccessPolicy> apiDocsAccess,
            tools.jackson.databind.ObjectMapper mapper,
            org.springframework.security.config.annotation.authentication.configuration
                            .AuthenticationConfiguration
                    authenticationConfiguration)
            throws Exception {
        http.authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/platform/api-docs")
                                        .access(
                                                (authentication, context) ->
                                                        new org.springframework.security
                                                                .authorization
                                                                .AuthorizationDecision(
                                                                PlatformHttpPermissionPolicy
                                                                                .authorize(
                                                                                        authentication
                                                                                                .get(),
                                                                                        context
                                                                                                .getRequest(),
                                                                                        plugins
                                                                                                .getIfAvailable())
                                                                        && apiDocsAccess
                                                                                        .getIfAvailable()
                                                                                != null
                                                                        && apiDocsAccess
                                                                                .getIfAvailable()
                                                                                .allows(
                                                                                        authentication
                                                                                                .get())))
                                        .requestMatchers(
                                                "/api/platform/api-docs",
                                                "/api/platform/api-docs.yaml",
                                                "/api/platform/api-docs/**",
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/v3/api-docs.yaml",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .denyAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/platform/modules/plugins/frontend",
                                                "/api/platform/modules/plugins/assets/**",
                                                "/api/platform/navigation")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/platform/role-preview/bootstrap")
                                        .authenticated()
                                        .requestMatchers("/api/platform/**")
                                        .access(
                                                (authentication, context) ->
                                                        new org.springframework.security
                                                                .authorization
                                                                .AuthorizationDecision(
                                                                PlatformHttpPermissionPolicy
                                                                        .authorize(
                                                                                authentication
                                                                                        .get(),
                                                                                context
                                                                                        .getRequest(),
                                                                                plugins
                                                                                        .getIfAvailable())))
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/auth/methods",
                                                "/api/auth/login/state",
                                                "/api/auth/registration/profile-schema",
                                                "/api/auth/registration/config",
                                                "/api/auth/registration/state",
                                                "/api/auth/password-recovery/state",
                                                "/api/auth/wechat/callback",
                                                "/api/auth/wechat/result")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/auth/login/cancel",
                                                "/api/auth/captcha/challenges",
                                                "/api/auth/captcha/verify",
                                                "/api/auth/login/captcha/confirm",
                                                "/api/auth/login/password-change",
                                                "/api/auth/wechat/attempts",
                                                "/api/auth/registration/challenges",
                                                "/api/auth/registration/challenges/verify",
                                                "/api/auth/registration/complete",
                                                "/api/auth/login-code/challenges",
                                                "/api/auth/login-code/complete",
                                                "/api/auth/password-recovery/request",
                                                "/api/auth/password-recovery/verify",
                                                "/api/auth/password-recovery/confirm")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/auth/second-factor/pending")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/auth/second-factor/confirm",
                                                "/api/auth/second-factor/enrollment/start",
                                                "/api/auth/second-factor/enrollment/confirm",
                                                "/api/auth/second-factor/recovery/request",
                                                "/api/auth/second-factor/recovery/verify",
                                                "/api/auth/second-factor/cancel")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/authentication-policy")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/role-preview",
                                                "/api/account/role-preview/roles",
                                                "/api/account/role-preview/resource-tickets/*")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/account/role-preview",
                                                "/api/account/role-preview/resource-tickets",
                                                "/api/platform/role-preview/bootstrap")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.DELETE,
                                                "/api/account/role-preview")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/wechat")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/account/wechat/binding/start",
                                                "/api/account/wechat/binding/confirm")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.DELETE,
                                                "/api/account/wechat")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/security-events")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/notifications",
                                                "/api/account/notifications/unread-count")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/account/notifications/*/read",
                                                "/api/account/notifications/read-all")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/auth/passkeys/authentication/availability")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/auth/passkeys/authentication/options",
                                                "/api/auth/passkeys/authentication/complete")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/auth/passkeys/registration/options",
                                                "/api/auth/passkeys/registration/complete")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/auth/passkeys")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.PATCH,
                                                "/api/auth/passkeys/*")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.DELETE,
                                                "/api/auth/passkeys/*")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/email")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/account/email/request",
                                                "/api/account/email/confirm")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/phone")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/account/phone/request",
                                                "/api/account/phone/confirm")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/public/localization/bundle/*",
                                                "/api/public/localization/locales")
                                        .permitAll()
                                        .dispatcherTypeMatchers(
                                                jakarta.servlet.DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/profile-fields")
                                        .authenticated()
                                        .requestMatchers(
                                                "/api/account/profile-values",
                                                "/api/account/profile-completion")
                                        .authenticated()
                                        .requestMatchers(
                                                "/api/account/files",
                                                "/api/account/files/*",
                                                "/api/account/files/*/content",
                                                "/api/account/files/*/restore",
                                                "/api/account/files/*/purge")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/account/files/*/preview")
                                        .authenticated()
                                        .requestMatchers(
                                                "/api/account/avatar",
                                                "/api/account/avatar/content")
                                        .authenticated()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/public/maintenance")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/public/watermark")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/public/agreements")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/public/branding")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/public/branding/assets/*")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/auth/csrf",
                                                "/api/auth/login",
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/prometheus")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/dictionaries/*/items",
                                                "/api/dictionaries/*/items/*")
                                        .access(
                                                (authentication, context) ->
                                                        new org.springframework.security
                                                                .authorization
                                                                .AuthorizationDecision(
                                                                PlatformHttpPermissionPolicy
                                                                        .authorize(
                                                                                authentication
                                                                                        .get(),
                                                                                context
                                                                                        .getRequest(),
                                                                                plugins
                                                                                        .getIfAvailable())))
                                        .requestMatchers(
                                                "/api/auth/me",
                                                "/api/auth/logout",
                                                "/api/account/reauthentication/start",
                                                "/api/account/reauthentication/complete",
                                                "/api/account/reauthentication/code",
                                                "/api/account/reauthentication/passkey/options",
                                                "/api/account/reauthentication/passkey/complete",
                                                "/api/account/password",
                                                "/api/account/profile",
                                                "/api/account/locale",
                                                "/api/account/sessions",
                                                "/api/account/sessions/*")
                                        .authenticated()
                                        .requestMatchers("/api/**")
                                        .access(
                                                (authentication, context) ->
                                                        new org.springframework.security
                                                                .authorization
                                                                .AuthorizationDecision(
                                                                plugins.getIfAvailable() != null
                                                                        && plugins.getIfAvailable()
                                                                                .authorize(
                                                                                        context.getRequest()
                                                                                                .getMethod(),
                                                                                        context.getRequest()
                                                                                                .getRequestURI(),
                                                                                        authentication
                                                                                                .get()
                                                                                                .getAuthorities()
                                                                                                .stream()
                                                                                                .map(
                                                                                                        org
                                                                                                                        .springframework
                                                                                                                        .security
                                                                                                                        .core
                                                                                                                        .GrantedAuthority
                                                                                                                ::getAuthority)
                                                                                                .collect(
                                                                                                        java
                                                                                                                .util
                                                                                                                .stream
                                                                                                                .Collectors
                                                                                                                .toSet()),
                                                                                        authentication
                                                                                                        .get()
                                                                                                        .isAuthenticated()
                                                                                                && !(authentication
                                                                                                                .get()
                                                                                                        instanceof
                                                                                                        org
                                                                                                                .springframework
                                                                                                                .security
                                                                                                                .authentication
                                                                                                                .AnonymousAuthenticationToken))))
                                        .anyRequest()
                                        .denyAll())
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(CsrfTokenContract.sessionRepository())
                                        .ignoringRequestMatchers(
                                                request -> {
                                                    var authorization = plugins.getIfAvailable();
                                                    if (authorization == null) return false;
                                                    String path = request.getRequestURI();
                                                    String contextPath = request.getContextPath();
                                                    if (contextPath != null
                                                            && !contextPath.isEmpty()
                                                            && path.startsWith(contextPath + "/")) {
                                                        path = path.substring(contextPath.length());
                                                    }
                                                    return authorization.signedCallback(
                                                            request.getMethod(), path);
                                                }))
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .maxAgeInSeconds(31536000))
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                org.springframework.security.web
                                                                        .header.writers
                                                                        .ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                        .permissionsPolicyHeader(
                                                permissions ->
                                                        permissions.policy(
                                                                "camera=(), microphone=(), geolocation=(), payment=()")))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .logout(
                        logout ->
                                logout.logoutUrl("/api/auth/logout")
                                        .deleteCookies("LUMIRA_NEXT_SESSION")
                                        .logoutSuccessHandler(
                                                (req, res, auth) -> res.setStatus(204)))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (req, res, error) ->
                                                        json(res, 401, "{\"detail\":\"请先登录\"}"))
                                        .accessDeniedHandler(
                                                (req, res, error) ->
                                                        json(
                                                                res,
                                                                403,
                                                                "{\"detail\":\"无权访问或安全令牌已过期\"}")));
        var passwordFilter = new PasswordLoginFilter(secondFactor, mapper, captchaProofAccess);
        passwordFilter.setAuthenticationManager(
                authenticationConfiguration.getAuthenticationManager());
        passwordFilter.setAuthenticationFailureHandler(
                (req, res, error) -> {
                    if (error
                            instanceof
                            PasswordLoginFilter.CaptchaAuthenticationException captchaError) {
                        json(
                                res,
                                captchaError.status(),
                                "{\"detail\":\"" + captchaError.code() + "\"}");
                        return;
                    }
                    if (error instanceof PasswordProofAuthenticationProvider.CapacityException) {
                        res.setHeader("Retry-After", "1");
                        json(res, 429, "{\"detail\":\"passwordHashCapacity\"}");
                        return;
                    }
                    loginAudit.record(
                            req.getParameter("username"),
                            false,
                            addresses.resolve(req),
                            req.getHeader("User-Agent"));
                    json(res, 401, "{\"detail\":\"loginFailed\"}");
                });
        http.addFilterAt(
                passwordFilter,
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                        .class);
        http.addFilterBefore(
                new LoginRateLimitFilter(limiter, addresses, loginAudit),
                org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
                        .class);
        http.addFilterBefore(new MetricsTokenFilter(metricsToken), AuthorizationFilter.class);
        http.addFilterBefore(
                new CurrentAuthorityFilter(
                        jdbc,
                        authenticationPolicies,
                        authenticationPolicyWriteLock,
                        transactionTemplate,
                        rolePreviewContexts),
                AuthorizationFilter.class);
        return http.build();
    }
}
