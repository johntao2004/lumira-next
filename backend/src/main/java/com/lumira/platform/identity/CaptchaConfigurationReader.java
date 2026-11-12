package com.lumira.platform.identity;

import com.lumira.platform.common.SecretKeys;
import com.lumira.platform.identity.api.CaptchaConfigurationPort;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/** Kernel challenge policy and local asset readiness continue without the editor. */
@Service
public class CaptchaConfigurationReader implements CaptchaConfigurationPort {
    private final JdbcClient jdbc;
    private final SecretKeys keys;
    private final LocalCaptchaAssets assets;
    private final ObjectMapper mapper;
    private final JavaType actionsMapType;

    public CaptchaConfigurationReader(
            JdbcClient jdbc, SecretKeys keys, LocalCaptchaAssets assets, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.assets = assets;
        this.mapper = mapper;
        this.actionsMapType =
                mapper.getTypeFactory().constructMapType(Map.class, String.class, Mode.class);
    }

    @Override
    @Transactional(readOnly = true)
    public Snapshot current() {
        return read(false);
    }

    @Override
    @Transactional
    public Snapshot lockCurrent() {
        return read(true);
    }

    @Override
    public boolean encryptionConfigured() {
        return keys.configured();
    }

    @Override
    public Readiness readiness(Data data) {
        boolean accessible = keys.configured() && assets.audioAvailable();
        boolean configured =
                data != null
                        && data.enabled()
                        && keys.configured()
                        && assets.typeAvailable(data.type())
                        && accessible;
        return new Readiness(configured, accessible);
    }

    private Snapshot read(boolean lock) {
        return jdbc.sql(
                        "SELECT version,revision,enabled,challenge_type,actions::text AS actions FROM platform_captcha_settings WHERE id=1"
                                + (lock ? " FOR UPDATE" : ""))
                .query(
                        (rs, row) ->
                                new Snapshot(
                                        rs.getLong("version"),
                                        rs.getObject("revision", UUID.class),
                                        new Data(
                                                rs.getBoolean("enabled"),
                                                "LOCAL",
                                                rs.getString("challenge_type"),
                                                mapper.readValue(
                                                        rs.getString("actions"), actionsMapType))))
                .single();
    }
}
