-- 应用自有基线；与宿主 B52 无关。 Application-owned baseline, independent of host B52.
CREATE TABLE notes_entry (id uuid PRIMARY KEY, text varchar(200) NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE notes_audit (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor text NOT NULL, action text NOT NULL, target_id uuid NOT NULL);
