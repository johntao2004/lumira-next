package com.lumira.platform.identity;

import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Record only resolved account identities; never persist submitted passwords or unknown names. */
@Service
public class LoginAudit {
    private static final Set<String> ACTIONS =
            Set.of(
                    "account.loginSucceeded",
                    "account.loginFailed",
                    "account.loginRateLimited",
                    "account.loginRejected");
    private final JdbcClient jdbc;

    public LoginAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void record(String username, boolean successful, String address, String userAgent) {
        record(
                username,
                successful ? "account.loginSucceeded" : "account.loginFailed",
                address,
                userAgent);
    }

    /** Record a bounded, account-bound login outcome without persisting unknown input. */
    @Transactional
    public void record(String username, String action, String address, String userAgent) {
        if (username == null || username.isBlank() || username.length() > 100) return;
        if (!ACTIONS.contains(action)) return;
        jdbc.sql(
                        """
   INSERT INTO platform_audit(id,actor,action,target_id,client_address,user_agent)
   SELECT gen_random_uuid(),username,:action,id,:address,:agent FROM platform_user WHERE username=:username
   """)
                .param(
                        "address",
                        address == null
                                ? null
                                : java.net.InetAddress.ofLiteral(address).getHostAddress())
                .param("agent", cleanAgent(userAgent))
                .param("username", username)
                .param("action", action)
                .update();
    }

    static String cleanAgent(String value) {
        if (value == null) return null;
        var out = new StringBuilder();
        value.codePoints()
                .filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
                .limit(256)
                .forEach(out::appendCodePoint);
        var result = out.toString().strip();
        return result.isEmpty() ? null : result;
    }
}
