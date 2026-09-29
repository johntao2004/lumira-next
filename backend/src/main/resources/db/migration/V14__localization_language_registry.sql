CREATE TABLE public.platform_localization_locale (
    locale_code character varying(63) NOT NULL,
    display_name character varying(100) NOT NULL,
    source_locale character varying(63) NOT NULL,
    fallback_locale character varying(63) NOT NULL,
    built_in boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    created_by character varying(100) NOT NULL,
    updated_by character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_localization_locale_pk PRIMARY KEY (locale_code),
    CONSTRAINT platform_localization_locale_code_check CHECK (locale_code ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CONSTRAINT platform_localization_locale_name_check CHECK (length(btrim(display_name)) BETWEEN 1 AND 100),
    CONSTRAINT platform_localization_locale_source_fk FOREIGN KEY (source_locale)
        REFERENCES public.platform_localization_locale(locale_code) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT platform_localization_locale_fallback_fk FOREIGN KEY (fallback_locale)
        REFERENCES public.platform_localization_locale(locale_code) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT platform_localization_locale_version_check CHECK (version > 0),
    CONSTRAINT platform_localization_locale_created_by_check CHECK (length(btrim(created_by)) > 0),
    CONSTRAINT platform_localization_locale_updated_by_check CHECK (length(btrim(updated_by)) > 0)
);

CREATE INDEX platform_localization_locale_list_idx
    ON public.platform_localization_locale (enabled, built_in, display_name, locale_code);

ALTER TABLE public.platform_localization_release
    ALTER COLUMN locale_code TYPE character varying(63);
ALTER TABLE public.platform_localization_release
    DROP CONSTRAINT platform_localization_release_locale_code_check;
ALTER TABLE public.platform_localization_release
    ADD CONSTRAINT platform_localization_release_locale_code_check
    CHECK (locale_code ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$');

INSERT INTO public.platform_localization_locale
    (locale_code, display_name, source_locale, fallback_locale, built_in, created_by, updated_by)
VALUES
    ('zh-CN', '简体中文', 'en', 'en', true, 'system', 'system'),
    ('zh-TW', '繁體中文', 'en', 'en', true, 'system', 'system'),
    ('en', 'English', 'en', 'zh-CN', true, 'system', 'system'),
    ('ja', '日本語', 'en', 'en', true, 'system', 'system'),
    ('ko', '한국어', 'en', 'en', true, 'system', 'system'),
    ('fr', 'Français', 'en', 'en', true, 'system', 'system'),
    ('es', 'Español', 'en', 'en', true, 'system', 'system'),
    ('de', 'Deutsch', 'en', 'en', true, 'system', 'system');
