CREATE TABLE plugin_cluster_runtime_operation (
    id uuid PRIMARY KEY,
    owner_node_id uuid NOT NULL,
    owner_token uuid NOT NULL,
    owner_epoch bigint NOT NULL DEFAULT 1 CHECK (owner_epoch > 0),
    lease_expires_at timestamptz NOT NULL,
    phase text NOT NULL CHECK (phase IN ('PREPARING','DRAINING','STAGING','COMMITTED','COMPLETED','ABORTING','ABORTED')),
    phase_started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    failure_code text,
    base_revision text NOT NULL CHECK (length(base_revision)=64),
    target jsonb NOT NULL CHECK (jsonb_typeof(target)='object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- One graph mutation at a time, including post-commit roll-forward and abort recovery.
CREATE UNIQUE INDEX plugin_cluster_runtime_active ON plugin_cluster_runtime_operation ((true))
    WHERE phase NOT IN ('COMPLETED','ABORTED');
CREATE TABLE plugin_cluster_runtime_participant (
    operation_id uuid NOT NULL REFERENCES plugin_cluster_runtime_operation(id),
    node_id uuid NOT NULL,
    owner_epoch bigint NOT NULL CHECK (owner_epoch > 0),
    acknowledged_phase text CHECK (acknowledged_phase IN ('PREPARING','DRAINING','STAGING','COMMITTED','COMPLETED','ABORTING','ABORTED')),
    observed_revision text,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(operation_id,node_id)
);

CREATE FUNCTION plugin_cluster_runtime_renew(op uuid, owner uuid, token uuid, epoch bigint)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_node_id=owner
      AND owner_token=token AND owner_epoch=epoch FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    -- Recheck wall time after acquiring the row lock; a blocked renew cannot revive a lease.
    UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()+interval '45 seconds',updated_at=clock_timestamp()
      WHERE id=op AND owner_node_id=owner AND owner_token=token AND owner_epoch=epoch
        AND lease_expires_at>clock_timestamp() AND phase NOT IN ('COMPLETED','ABORTED');
    RETURN FOUND;
END
$$;

CREATE FUNCTION plugin_cluster_runtime_claim(op uuid, owner uuid, token uuid)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE claimed bigint;
BEGIN
    UPDATE plugin_cluster_runtime_operation SET owner_node_id=owner,owner_token=token,
      owner_epoch=owner_epoch+1,lease_expires_at=clock_timestamp()+interval '45 seconds',updated_at=clock_timestamp()
    WHERE id=op AND lease_expires_at<=clock_timestamp() AND phase NOT IN ('COMPLETED','ABORTED')
    RETURNING owner_epoch INTO claimed;
    IF claimed IS NULL THEN RETURN NULL; END IF;
    -- Prior owner acknowledgements cannot authorize a new owner's commit.
    UPDATE plugin_cluster_runtime_participant SET owner_epoch=claimed,acknowledged_phase=NULL,
      observed_revision=NULL,updated_at=clock_timestamp() WHERE operation_id=op;
    RETURN claimed;
END $$;

CREATE FUNCTION plugin_cluster_runtime_ack(op uuid, node uuid, epoch bigint, expected_phase text, revision text)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    -- Serialize ACK and phase advancement on the same operation row.
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_epoch=epoch
      AND phase=expected_phase AND lease_expires_at>clock_timestamp() FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    UPDATE plugin_cluster_runtime_participant SET acknowledged_phase=expected_phase,
      observed_revision=revision,updated_at=clock_timestamp()
      WHERE operation_id=op AND node_id=node AND owner_epoch=epoch
        AND EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation WHERE id=op AND lease_expires_at>clock_timestamp());
    RETURN FOUND;
END $$;

CREATE FUNCTION plugin_cluster_runtime_advance(op uuid, owner uuid, token uuid, epoch bigint, expected_phase text, next_phase text)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    IF NOT ((expected_phase,next_phase) IN (
      ('PREPARING','DRAINING'),('DRAINING','STAGING'),('STAGING','COMMITTED'),
      ('COMMITTED','COMPLETED'),('PREPARING','ABORTING'),('DRAINING','ABORTING'),
      ('STAGING','ABORTING'),('ABORTING','ABORTED'))) THEN
      RAISE EXCEPTION 'invalid cluster phase transition' USING ERRCODE='22023';
    END IF;
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_node_id=owner
      AND owner_token=token AND owner_epoch=epoch AND phase=expected_phase
      AND lease_expires_at>clock_timestamp() FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    -- A failed participant cannot ACK a successful stage. Owner-fenced abort is a decision,
    -- not a successful-stage quorum. Cleanup ACKs are still required before ABORTED.
    IF next_phase<>'ABORTING' AND EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant WHERE operation_id=op
      AND (owner_epoch<>epoch OR acknowledged_phase IS DISTINCT FROM expected_phase)) THEN RETURN false; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant WHERE operation_id=op) THEN RETURN false; END IF;
    UPDATE plugin_cluster_runtime_operation SET phase=next_phase,phase_started_at=clock_timestamp(),updated_at=clock_timestamp()
      WHERE id=op AND lease_expires_at>clock_timestamp();
    RETURN FOUND;
