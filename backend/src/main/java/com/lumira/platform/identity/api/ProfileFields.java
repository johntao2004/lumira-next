package com.lumira.platform.identity.api;

import java.util.*;

/**
 * Versioned identity profile schema. Keys are stable identifiers, never labels or executable
 * expressions.
 */
public record ProfileFields(List<Field> fields) {
    /** Domain validation failure; HTTP adapters decide how to expose it. */
    public static final class Invalid extends IllegalArgumentException {
        public Invalid() {
            super("invalidProfileFields");
        }
    }

    /** A placement is a registered host and tab, never a route or executable template. */
    public record Placement(String page, String tab, Integer sortNo) {}

    public record Field(
            String key,
            String label,
            String description,
            String type,
            Boolean visible,
            Boolean required,
            Integer weight,
            String placeholder,
            String groupKey,
            String groupLabel,
            Integer sortNo,
            Boolean custom,
            List<String> options,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String dictionaryCode,
            Boolean publicRegistrationAllowed,
            List<Placement> placements) {
        public Field(
                String key,
                String label,
                String description,
                String type,
                Boolean visible,
                Boolean required,
                Integer weight,
                String placeholder,
                String groupKey,
                String groupLabel,
                Integer sortNo,
                Boolean custom,
                List<String> options) {
            this(
                    key,
                    label,
                    description,
                    type,
                    visible,
                    required,
                    weight,
                    placeholder,
                    groupKey,
                    groupLabel,
                    sortNo,
                    custom,
                    options,
                    null,
                    false,
                    null);
        }

        /** Backward-compatible constructor for fields configured with dictionary support. */
        public Field(
                String key,
                String label,
                String description,
                String type,
                Boolean visible,
                Boolean required,
                Integer weight,
                String placeholder,
                String groupKey,
                String groupLabel,
                Integer sortNo,
                Boolean custom,
                List<String> options,
                String dictionaryCode) {
            this(
                    key,
                    label,
                    description,
                    type,
                    visible,
                    required,
                    weight,
                    placeholder,
                    groupKey,
                    groupLabel,
                    sortNo,
                    custom,
                    options,
                    dictionaryCode,
                    false,
                    null);
        }

        public boolean usesDictionary() {
            return dictionaryCode != null && !dictionaryCode.isEmpty();
        }

        /** Missing placement arrays are legacy schemas; retain their old account-page order. */
        public List<Placement> placementsOrLegacy() {
            if (placements != null) return placements;
            if (Boolean.TRUE.equals(custom))
                return List.of(new Placement("account-profile", "custom", sortNo));
            if ("displayName".equals(key))
                return List.of(
                        new Placement("account-profile", "basic", sortNo),
                        new Placement("admin-user-editor", "profile", sortNo));
            if (BUILT_INS.containsKey(key))
                return List.of(new Placement("account-profile", "basic", sortNo));
            return List.of();
        }

        public boolean isPlacedAt(String page, String tab) {
            return placementsOrLegacy().stream()
                    .anyMatch(
                            placement ->
                                    placement.page().equals(page) && placement.tab().equals(tab));
        }

        public int placementSortNo(String page, String tab) {
            return placementsOrLegacy().stream()
                    .filter(
                            placement ->
                                    placement.page().equals(page) && placement.tab().equals(tab))
                    .map(Placement::sortNo)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(sortNo);
        }

        public Field withResolvedPlacement() {
            if (placements != null && publicRegistrationAllowed != null) return this;
            return new Field(
                    key,
                    label,
                    description,
                    type,
                    visible,
                    required,
                    weight,
                    placeholder,
                    groupKey,
                    groupLabel,
                    sortNo,
                    custom,
                    options,
                    dictionaryCode,
                    Boolean.TRUE.equals(publicRegistrationAllowed),
                    placementsOrLegacy());
        }

        /** Return host-only metadata without exposing assignments to other hosts. */
        public Field projectedForHost(String page) {
            var hostPlacements =
                    placementsOrLegacy().stream()
                            .filter(placement -> placement.page().equals(page))
                            .toList();
            return new Field(
                    key,
                    label,
                    description,
                    type,
                    visible,
                    required,
                    weight,
                    placeholder,
                    groupKey,
                    groupLabel,
                    sortNo,
                    custom,
                    options,
                    dictionaryCode,
                    false,
                    hostPlacements);
        }
    }

