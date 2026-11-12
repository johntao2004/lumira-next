DO $$
DECLARE
  op uuid := gen_random_uuid(); owner uuid := gen_random_uuid(); peer uuid := gen_random_uuid();
  token uuid := gen_random_uuid(); replacement uuid := gen_random_uuid(); epoch bigint;
BEGIN
  INSERT INTO plugin_cluster_runtime_operation(id,owner_node_id,owner_token,lease_expires_at,phase,base_revision,target)
    VALUES(op,owner,token,clock_timestamp()+interval '45 seconds','PREPARING',repeat('a',64),'{}');
  INSERT INTO plugin_cluster_runtime_participant(operation_id,node_id,owner_epoch) VALUES(op,owner,1),(op,peer,1);
  IF plugin_cluster_runtime_advance(op,owner,token,1,'PREPARING','DRAINING') THEN RAISE EXCEPTION 'advanced without acknowledgements'; END IF;
  IF NOT plugin_cluster_runtime_ack(op,owner,1,'PREPARING','base') THEN RAISE EXCEPTION 'owner ack rejected'; END IF;
  IF plugin_cluster_runtime_advance(op,owner,token,1,'PREPARING','DRAINING') THEN RAISE EXCEPTION 'missing peer ignored'; END IF;
  IF plugin_cluster_runtime_ack(op,gen_random_uuid(),1,'PREPARING','base') THEN RAISE EXCEPTION 'unknown participant accepted'; END IF;
  IF NOT plugin_cluster_runtime_ack(op,peer,1,'PREPARING','base') OR NOT plugin_cluster_runtime_ack(op,peer,1,'PREPARING','base') THEN RAISE EXCEPTION 'ack not idempotent'; END IF;
  IF NOT plugin_cluster_runtime_advance(op,owner,token,1,'PREPARING','DRAINING') THEN RAISE EXCEPTION 'complete preparation rejected'; END IF;
  IF plugin_cluster_runtime_claim(op,peer,replacement) IS NOT NULL THEN RAISE EXCEPTION 'live owner stolen'; END IF;
  UPDATE plugin_cluster_runtime_operation SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE id=op;
  IF plugin_cluster_runtime_renew(op,owner,token,1) THEN RAISE EXCEPTION 'expired owner revived'; END IF;
  epoch := plugin_cluster_runtime_claim(op,peer,replacement);
  IF epoch IS DISTINCT FROM 2 THEN RAISE EXCEPTION 'takeover did not increment fencing epoch'; END IF;
  IF EXISTS(SELECT 1 FROM plugin_cluster_runtime_participant WHERE operation_id=op AND acknowledged_phase IS NOT NULL) THEN RAISE EXCEPTION 'stale acknowledgements survived takeover'; END IF;
  IF plugin_cluster_runtime_ack(op,owner,1,'DRAINING','base') THEN RAISE EXCEPTION 'stale epoch acknowledged'; END IF;
  IF plugin_cluster_runtime_advance(op,owner,token,1,'DRAINING','STAGING') THEN RAISE EXCEPTION 'stale owner advanced'; END IF;
  PERFORM plugin_cluster_runtime_ack(op,owner,2,'DRAINING','base');
  PERFORM plugin_cluster_runtime_ack(op,peer,2,'DRAINING','base');
  IF NOT plugin_cluster_runtime_advance(op,peer,replacement,2,'DRAINING','STAGING') THEN RAISE EXCEPTION 'drain rejected'; END IF;
  PERFORM plugin_cluster_runtime_ack(op,owner,2,'STAGING','target');
  PERFORM plugin_cluster_runtime_ack(op,peer,2,'STAGING','target');
  BEGIN
    IF NOT plugin_cluster_runtime_advance(op,peer,replacement,2,'STAGING','COMMITTED') THEN RAISE EXCEPTION 'staging rejected'; END IF;
    RAISE EXCEPTION 'injected metadata transaction failure' USING ERRCODE='P0002';
  EXCEPTION WHEN no_data_found THEN NULL;
  END;
  IF (SELECT phase FROM plugin_cluster_runtime_operation WHERE id=op)<>'STAGING' THEN RAISE EXCEPTION 'journal commit escaped metadata rollback'; END IF;
  IF NOT plugin_cluster_runtime_advance(op,peer,replacement,2,'STAGING','COMMITTED') THEN RAISE EXCEPTION 'commit retry rejected'; END IF;
  BEGIN
    PERFORM plugin_cluster_runtime_advance(op,peer,replacement,2,'COMMITTED','ABORTING');
    RAISE EXCEPTION 'committed decision reversed';
  EXCEPTION WHEN invalid_parameter_value THEN NULL;
  END;
  PERFORM plugin_cluster_runtime_ack(op,owner,2,'COMMITTED','target');
  PERFORM plugin_cluster_runtime_ack(op,peer,2,'COMMITTED','target');
  IF NOT plugin_cluster_runtime_advance(op,peer,replacement,2,'COMMITTED','COMPLETED') THEN RAISE EXCEPTION 'completion rejected'; END IF;
  IF plugin_cluster_runtime_renew(op,peer,replacement,2) THEN RAISE EXCEPTION 'terminal operation renewed'; END IF;
END $$;