END $$;

CREATE TABLE plugin_cluster_runtime_activation (
    id boolean PRIMARY KEY CHECK (id),
    protocol_version integer NOT NULL CHECK (protocol_version=1),
    active boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO plugin_cluster_runtime_activation(id,protocol_version) VALUES(true,1);

CREATE TABLE plugin_cluster_runtime_node_capability (
    node_id uuid PRIMARY KEY,
    protocol_version integer NOT NULL CHECK (protocol_version IN (0,1)),
    admitted boolean NOT NULL DEFAULT false,
    observed_heartbeat timestamptz NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE FUNCTION plugin_cluster_runtime_node_admission()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE enabled boolean; declared integer;
BEGIN
    -- Registration, heartbeat and removal serialize against activation's membership snapshot.
    SELECT active INTO STRICT enabled FROM plugin_cluster_runtime_activation WHERE id FOR SHARE;
    IF TG_OP='DELETE' THEN
        DELETE FROM plugin_cluster_runtime_node_capability WHERE node_id=OLD.node_id;
        RETURN OLD;
    END IF;
    declared := CASE WHEN current_setting('lumira.cluster_protocol',true)='1' THEN 1 ELSE 0 END;
    IF enabled AND declared<>1 THEN
        RAISE EXCEPTION 'pluginClusterProtocolUnsupported' USING ERRCODE='55000';
    END IF;
    IF (NEW.ready OR EXISTS(SELECT 1 FROM plugin_cluster_runtime_node_capability
      WHERE node_id=NEW.node_id AND admitted)) AND EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation o
      WHERE o.phase NOT IN ('COMPLETED','ABORTED') AND NOT EXISTS(
        SELECT 1 FROM plugin_cluster_runtime_participant p
        WHERE p.operation_id=o.id AND p.node_id=NEW.node_id)) THEN
        RAISE EXCEPTION 'pluginClusterJoinPending' USING ERRCODE='55000';
    END IF;
    INSERT INTO plugin_cluster_runtime_node_capability(node_id,protocol_version,observed_heartbeat,admitted)
      VALUES(NEW.node_id,declared,NEW.heartbeat_at,NEW.ready)
      ON CONFLICT(node_id) DO UPDATE SET protocol_version=EXCLUDED.protocol_version,
        observed_heartbeat=EXCLUDED.observed_heartbeat,observed_at=clock_timestamp(),admitted=plugin_cluster_runtime_node_capability.admitted OR EXCLUDED.admitted;
    RETURN NEW;
END $$;

CREATE TRIGGER plugin_cluster_runtime_node_admission
BEFORE INSERT OR UPDATE OR DELETE ON platform_plugin_runtime_node
FOR EACH ROW EXECUTE FUNCTION plugin_cluster_runtime_node_admission();

CREATE FUNCTION plugin_cluster_runtime_activate(expected_revision text)
RETURNS uuid[] LANGUAGE plpgsql AS $$
DECLARE participants uuid[];
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_activation WHERE id FOR UPDATE;
    IF expected_revision IS NULL OR expected_revision !~ '^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION 'invalidClusterRevision' USING ERRCODE='22023';
    END IF;
    IF EXISTS(
        SELECT 1 FROM platform_plugin_runtime_node n
        LEFT JOIN plugin_cluster_runtime_node_capability c USING(node_id)
        WHERE n.heartbeat_at>clock_timestamp()-interval '30 seconds'
          AND (NOT n.ready OR n.fixed_graph OR n.graph_revision IS DISTINCT FROM expected_revision
            OR c.protocol_version IS DISTINCT FROM 1
            OR c.observed_heartbeat IS DISTINCT FROM n.heartbeat_at)
    ) THEN
        RAISE EXCEPTION 'pluginClusterNodesNotPrepared' USING ERRCODE='55000';
    END IF;
    SELECT array_agg(node_id ORDER BY node_id) INTO participants
      FROM platform_plugin_runtime_node WHERE heartbeat_at>clock_timestamp()-interval '30 seconds';
    IF participants IS NULL THEN
        RAISE EXCEPTION 'pluginClusterNodesNotPrepared' USING ERRCODE='55000';
    END IF;
    UPDATE plugin_cluster_runtime_activation SET active=true,updated_at=clock_timestamp() WHERE id;
    RETURN participants;
END $$;

CREATE FUNCTION plugin_cluster_runtime_deactivate()
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_activation WHERE id FOR UPDATE;
    IF EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation WHERE phase NOT IN ('COMPLETED','ABORTED')) THEN
        RETURN false;
    END IF;
    UPDATE plugin_cluster_runtime_activation SET active=false,updated_at=clock_timestamp() WHERE id;
    RETURN true;