    /** Return the exact placement allow-list supported by the current application. */
    public static boolean registeredTarget(String page, String tab) {
        return switch (page == null ? "" : page) {
            case "account-profile" ->
                    "basic".equals(tab) || "details".equals(tab) || "custom".equals(tab);
            case "admin-user-editor" -> "profile".equals(tab);
            case "public-registration" -> "additional".equals(tab);
            default -> false;
        };
    }

    public ProfileFields withResolvedPlacements() {
        if (fields == null) return this;
        return new ProfileFields(
                fields.stream()
                        .map(field -> field == null ? null : field.withResolvedPlacement())
                        .toList());
    }

    /** Project visible fields assigned to a host, stripping placements for every other host. */
    public ProfileFields forHost(String page) {
        if (!registeredPage(page) || fields == null) return new ProfileFields(List.of());
        return new ProfileFields(
                fields.stream()
                        .filter(field -> field != null && Boolean.TRUE.equals(field.visible()))
                        .filter(
                                field ->
                                        field.placementsOrLegacy().stream()
                                                .anyMatch(
                                                        placement -> placement.page().equals(page)))
                        .map(field -> field.projectedForHost(page))
                        .toList());
    }

    private static boolean registeredPage(String page) {
        return switch (page == null ? "" : page) {
            case "account-profile", "admin-user-editor", "public-registration" -> true;
            default -> false;
        };
    }

    private static final Map<String, String> BUILT_INS =
            Map.of(
                    "displayName", "TEXT",
                    "realName", "TEXT",
                    "birthDate", "DATE",
                    "region", "TEXT",
                    "avatarUrl", "IMAGE");
    private static final Set<String> RESERVED =
            Set.of(
                    "id",
                    "userid",
                    "user_id",
                    "accountid",
                    "account_id",
                    "sessionid",
                    "session_id",
                    "username",
                    "authentication",
                    "authorization",
                    "password",
                    "passwordhash",
                    "password_hash",
                    "authorities",
                    "globalauthorities",
                    "version",
                    "enabled",
                    "email",
                    "emailaddress",
                    "mobile",
                    "phone",
                    "phonenumber",
                    "nickname",
                    "displayname",
                    "realname",
                    "birthdate",
                    "avatarurl",
                    "roleids",
                    "rolecodes",
                    "organizationids",
                    "permissions",
                    "permissioncodes",
                    "mfa",
                    "totp",
                    "webauthn",
                    "passkeys",
                    "otp",
                    "verificationcode",
                    "recoverycodes",
                    "emailverified",
                    "phoneverified",
                    "__proto__",
                    "prototype",
                    "constructor",
                    "tostring",
                    "valueof",
                    "hasownproperty",
                    "isprototypeof",
                    "propertyisenumerable",
                    "tolocalestring",
                    "__definegetter__",
                    "__definesetter__",
                    "__lookupgetter__",
                    "__lookupsetter__");

    public Optional<Field> avatar() {
        return fields.stream().filter(f -> f.key().equals("avatarUrl") && !f.custom()).findFirst();
    }

    public static ProfileFields defaults() {
        return new ProfileFields(
                List.of(
                        builtIn("displayName", "Display name", "TEXT", true, 10),
                        builtIn("realName", "Real name", "TEXT", false, 20),
                        builtIn("birthDate", "Birth date", "DATE", false, 30),
                        builtIn("region", "Region", "TEXT", false, 40),
                        builtIn("avatarUrl", "Avatar", "IMAGE", false, 50)));
    }

    /** Supply the default image field for schemas saved before it became a default. */
    public ProfileFields withDefaultAvatar() {
        if (avatar().isPresent() || fields.size() >= 100) return this;
        var items = new ArrayList<>(fields);
        String groupLabel =
                fields.stream()
                        .filter(f -> f.groupKey().equals("basic"))
                        .map(Field::groupLabel)
                        .findFirst()
                        .orElse("Basic information");
        items.add(
                new Field(
                        "avatarUrl",
                        "Avatar",
                        "",
                        "IMAGE",
                        true,
                        false,
                        5,
                        "",
                        "basic",
                        groupLabel,
                        50,
                        false,
                        List.of()));
        return new ProfileFields(List.copyOf(items));
    }

    public ProfileFields sorted() {
        return new ProfileFields(
                fields.stream()
                        .sorted(Comparator.comparingInt(Field::sortNo).thenComparing(Field::key))
                        .toList());
    }

    private static Field builtIn(
            String key, String label, String type, boolean required, int sort) {
        return new Field(
                key,
                label,
                "",
                type,
                true,
                required,
                5,
                "",
                "basic",
                "Basic information",
                sort,
                false,
                List.of());
    }

