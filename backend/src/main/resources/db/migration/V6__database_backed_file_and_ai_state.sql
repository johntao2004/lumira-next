ALTER TABLE public.platform_file
    ADD COLUMN legacy_local_cleanup_pending boolean DEFAULT false NOT NULL;

CREATE TABLE public.platform_file_blob_chunk (
    file_id uuid NOT NULL,
    chunk_index integer NOT NULL,
    payload bytea NOT NULL,
    retire_after timestamp with time zone,
    CONSTRAINT platform_file_blob_chunk_pkey PRIMARY KEY (file_id, chunk_index),
    CONSTRAINT platform_file_blob_chunk_index_check CHECK (chunk_index >= 0),
    CONSTRAINT platform_file_blob_chunk_payload_check CHECK (octet_length(payload) BETWEEN 1 AND 65536)
);

CREATE INDEX platform_file_blob_chunk_retirement_idx
    ON public.platform_file_blob_chunk USING btree (retire_after, file_id, chunk_index)
    WHERE retire_after IS NOT NULL;

CREATE TABLE public.platform_ai_pending_action (
    id uuid NOT NULL,
    actor character varying(100) NOT NULL,
    kind character varying(32) NOT NULL,
    user_id uuid,
    version bigint DEFAULT 0 NOT NULL,
    username character varying(100),
    display_name character varying(200),
    enabled boolean,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT platform_ai_pending_action_pkey PRIMARY KEY (id),
    CONSTRAINT platform_ai_pending_action_kind_check CHECK (((kind)::text = ANY ((ARRAY['CREATE_USER'::character varying, 'UPDATE_USER'::character varying, 'DELETE_USER'::character varying])::text[]))),
    CONSTRAINT platform_ai_pending_action_version_check CHECK ((version >= 0))
);

CREATE INDEX platform_ai_pending_action_expiry_idx
    ON public.platform_ai_pending_action USING btree (expires_at);

CREATE TABLE public.platform_sensitive_word_revision (
    singleton boolean NOT NULL,
    revision bigint DEFAULT 0 NOT NULL,
    CONSTRAINT platform_sensitive_word_revision_pkey PRIMARY KEY (singleton),
    CONSTRAINT platform_sensitive_word_revision_singleton_check CHECK (singleton),
    CONSTRAINT platform_sensitive_word_revision_value_check CHECK (revision >= 0)
);

INSERT INTO public.platform_sensitive_word_revision (singleton, revision) VALUES (true, 0);

CREATE FUNCTION public.platform_bump_sensitive_word_revision() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE public.platform_sensitive_word_revision
       SET revision = revision + 1
     WHERE singleton = true;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER platform_sensitive_word_revision_trigger
    AFTER INSERT OR UPDATE OR DELETE ON public.platform_sensitive_word
    FOR EACH ROW EXECUTE FUNCTION public.platform_bump_sensitive_word_revision();

CREATE TRIGGER platform_sensitive_word_revision_truncate_trigger
    AFTER TRUNCATE ON public.platform_sensitive_word
    FOR EACH STATEMENT EXECUTE FUNCTION public.platform_bump_sensitive_word_revision();
