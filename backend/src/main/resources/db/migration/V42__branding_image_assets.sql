CREATE TABLE public.platform_branding_file (
    file_id uuid PRIMARY KEY REFERENCES public.platform_file(id) ON DELETE CASCADE,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    published_at timestamp with time zone
);

CREATE INDEX platform_branding_file_pending_idx
    ON public.platform_branding_file (created_at, file_id)
    WHERE published_at IS NULL;
