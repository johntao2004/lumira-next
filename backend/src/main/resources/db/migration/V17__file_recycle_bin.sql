ALTER TABLE public.platform_file
    ADD COLUMN trashed_at timestamptz;

ALTER TABLE public.platform_file
    DROP CONSTRAINT platform_file_state_check;

ALTER TABLE public.platform_file
    ADD CONSTRAINT platform_file_state_check
        CHECK (state IN ('UPLOADING', 'READY', 'TRASHED', 'DELETING'));

ALTER TABLE public.platform_file
    ADD CONSTRAINT platform_file_trash_timestamp_check
        CHECK ((state = 'TRASHED') = (trashed_at IS NOT NULL));

CREATE INDEX platform_file_owner_trash
    ON public.platform_file (owner_id, trashed_at DESC, id)
    WHERE state = 'TRASHED';

CREATE INDEX platform_file_trash_retention
    ON public.platform_file (trashed_at, id)
    WHERE state = 'TRASHED';
