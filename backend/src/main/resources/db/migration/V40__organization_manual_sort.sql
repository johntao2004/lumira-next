-- Existing organizations share sort number 0, preserving their established code order.
ALTER TABLE platform_organization
    ADD COLUMN sort_no integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT platform_organization_sort_no_check CHECK (sort_no >= 0);

CREATE INDEX platform_organization_sort_idx
    ON platform_organization (parent_id, sort_no, code, id);