    private static boolean text(String value, int max) {
        return value != null && value.length() <= max;
    }

    private static void invalid() {
        throw new Invalid();
    }

    public void validate() {
        if (fields == null || fields.size() > 100) {
            invalid();
            return;
        }
        Set<String> keys = new HashSet<>();
        Map<String, String> groups = new HashMap<>();
        for (Field field : fields) {
            if (field == null
                    || field.key() == null
                    || !field.key().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !keys.add(field.key())
                    || Boolean.TRUE.equals(field.custom())
                            && RESERVED.contains(field.key().toLowerCase(Locale.ROOT))
                    || !text(field.label(), 64)
                    || field.label().isBlank()
                    || !text(field.description(), 200)
                    || !text(field.placeholder(), 120)
                    || field.type() == null
                    || !Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "IMAGE")
                            .contains(field.type())
                    || field.visible() == null
                    || field.required() == null
                    || field.custom() == null
                    || field.weight() == null
                    || field.weight() < 1
                    || field.weight() > 100
                    || field.sortNo() == null
                    || field.sortNo() < 0
                    || field.sortNo() > 100000
                    || field.groupKey() == null
                    || !field.groupKey().matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                    || !text(field.groupLabel(), 64)
                    || field.groupLabel().isBlank()
                    || field.options() == null
                    || field.options().size() > 200
                    || field.publicRegistrationAllowed() == null
                    || field.placements() != null && field.placements().size() > 3) {
                invalid();
                return;
            }
            String group = groups.putIfAbsent(field.groupKey(), field.groupLabel());
            if (group != null && !group.equals(field.groupLabel())) {
                invalid();
            }
            if (BUILT_INS.containsKey(field.key())) {
                if (field.custom() || !BUILT_INS.get(field.key()).equals(field.type())) invalid();
            } else if (!field.custom() || field.type().equals("IMAGE")) {
                invalid();
            }
            if (field.key().equals("displayName") && (!field.visible() || !field.required()))
                invalid();
            if (field.key().equals("displayName")
                    && (!field.isPlacedAt("account-profile", "basic")
                            || !field.isPlacedAt("admin-user-editor", "profile"))) invalid();
            if (field.placements() != null) {
                Set<String> placedPages = new HashSet<>();
                for (Placement placement : field.placements()) {
                    if (placement == null
                            || !registeredTarget(placement.page(), placement.tab())
                            || placement.sortNo() == null
                            || placement.sortNo() < 0
                            || placement.sortNo() > 100000
                            || !placedPages.add(placement.page())) invalid();
                    if ("public-registration".equals(placement.page()) && !field.custom())
                        invalid();
                    if ("admin-user-editor".equals(placement.page())
                            && !field.custom()
                            && !"displayName".equals(field.key())) invalid();
                }
            }
            boolean registrationPlacement =
                    field.placementsOrLegacy().stream()
                            .anyMatch(p -> "public-registration".equals(p.page()));
            if (Boolean.TRUE.equals(field.publicRegistrationAllowed()) && !field.custom())
                invalid();
            if (registrationPlacement && !field.custom()) invalid();
            if (field.custom()
                    && field.required()
                    && field.visible()
                    && field.placementsOrLegacy().stream()
                            .noneMatch(p -> !"public-registration".equals(p.page()))) invalid();
            if (field.usesDictionary()) {
                if (!field.custom()
                        || !field.type().equals("SELECT")
                        || !field.dictionaryCode().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{2,99}")
                        || !field.options().isEmpty()) invalid();
            } else if (field.type().equals("SELECT")) {
                if (field.options().isEmpty()) invalid();
                Set<String> options = new HashSet<>();
                for (String option : field.options()) {
                    if (!text(option, 200)
                            || option.isBlank()
                            || !option.equals(option.strip())
                            || !options.add(option)) invalid();
                }
            } else if (!field.options().isEmpty()) {
                invalid();
            }
        }
        if (!keys.containsAll(Set.of("displayName", "realName", "birthDate", "region"))) invalid();
        long publishedRegistrationFields =
                fields.stream()
                        .filter(
                                field ->
                                        field.custom()
                                                && field.visible()
                                                && Boolean.TRUE.equals(
                                                        field.publicRegistrationAllowed())
                                                && field.isPlacedAt(
                                                        "public-registration", "additional"))
                        .count();
        if (publishedRegistrationFields > 20) invalid();
    }
}
