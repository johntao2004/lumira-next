package com.lumira.platform.identity;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Primary credentials never publish a security context while a second factor is pending. */
final class PasswordLoginFilter extends UsernamePasswordAuthenticationFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordLoginFilter.class);
    private final SecondFactorLoginService flow;
    private final ObjectMapper json;
    private final CaptchaProofAccess captcha;

    PasswordLoginFilter(
            SecondFactorLoginService flow, ObjectMapper json, CaptchaProofAccess captcha) {
        this.flow = flow;
        this.json = json;
        this.captcha = captcha;
        setFilterProcessesUrl("/api/auth/login");
    }

    PasswordLoginFilter(SecondFactorLoginService flow, ObjectMapper json) {
        this(flow, json, null);
    }

    @Override
    public Authentication attemptAuthentication(HttpServletRequest req, HttpServletResponse res)
            throws AuthenticationException {
        if (captcha != null) {
            try {
                var gate =
                        captcha.begin(
                                com.lumira.platform.common.CaptchaProofPort.Action.PASSWORD_LOGIN,
                                null,
                                null,
                                req);
                captcha.consume(gate, req.getParameter("captchaProofId"), req, null);
            } catch (org.springframework.web.server.ResponseStatusException error) {
                throw new CaptchaAuthenticationException(
                        error.getStatusCode().value(), error.getReason(), error);
            }
        }
        return super.attemptAuthentication(req, res);
    }

    static final class CaptchaAuthenticationException extends AuthenticationServiceException {
        private final int status;
        private final String code;

        CaptchaAuthenticationException(int status, String code, Throwable cause) {
            super("CAPTCHA verification required", cause);
            this.status = status;
            this.code = code == null ? "captchaUnavailable" : code;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }

    @Override
    protected void successfulAuthentication(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain, Authentication auth)
            throws IOException, ServletException {
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        try {
            var result = flow.primary(auth, req, res);
            res.setStatus(result.secondFactorRequired() ? 202 : 200);
            res.getWriter().write(json.writeValueAsString(result));
        } catch (RuntimeException error) {
            if (!(error instanceof ResponseStatusException)) {
                LOGGER.error(
                        "Password login flow failed; exception messages omitted. Type: {}\n{}",
                        error.getClass().getName(),
                        sanitizedStack(error));
            }
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            var session = req.getSession(false);
            if (session != null) session.invalidate();
            res.setStatus(
                    error instanceof ResponseStatusException e ? e.getStatusCode().value() : 503);
            res.getWriter()
                    .write(
                            json.writeValueAsString(
                                    java.util.Map.of(
                                            "detail",
                                            error instanceof ResponseStatusException e
                                                    ? e.getReason()
                                                    : "unavailable")));
        }
    }

    private static String sanitizedStack(Throwable error) {
        StringBuilder trace = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (!trace.isEmpty()) trace.append("\nCaused by ");
            trace.append(cause.getClass().getName());
            StackTraceElement[] frames = cause.getStackTrace();
            for (int index = 0; index < Math.min(frames.length, 50); index++) {
                trace.append("\n  at ").append(frames[index]);
            }
        }
        return trace.toString();
    }
}