END $$;

CREATE FUNCTION plugin_cluster_runtime_veto(op uuid, node uuid, epoch bigint, error text)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    IF error IS NULL OR length(error)<1 OR length(error)>128 THEN
        RAISE EXCEPTION 'invalid cluster error code' USING ERRCODE='22023';
    END IF;
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_epoch=epoch
      AND phase IN ('PREPARING','DRAINING','STAGING') FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant
      WHERE operation_id=op AND node_id=node AND owner_epoch=epoch) THEN RETURN false; END IF;
    -- A participant can veto preparation, never reverse a committed decision.
    UPDATE plugin_cluster_runtime_operation SET phase='ABORTING',
      failure_code=COALESCE(failure_code,error),phase_started_at=clock_timestamp(),updated_at=clock_timestamp()
      WHERE id=op AND owner_epoch=epoch AND phase IN ('PREPARING','DRAINING','STAGING');
    RETURN FOUND;
END $$;

ALTER TABLE plugin_cluster_runtime_participant
    ADD COLUMN retired_at timestamptz,
    ADD COLUMN retired_epoch bigint,
    ADD COLUMN recovery boolean NOT NULL DEFAULT false,
    ADD COLUMN execution_fenced boolean NOT NULL DEFAULT false;
ALTER TABLE plugin_cluster_runtime_node_capability
    DROP CONSTRAINT plugin_cluster_runtime_node_capability_protocol_version_check,
    ADD CHECK(protocol_version IN (0,1,2));
ALTER TABLE plugin_cluster_runtime_activation
    DROP CONSTRAINT plugin_cluster_runtime_activation_protocol_version_check;
UPDATE plugin_cluster_runtime_activation SET protocol_version=2;
ALTER TABLE plugin_cluster_runtime_activation ADD CHECK(protocol_version=2);
CREATE TABLE plugin_cluster_runtime_node_revocation (
    node_id uuid PRIMARY KEY,
    operation_id uuid NOT NULL REFERENCES plugin_cluster_runtime_operation(id),
    owner_epoch bigint NOT NULL,
    retired_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- IEEE CRC32, exactly the signed key used by PluginTransactionFence (UTF-8 input).
CREATE FUNCTION plugin_cluster_runtime_fence_key(value text)
RETURNS integer LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE bytes bytea:=convert_to(value,'UTF8'); crc bigint:=4294967295; i integer; j integer;
BEGIN
    FOR i IN 0..length(bytes)-1 LOOP
        crc:=crc # get_byte(bytes,i);
        FOR j IN 1..8 LOOP
            IF (crc & 1)=1 THEN crc:=(crc >> 1) # 3988292384; ELSE crc:=crc >> 1; END IF;
        END LOOP;
    END LOOP;
    crc:=crc # 4294967295;
    IF crc>2147483647 THEN crc:=crc-4294967296; END IF;
    RETURN crc::integer;
END $$;

-- The callback holds this shared lock through ACK/rollback, including independent migrations.
-- Retirement cannot declare an in-flight callback dead just because its heartbeat stops.
CREATE FUNCTION plugin_cluster_runtime_enter(op uuid, node uuid, epoch bigint, expected_phase text)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_try_advisory_xact_lock_shared(1279480389,plugin_cluster_runtime_fence_key(node::text)) THEN RETURN false; END IF;
    RETURN EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation o
        JOIN plugin_cluster_runtime_participant p ON p.operation_id=o.id
        JOIN platform_plugin_runtime_node n ON n.node_id=p.node_id
        WHERE o.id=op AND o.owner_epoch=epoch AND o.phase=expected_phase
          AND p.node_id=node AND p.retired_at IS NULL AND p.owner_epoch=epoch
          AND n.heartbeat_at>clock_timestamp()-interval '30 seconds'
          AND NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_node_revocation r WHERE r.node_id=node));
