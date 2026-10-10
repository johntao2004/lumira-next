ALTER TABLE public.platform_second_factor_requirement
    ADD COLUMN requirement_version uuid NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE public.platform_login_challenge
    ADD COLUMN requirement_version uuid;

UPDATE public.platform_login_challenge AS challenge
SET requirement_version = requirement.requirement_version
FROM public.platform_second_factor_requirement AS requirement
WHERE challenge.user_id = requirement.user_id
  AND challenge.provider_id = requirement.provider_id;

UPDATE public.platform_login_challenge
SET requirement_version = gen_random_uuid()
WHERE requirement_version IS NULL;

ALTER TABLE public.platform_login_challenge
    ALTER COLUMN requirement_version SET NOT NULL;
