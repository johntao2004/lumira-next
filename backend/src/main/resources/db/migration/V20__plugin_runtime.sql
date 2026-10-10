-- owner=platform
CREATE TABLE platform_plugin_artifact (
 digest varchar(64) PRIMARY KEY CHECK (digest ~ '^[a-f0-9]{64}$'),
 module_id varchar(100) NOT NULL, version varchar(64) NOT NULL, manifest jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(module_id,version)
);
CREATE TABLE platform_plugin_installation (
 module_id varchar(100) PRIMARY KEY, digest varchar(64) NOT NULL REFERENCES platform_plugin_artifact(digest),
 installed boolean NOT NULL DEFAULT false, explicit_install boolean NOT NULL DEFAULT false,
 updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform_plugin_operation (
 id uuid PRIMARY KEY, module_id varchar(100) NOT NULL, kind varchar(20) NOT NULL,
 status varchar(20) NOT NULL, step text NOT NULL, error_code text,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE platform_plugin_bootstrap (id integer PRIMARY KEY CHECK(id=1));