END $$;

CREATE OR REPLACE FUNCTION plugin_cluster_runtime_node_admission()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE enabled boolean; declared integer;
BEGIN
    SELECT active INTO STRICT enabled FROM plugin_cluster_runtime_activation WHERE id FOR SHARE;
    IF TG_OP='DELETE' THEN
        DELETE FROM plugin_cluster_runtime_node_capability WHERE node_id=OLD.node_id;
        RETURN OLD;
    END IF;
    IF EXISTS(SELECT 1 FROM plugin_cluster_runtime_node_revocation WHERE node_id=NEW.node_id) THEN
        RAISE EXCEPTION 'pluginClusterNodeRetired' USING ERRCODE='55000';
    END IF;
    declared := CASE current_setting('lumira.cluster_protocol',true) WHEN '2' THEN 2 WHEN '1' THEN 1 ELSE 0 END;
    IF enabled AND declared<>2 THEN
        RAISE EXCEPTION 'pluginClusterProtocolUnsupported' USING ERRCODE='55000';
    END IF;
    IF (NEW.ready OR EXISTS(SELECT 1 FROM plugin_cluster_runtime_node_capability
      WHERE node_id=NEW.node_id AND admitted)) AND EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation o
      WHERE o.phase NOT IN ('COMPLETED','ABORTED') AND NOT EXISTS(
        SELECT 1 FROM plugin_cluster_runtime_participant p
        WHERE p.operation_id=o.id AND p.node_id=NEW.node_id AND p.retired_at IS NULL)) THEN
        RAISE EXCEPTION 'pluginClusterJoinPending' USING ERRCODE='55000';
    END IF;
    INSERT INTO plugin_cluster_runtime_node_capability(node_id,protocol_version,observed_heartbeat,admitted)
      VALUES(NEW.node_id,declared,NEW.heartbeat_at,NEW.ready)
      ON CONFLICT(node_id) DO UPDATE SET protocol_version=EXCLUDED.protocol_version,
        observed_heartbeat=EXCLUDED.observed_heartbeat,observed_at=clock_timestamp(),
        admitted=plugin_cluster_runtime_node_capability.admitted OR EXCLUDED.admitted;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION plugin_cluster_runtime_activate(expected_revision text)
RETURNS uuid[] LANGUAGE plpgsql AS $$
DECLARE participants uuid[];
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_activation WHERE id FOR UPDATE;
    IF expected_revision IS NULL OR expected_revision !~ '^[a-f0-9]{64}$' THEN
        RAISE EXCEPTION 'invalidClusterRevision' USING ERRCODE='22023';
    END IF;
    IF EXISTS(SELECT 1 FROM platform_plugin_runtime_node n
      LEFT JOIN plugin_cluster_runtime_node_capability c USING(node_id)
      WHERE n.heartbeat_at>clock_timestamp()-interval '30 seconds'
        AND (NOT n.ready OR n.fixed_graph OR n.graph_revision IS DISTINCT FROM expected_revision
          OR c.protocol_version IS DISTINCT FROM 2 OR c.observed_heartbeat IS DISTINCT FROM n.heartbeat_at)) THEN
        RAISE EXCEPTION 'pluginClusterNodesNotPrepared' USING ERRCODE='55000';
    END IF;
    SELECT array_agg(node_id ORDER BY node_id) INTO participants FROM platform_plugin_runtime_node
      WHERE heartbeat_at>clock_timestamp()-interval '30 seconds';
    IF participants IS NULL THEN RAISE EXCEPTION 'pluginClusterNodesNotPrepared' USING ERRCODE='55000'; END IF;
    UPDATE plugin_cluster_runtime_activation SET active=true,updated_at=clock_timestamp() WHERE id;
    RETURN participants;
END $$;

