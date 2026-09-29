package com.lumira.platform.localization;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LocalizationLocaleRepository {
    public record Language(String locale, String displayName, String sourceLocale, String fallbackLocale,
                           boolean builtIn, boolean enabled, long version, OffsetDateTime createdAt,
                           OffsetDateTime updatedAt) {}

    private static final String SELECT = "SELECT locale_code,display_name,source_locale,fallback_locale,built_in,enabled,version,created_at,updated_at FROM platform_localization_locale";
    private final JdbcClient jdbc;

    public LocalizationLocaleRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<Language> listEnabled() {
        return jdbc.sql(SELECT + " WHERE enabled=true ORDER BY built_in DESC,display_name,locale_code")
            .query((rs, row) -> new Language(rs.getString("locale_code"), rs.getString("display_name"),
                rs.getString("source_locale"), rs.getString("fallback_locale"), rs.getBoolean("built_in"),
                rs.getBoolean("enabled"), rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class))).list();
    }

    public Optional<Language> find(String locale) {
        return jdbc.sql(SELECT + " WHERE locale_code=:locale AND enabled=true").param("locale", locale)
            .query((rs, row) -> new Language(rs.getString("locale_code"), rs.getString("display_name"),
                rs.getString("source_locale"), rs.getString("fallback_locale"), rs.getBoolean("built_in"),
                rs.getBoolean("enabled"), rs.getLong("version"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class))).optional();
    }

    public boolean exists(String locale) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform_localization_locale WHERE locale_code=:locale AND enabled=true)")
            .param("locale", locale).query(Boolean.class).single();
    }

    /** New locale bundles never replace registry metadata for a locale that already exists. */
    public int insert(String locale, String displayName, String sourceLocale, String fallbackLocale, String actor) {
        return jdbc.sql("""
            INSERT INTO platform_localization_locale(locale_code,display_name,source_locale,fallback_locale,built_in,enabled,created_by,updated_by)
            VALUES(:locale,:displayName,:sourceLocale,:fallbackLocale,false,true,:actor,:actor)
            ON CONFLICT(locale_code) DO NOTHING
            """).param("locale", locale).param("displayName", displayName).param("sourceLocale", sourceLocale)
            .param("fallbackLocale", fallbackLocale).param("actor", actor).update();
    }
}
