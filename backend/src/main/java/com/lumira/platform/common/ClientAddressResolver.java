package com.lumira.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Trust only an explicitly configured immediate proxy, never an arbitrary forwarded chain. */
@Component
public class ClientAddressResolver {
    public static final String HEADER = "X-Lumira-Client-IP";
    private final Set<String> trusted;

    public ClientAddressResolver(
            @Value("${lumira.login.trusted-proxy-addresses:}") String addresses) {
        var parsed = new HashSet<String>();
        for (var address : addresses.split(",")) {
            if (!address.isBlank()) parsed.add(literal(address.strip()));
        }
        trusted = Set.copyOf(parsed);
    }

    private static String literal(String address) {
        if (address.contains("%"))
            throw new IllegalArgumentException("Scoped client addresses are not supported");
        return InetAddress.ofLiteral(address).getHostAddress();
    }

    public String resolve(HttpServletRequest request) {
        String remote = literal(request.getRemoteAddr());
        if (!trusted.contains(remote)) return remote;
        var values = request.getHeaders(HEADER);
        if (values == null || !values.hasMoreElements()) return remote;
        String candidate = values.nextElement();
        if (values.hasMoreElements()) return remote;
        try {
            return literal(candidate);
        } catch (IllegalArgumentException error) {
            return remote;
        }
    }
}