CREATE OR REPLACE FUNCTION plugin_cluster_runtime_claim(op uuid, owner uuid, token uuid)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE claimed bigint;
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant p
      JOIN platform_plugin_runtime_node n ON n.node_id=p.node_id
      WHERE p.operation_id=op AND p.node_id=owner AND p.retired_at IS NULL
        AND n.heartbeat_at>clock_timestamp()-interval '30 seconds') THEN RETURN NULL; END IF;
    UPDATE plugin_cluster_runtime_operation SET owner_node_id=owner,owner_token=token,
      owner_epoch=owner_epoch+1,lease_expires_at=clock_timestamp()+interval '45 seconds',updated_at=clock_timestamp()
      WHERE id=op AND lease_expires_at<=clock_timestamp() AND phase NOT IN ('COMPLETED','ABORTED')
      RETURNING owner_epoch INTO claimed;
    IF claimed IS NULL THEN RETURN NULL; END IF;
    UPDATE plugin_cluster_runtime_participant SET owner_epoch=claimed,acknowledged_phase=NULL,
      observed_revision=NULL,updated_at=clock_timestamp() WHERE operation_id=op AND retired_at IS NULL;
    RETURN claimed;
END $$;

-- An unadmitted cold node may replace a lost owner. It cannot continue precommit staging.
CREATE FUNCTION plugin_cluster_runtime_recover(op uuid, node uuid, token uuid)
RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE claimed bigint;
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_activation WHERE id FOR SHARE;
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation WHERE id=op
      AND lease_expires_at<=clock_timestamp() AND phase NOT IN ('COMPLETED','ABORTED')) THEN RETURN NULL; END IF;
    IF EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant WHERE operation_id=op AND NOT execution_fenced) THEN RETURN NULL; END IF;
    PERFORM 1 FROM platform_plugin_runtime_node n JOIN plugin_cluster_runtime_node_capability c USING(node_id)
      WHERE n.node_id=node AND NOT n.ready AND NOT n.fixed_graph AND NOT c.admitted
        AND c.protocol_version=2 AND c.observed_heartbeat=n.heartbeat_at
        AND n.heartbeat_at>clock_timestamp()-interval '30 seconds'
        AND NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_node_revocation WHERE node_id=node)
      FOR UPDATE OF n;
    IF NOT FOUND THEN RETURN NULL; END IF;
    IF EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant WHERE operation_id=op AND node_id=node) THEN RETURN NULL; END IF;
    INSERT INTO plugin_cluster_runtime_participant(operation_id,node_id,owner_epoch,recovery,execution_fenced)
      SELECT op,node,owner_epoch,true,true FROM plugin_cluster_runtime_operation WHERE id=op;
    claimed:=plugin_cluster_runtime_claim(op,node,token);
    IF claimed IS NULL THEN RAISE EXCEPTION 'clusterRecoveryClaimLost' USING ERRCODE='40001'; END IF;
    UPDATE plugin_cluster_runtime_operation SET phase='ABORTING',
      failure_code=COALESCE(failure_code,'pluginOperationInterrupted'),phase_started_at=clock_timestamp()
      WHERE id=op AND phase IN ('PREPARING','DRAINING','STAGING');
    RETURN claimed;
END $$;

CREATE FUNCTION plugin_cluster_runtime_retire(op uuid, owner uuid, token uuid, epoch bigint, departed uuid)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE module text; heartbeat timestamptz;
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_activation WHERE id FOR SHARE;
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op FOR UPDATE;
    IF NOT FOUND OR owner=departed THEN RETURN false; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_node_id=owner
      AND owner_token=token AND owner_epoch=epoch AND lease_expires_at>clock_timestamp()
      AND phase IN ('ABORTING','COMMITTED')) THEN RETURN false; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant
      WHERE operation_id=op AND node_id=departed AND retired_at IS NULL AND execution_fenced) THEN RETURN false; END IF;
    IF NOT pg_try_advisory_xact_lock(1279480389,plugin_cluster_runtime_fence_key(departed::text)) THEN RETURN false; END IF;
    SELECT heartbeat_at INTO heartbeat FROM platform_plugin_runtime_node WHERE node_id=departed FOR UPDATE;
    IF heartbeat>clock_timestamp()-interval '30 seconds' THEN RETURN false; END IF;
    -- ALL installed modules, not just changed ones: an expired peer may still own unrelated SQL.
    FOR module IN
      SELECT module_id FROM platform_plugin_installation WHERE installed
      UNION SELECT jsonb_array_elements_text(target->'install') FROM plugin_cluster_runtime_operation WHERE id=op
      UNION SELECT jsonb_array_elements_text(target->'enable') FROM plugin_cluster_runtime_operation WHERE id=op
      UNION SELECT jsonb_array_elements_text(target->'remove') FROM plugin_cluster_runtime_operation WHERE id=op
      UNION SELECT target->>'moduleId' FROM plugin_cluster_runtime_operation WHERE id=op
      ORDER BY 1
    LOOP
        IF NOT pg_try_advisory_xact_lock(1280330823,plugin_cluster_runtime_fence_key(module)) THEN RETURN false; END IF;
    END LOOP;
    -- No mutation until both physical SQL and callbacks are proven quiescent under retained locks.
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation WHERE id=op AND lease_expires_at>clock_timestamp()) THEN RETURN false; END IF;
    INSERT INTO plugin_cluster_runtime_node_revocation(node_id,operation_id,owner_epoch)
      VALUES(departed,op,epoch);
    DELETE FROM platform_plugin_runtime_node WHERE node_id=departed;
    UPDATE plugin_cluster_runtime_participant SET retired_at=clock_timestamp(),retired_epoch=epoch,
      updated_at=clock_timestamp() WHERE operation_id=op AND node_id=departed;
    RETURN true;
