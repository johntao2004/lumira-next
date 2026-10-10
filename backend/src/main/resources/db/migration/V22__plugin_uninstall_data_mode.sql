-- owner=platform
ALTER TABLE platform_plugin_operation ADD COLUMN data_mode varchar(16) NOT NULL DEFAULT 'PRESERVE'
 CHECK (data_mode IN ('PRESERVE','DELETE'));
