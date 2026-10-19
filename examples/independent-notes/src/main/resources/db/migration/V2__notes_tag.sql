-- 首个增量示例保留原记录；不改 V1 校验和。 First incremental example preserves existing rows without changing V1 checksums.
ALTER TABLE notes_entry ADD COLUMN tag varchar(40) NOT NULL DEFAULT '';
