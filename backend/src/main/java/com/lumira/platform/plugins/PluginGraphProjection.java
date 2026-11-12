package com.lumira.platform.plugins;

import java.util.Collection;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Computes the existing graph fingerprint for a proposed change without writing metadata. */
final class PluginGraphProjection {
    private record Snapshot(String artifacts, String installations, String moduleStates) {}

    private PluginGraphProjection() {}

    static String project(
            JdbcClient jdbc,
            ObjectMapper mapper,
            Collection<PluginRuntime.Installation> installations,
            Map<String, Boolean> flags) {
        return jdbc.sql(
                        """
                WITH desired_installations AS (
                    SELECT * FROM jsonb_to_recordset(CAST(:installations AS jsonb))
                      AS d("moduleId" text,digest text,installed boolean,"explicitInstall" boolean)
                ), desired_flags AS (
                    SELECT key AS module_id,value::boolean AS enabled
                    FROM jsonb_each_text(CAST(:flags AS jsonb))
                ), next_installations AS (
                    SELECT i.module_id,i.digest,i.installed,i.explicit_install
                    FROM platform_plugin_installation i
                    WHERE NOT EXISTS(SELECT 1 FROM desired_installations d WHERE d."moduleId"=i.module_id)
                    UNION ALL
                    SELECT "moduleId",digest,installed,"explicitInstall" FROM desired_installations
                ), next_states AS (
                    SELECT s.module_id,s.enabled,s.version FROM platform_module_state s
                    WHERE NOT EXISTS(SELECT 1 FROM desired_flags d WHERE d.module_id=s.module_id)
                    UNION ALL
                    SELECT d.module_id,d.enabled,COALESCE(s.version,0)+1 FROM desired_flags d
                    LEFT JOIN platform_module_state s ON s.module_id=d.module_id
                )
                SELECT
                    (SELECT COALESCE(jsonb_agg(jsonb_build_array(module_id,version,digest)
                        ORDER BY module_id,version,digest),'[]'::jsonb)::text FROM platform_plugin_artifact) AS artifacts,
                    (SELECT COALESCE(jsonb_agg(jsonb_build_array(module_id,digest,installed,explicit_install)
                        ORDER BY module_id),'[]'::jsonb)::text FROM next_installations) AS installations,
                    (SELECT COALESCE(jsonb_agg(jsonb_build_array(module_id,enabled,version)
                        ORDER BY module_id),'[]'::jsonb)::text FROM next_states) AS module_states
                """)
                .param("installations", mapper.writeValueAsString(installations))
                .param("flags", mapper.writeValueAsString(flags))
                .query(
                        (rs, row) ->
                                PluginArchive.digest(
                                        mapper.writeValueAsBytes(
                                                new Snapshot(
                                                        rs.getString("artifacts"),
                                                        rs.getString("installations"),
                                                        rs.getString("module_states")))))
                .single();
    }
}
