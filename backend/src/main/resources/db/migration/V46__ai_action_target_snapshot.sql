ALTER TABLE public.platform_ai_pending_action
    ADD COLUMN target_username character varying(100),
    ADD COLUMN target_display_name character varying(200),
    ADD COLUMN target_enabled boolean;

ALTER TABLE public.platform_ai_pending_action
    ADD CONSTRAINT platform_ai_pending_action_target_snapshot_check
    CHECK (
        (target_username IS NULL AND target_display_name IS NULL AND target_enabled IS NULL)
        OR
        (target_username IS NOT NULL AND target_display_name IS NOT NULL AND target_enabled IS NOT NULL)
    );
