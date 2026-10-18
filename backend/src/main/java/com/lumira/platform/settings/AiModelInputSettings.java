package com.lumira.platform.settings;

import com.lumira.platform.common.AiModelInputSettingsPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Provider-scoped typed settings, committed in the provider's optimistic-lock transaction. */
@Repository
public class AiModelInputSettings implements AiModelInputSettingsPort {
    private static final TypeReference<Map<String, List<String>>> TYPE = new TypeReference<>() {};
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AiModelInputSettings(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private String section(UUID id) {
        return "ai-model-input-" + id;
    }

    @Override
    public Map<String, List<String>> read(UUID providerId) {
        return jdbc.sql("SELECT payload::text FROM platform_setting WHERE section=:section")
                .param("section", section(providerId))
                .query(String.class)
                .optional()
                .map(json -> mapper.readValue(json, TYPE))
                .orElse(Map.of());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(UUID providerId, Map<String, List<String>> models, String actor) {
        jdbc.sql(
                        """
                INSERT INTO platform_setting(id,section,version,payload,updated_by)
                VALUES(:id,:section,1,CAST(:payload AS jsonb),:actor)
                ON CONFLICT(section) DO UPDATE SET payload=EXCLUDED.payload,
                    version=platform_setting.version+1,updated_by=EXCLUDED.updated_by,updated_at=now()
                """)
                .param("id", UUID.randomUUID())
                .param("section", section(providerId))
                .param("payload", mapper.writeValueAsString(models))
                .param("actor", actor)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(UUID providerId) {
        jdbc.sql("DELETE FROM platform_setting WHERE section=:section")
                .param("section", section(providerId))
                .update();
    }
}
