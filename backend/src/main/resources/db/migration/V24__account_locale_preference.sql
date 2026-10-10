-- NULL keeps the existing browser preference until the account chooses a language.
ALTER TABLE public.platform_user ADD COLUMN preferred_locale varchar(63);
ALTER TABLE public.platform_user ADD CONSTRAINT platform_user_preferred_locale_check
    CHECK (preferred_locale IS NULL OR preferred_locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$');
