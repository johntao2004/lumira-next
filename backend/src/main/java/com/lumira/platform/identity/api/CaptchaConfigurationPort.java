package com.lumira.platform.identity.api;

import com.lumira.platform.common.CaptchaProofPort;
import java.util.*;

/** Local challenge configuration, independent of the optional administration workflow. */
public interface CaptchaConfigurationPort {
    final class Invalid extends IllegalArgumentException {
        public Invalid() {
            super("invalidCaptchaSettings");
        }
    }

    public enum Mode {
        OFF,
        RISK,
        ALWAYS
    }

    public record Data(boolean enabled, String provider, String type, Map<String, Mode> actions) {
        public Data {
            actions = Map.copyOf(actions == null ? Map.of() : actions);
        }

        public static Data defaults() {
            var actions = new java.util.LinkedHashMap<String, Mode>();
            for (var action : CaptchaProofPort.Action.values())
                actions.put(action.name(), Mode.OFF);
            return new Data(false, "LOCAL", "IMAGE_TEXT", actions);
        }

        public Map<CaptchaProofPort.Action, Mode> parsedActions() {
            var result = new EnumMap<CaptchaProofPort.Action, Mode>(CaptchaProofPort.Action.class);
            for (var action : CaptchaProofPort.Action.values()) {
                Mode mode = actions.get(action.name());
                result.put(action, mode == null ? Mode.OFF : mode);
            }
            return Map.copyOf(result);
        }

        public void validate() {
            if (!"LOCAL".equals(provider)
                    || !("IMAGE_TEXT".equals(type) || "SLIDER".equals(type))
                    || actions.size() > CaptchaProofPort.Action.values().length
                    || actions.keySet().stream()
                            .anyMatch(
                                    key -> {
                                        try {
                                            CaptchaProofPort.Action.valueOf(key);
                                            return false;
                                        } catch (RuntimeException error) {
                                            return true;
                                        }
                                    })
                    || actions.values().stream().anyMatch(java.util.Objects::isNull))
                throw new Invalid();
            boolean required = parsedActions().values().stream().anyMatch(mode -> mode != Mode.OFF);
            if (!enabled && required) throw new Invalid();
        }
    }

    public record Readiness(boolean configured, boolean accessibleAlternativeAvailable) {}

    public record Snapshot(long version, UUID revision, Data data) {}

    Snapshot current();

    Snapshot lockCurrent();

    Readiness readiness(Data data);

    boolean encryptionConfigured();

    default Readiness readiness() {
        return readiness(current().data());
    }

    default boolean readyFor(Data data) {
        return readiness(data).configured();
    }
}