END $$;

CREATE OR REPLACE FUNCTION plugin_cluster_runtime_ack(op uuid, node uuid, epoch bigint, expected_phase text, revision text)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_epoch=epoch
      AND phase=expected_phase FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    UPDATE plugin_cluster_runtime_participant SET acknowledged_phase=expected_phase,
      observed_revision=revision,updated_at=clock_timestamp()
      WHERE operation_id=op AND node_id=node AND owner_epoch=epoch AND retired_at IS NULL
        AND EXISTS(SELECT 1 FROM plugin_cluster_runtime_operation WHERE id=op AND lease_expires_at>clock_timestamp());
    RETURN FOUND;
END $$;

CREATE OR REPLACE FUNCTION plugin_cluster_runtime_advance(op uuid, owner uuid, token uuid, epoch bigint, expected_phase text, next_phase text)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    IF NOT ((expected_phase,next_phase) IN (
      ('PREPARING','DRAINING'),('DRAINING','STAGING'),('STAGING','COMMITTED'),
      ('COMMITTED','COMPLETED'),('PREPARING','ABORTING'),('DRAINING','ABORTING'),
      ('STAGING','ABORTING'),('ABORTING','ABORTED'))) THEN
      RAISE EXCEPTION 'invalid cluster phase transition' USING ERRCODE='22023';
    END IF;
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_node_id=owner
      AND owner_token=token AND owner_epoch=epoch AND phase=expected_phase FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    IF next_phase<>'ABORTING' AND EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant WHERE operation_id=op
      AND retired_at IS NULL AND (owner_epoch<>epoch OR acknowledged_phase IS DISTINCT FROM expected_phase)) THEN RETURN false; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant WHERE operation_id=op AND retired_at IS NULL) THEN RETURN false; END IF;
    UPDATE plugin_cluster_runtime_operation SET phase=next_phase,phase_started_at=clock_timestamp(),updated_at=clock_timestamp()
      WHERE id=op AND lease_expires_at>clock_timestamp();
    RETURN FOUND;
END $$;

CREATE OR REPLACE FUNCTION plugin_cluster_runtime_veto(op uuid, node uuid, epoch bigint, error text)
RETURNS boolean LANGUAGE plpgsql AS $$
BEGIN
    IF error IS NULL OR length(error)<1 OR length(error)>128 THEN
        RAISE EXCEPTION 'invalid cluster error code' USING ERRCODE='22023';
    END IF;
    PERFORM 1 FROM plugin_cluster_runtime_operation WHERE id=op AND owner_epoch=epoch
      AND phase IN ('PREPARING','DRAINING','STAGING') FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    IF NOT EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant
      WHERE operation_id=op AND node_id=node AND owner_epoch=epoch AND retired_at IS NULL) THEN RETURN false; END IF;
    UPDATE plugin_cluster_runtime_operation SET phase='ABORTING',
      failure_code=COALESCE(failure_code,error),phase_started_at=clock_timestamp(),updated_at=clock_timestamp()
      WHERE id=op AND owner_epoch=epoch AND phase IN ('PREPARING','DRAINING','STAGING');
    RETURN FOUND;
END $$;
