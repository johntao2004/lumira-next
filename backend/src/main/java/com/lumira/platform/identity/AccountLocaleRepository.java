package com.lumira.platform.identity;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountLocaleRepository {
    public record Preference(String locale) {}

    private final JdbcClient jdbc;

    public AccountLocaleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Preference> find(String actor) {
        return jdbc.sql(
                        "SELECT preferred_locale FROM platform_user WHERE username=:actor AND enabled AND deleted_at IS NULL")
                .param("actor", actor)
                .query((rs, row) -> new Preference(rs.getString("preferred_locale")))
                .optional();
    }

    public boolean save(String actor, String locale) {
        // A UI preference does not invalidate concurrent profile/permission edits.
        return jdbc.sql(
                                "UPDATE platform_user SET preferred_locale=:locale WHERE username=:actor AND enabled AND deleted_at IS NULL")
                        .param("locale", locale)
                        .param("actor", actor)
                        .update()
                == 1;
    }
}
