package com.lumira.platform.identity;

import com.lumira.platform.common.PrimaryMethod;
import com.lumira.platform.identity.api.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

/** Internal HTTP-facing completion bridge shared by the host's primary-authentication flows. */
public interface PrimaryAuthenticationPort {
    LoginResponse complete(
            Authentication authentication,
            PrimaryMethod method,
            String primaryCredentialRevision,
            HttpServletRequest request,
            HttpServletResponse response);

    default LoginResponse complete(
            Authentication authentication,
            PrimaryMethod method,
            HttpServletRequest request,
            HttpServletResponse response) {
        return complete(authentication, method, null, request, response);
    }
}
