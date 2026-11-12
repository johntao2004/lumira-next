package com.lumira.platform.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AiProviderRepository {
    public record ConsoleProfile(
            UUID id,
            String name,
            AiProviderProfile.ProviderKind providerKind,
            String modelName,
            List<String> capabilityCodes,
            boolean enabled) {
        public ConsoleProfile {
            capabilityCodes = List.copyOf(capabilityCodes);
        }

        public List<String> modelNames() {
            return AiProviderProfile.decodeModelNames(modelName);
        }

        public String modelName() {
            return modelNames().stream().findFirst().orElse("");
        }
    }

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AiProviderRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<AiProviderProfile> findAll() {
        return jdbc.sql(
                        """
            SELECT id,name,provider_kind,base_url,model_name,capability_codes::text AS capability_codes,enabled,version
            FROM platform_ai_provider ORDER BY created_at DESC,id
            """)
                .query(
                        (rs, row) ->
                                read(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("provider_kind"),
                                        rs.getString("base_url"),
                                        rs.getString("model_name"),
                                        rs.getString("capability_codes"),
                                        rs.getBoolean("enabled"),
                                        rs.getLong("version")))
                .list();
    }

    /** Minimal provider metadata for the AI console; network settings and secrets stay private. */
    public List<ConsoleProfile> findEnabledForConsole() {
        return jdbc.sql(
                        """
            SELECT id,name,provider_kind,model_name,capability_codes::text AS capability_codes,enabled
            FROM platform_ai_provider WHERE enabled ORDER BY created_at DESC,id
            """)
                .query(
                        (rs, row) ->
                                new ConsoleProfile(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        AiProviderProfile.ProviderKind.valueOf(
                                                rs.getString("provider_kind")),
                                        rs.getString("model_name"),
                                        readCapabilities(rs.getString("capability_codes")),
                                        rs.getBoolean("enabled")))
                .list();
    }

    public Optional<ConsoleProfile> findForConsole(UUID id) {
        return jdbc.sql(
                        """
            SELECT id,name,provider_kind,model_name,capability_codes::text AS capability_codes,enabled
            FROM platform_ai_provider WHERE id=:id
            """)
                .param("id", id)
                .query(
                        (rs, row) ->
                                new ConsoleProfile(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        AiProviderProfile.ProviderKind.valueOf(
                                                rs.getString("provider_kind")),
                                        rs.getString("model_name"),
                                        readCapabilities(rs.getString("capability_codes")),
                                        rs.getBoolean("enabled")))
                .optional();
    }

    public Optional<AiProviderProfile> find(UUID id) {
        return jdbc.sql(
                        """
            SELECT id,name,provider_kind,base_url,model_name,capability_codes::text AS capability_codes,enabled,version
            FROM platform_ai_provider WHERE id=:id
            """)
                .param("id", id)
                .query(
                        (rs, row) ->
                                read(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("provider_kind"),
                                        rs.getString("base_url"),
                                        rs.getString("model_name"),
                                        rs.getString("capability_codes"),
                                        rs.getBoolean("enabled"),
                                        rs.getLong("version")))
                .optional();
    }

    public void insert(AiProviderProfile profile, String capabilitiesJson, String actor) {
        jdbc.sql(
                        """
            INSERT INTO platform_ai_provider(id,name,provider_kind,base_url,model_name,capability_codes,enabled,created_by,updated_by)
            VALUES(:id,:name,:kind,:baseUrl,:model,CAST(:capabilities AS jsonb),:enabled,:actor,:actor)
            """)
                .param("id", profile.id())
                .param("name", profile.name())
                .param("kind", profile.providerKind().name())
                .param("baseUrl", profile.baseUrl())
                .param("model", profile.encodedModelNames())
                .param("capabilities", capabilitiesJson)
                .param("enabled", profile.enabled())
                .param("actor", actor)
                .update();
    }

    public int update(AiProviderProfile profile, String capabilitiesJson, String actor) {
        return jdbc.sql(
                        """
            UPDATE platform_ai_provider SET name=:name,provider_kind=:kind,base_url=:baseUrl,model_name=:model,
                capability_codes=CAST(:capabilities AS jsonb),enabled=:enabled,version=version+1,updated_by=:actor,updated_at=now()
            WHERE id=:id AND version=:version
            """)
                .param("id", profile.id())
                .param("name", profile.name())
                .param("kind", profile.providerKind().name())
                .param("baseUrl", profile.baseUrl())
                .param("model", profile.encodedModelNames())
                .param("capabilities", capabilitiesJson)
                .param("enabled", profile.enabled())
                .param("actor", actor)
                .param("version", profile.version())
                .update();
    }

    public int delete(UUID id, long version) {
        return jdbc.sql("DELETE FROM platform_ai_provider WHERE id=:id AND version=:version")
                .param("id", id)
                .param("version", version)
                .update();
    }

    private AiProviderProfile read(
            UUID id,
            String name,
            String kind,
            String baseUrl,
            String model,
            String capabilitiesJson,
            boolean enabled,
            long version) {
        try {
            return new AiProviderProfile(
                    id,
                    name,
                    AiProviderProfile.ProviderKind.valueOf(kind),
                    baseUrl,
                    model,
                    mapper.readValue(capabilitiesJson, STRINGS),
                    enabled,
                    version);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Stored AI provider configuration is invalid", exception);
        }
    }

    private List<String> readCapabilities(String capabilitiesJson) {
        try {
            return mapper.readValue(capabilitiesJson, STRINGS);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Stored AI provider capabilities are invalid", exception);
        }
    }
}
