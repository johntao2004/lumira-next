-- Legacy challenges predate requirement-version checks and cannot prove which
-- requirement instance issued them. Give them unrelated versions so they fail closed.
UPDATE public.platform_login_challenge
SET requirement_version = gen_random_uuid();
