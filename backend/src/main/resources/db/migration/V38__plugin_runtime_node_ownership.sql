-- owner=platform
-- Track per-process plugin graph readiness and give every in-flight lifecycle operation a node lease.
CREATE TABLE platform_plugin_runtime_node (
    node_id uuid PRIMARY KEY,
    started_at timestamptz NOT NULL DEFAULT now(),
    heartbeat_at timestamptz NOT NULL DEFAULT now(),
    ready boolean NOT NULL DEFAULT false,
    fixed_graph boolean NOT NULL DEFAULT false,
    graph_revision varchar(64) NOT NULL DEFAULT ''
);

CREATE INDEX platform_plugin_runtime_node_heartbeat_idx
    ON platform_plugin_runtime_node(heartbeat_at);

CREATE TABLE platform_plugin_graph_approval (
    id integer PRIMARY KEY CHECK (id = 1),
    graph_revision varchar(64) NOT NULL,
    approved_at timestamptz NOT NULL DEFAULT now(),
    approved_by varchar(100) NOT NULL
);

ALTER TABLE platform_plugin_operation
    ADD COLUMN owner_node_id uuid,
    ADD COLUMN lease_expires_at timestamptz;

-- Pre-lease operations cannot be resumed safely. Preserve recent work while marking abandoned
-- legacy queue entries interrupted after a conservative grace period.
UPDATE platform_plugin_operation
SET status = 'FAILED',
    error_code = 'pluginOperationInterrupted',
    step = 'legacy operation had no runtime owner lease',
    updated_at = now()
WHERE status IN ('QUEUED', 'RUNNING')
  AND owner_node_id IS NULL
  AND updated_at < now() - interval '5 minutes';

CREATE INDEX platform_plugin_operation_lease_idx
    ON platform_plugin_operation(lease_expires_at)
    WHERE status IN ('QUEUED', 'RUNNING');
