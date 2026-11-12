"""Real child-container consumer registration, drain, uninstall, and backlog recovery.

Uses the shared explicit QA config/database guard. Requires built plugins and JDK 25.
"""
import importlib.util
import time
import uuid
from pathlib import Path

spec = importlib.util.spec_from_file_location("plugin_smoke", Path(__file__).with_name("plugins-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
plugin = "qa-plugin-events"
source = "qa.plugin.events"
run = str(uuid.uuid4())


def wait(check, message, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.1)
    raise AssertionError(message)


def emit(key):
    event = str(uuid.uuid4())
    smoke.sql("INSERT INTO platform_event_outbox(id,source_type,event_type,event_key,payload) VALUES('%s','%s','created','%s','{}')" % (event, source, run + '/' + key))
    return event


def delivered(event):
    return smoke.sql("SELECT status FROM platform_event_outbox WHERE id='%s'" % event) == "DELIVERED"


try:
    topology = smoke.jsoncall('/platform/monitoring/summary')['flow']
    smoke.imports(smoke.fixture(plugin, events=True))
    smoke.operate(plugin, "install")
    published = smoke.jsoncall('/platform/monitoring/summary')['flow']
    for field in ['producerTypes', 'consumerTypes', 'connectedTypes']:
        assert published[field] == topology[field] + 1, (field, topology, published)
    baseline = int(smoke.sql("SELECT count(*) FROM plugin_qa_plugin_events_effect"))
    started = time.monotonic()
    events = [emit("batch-" + str(i)) for i in range(100)]
    wait(lambda: smoke.sql("SELECT count(*) FROM platform_event_outbox WHERE source_type='%s' AND event_key LIKE '%s/%%' AND status<>'DELIVERED'" % (source, run)) == "0", "Installed child consumers did not drain the backlog")
    assert int(smoke.sql("SELECT count(*) FROM plugin_qa_plugin_events_effect")) == baseline + 100
    print("PASS: dynamically loaded consumer delivered 100 events in %.2f seconds including SQL fixture creation" % (time.monotonic() - started), flush=True)
    slow = emit("slow")
    wait(lambda: smoke.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND state='active' AND query='SELECT pg_sleep(1.8)'") != "0", "Slow consumer never acquired a runtime lease")
    smoke.operate(plugin, "uninstall")
    assert delivered(slow), "Uninstall returned before transaction and acknowledgement completed"
    revoked = smoke.jsoncall('/platform/monitoring/summary')['flow']
    for field in ['producerTypes', 'consumerTypes', 'connectedTypes']:
        assert revoked[field] == topology[field], (field, topology, revoked)
    pending = emit("uninstalled")
    wait(lambda: smoke.sql("SELECT last_error FROM platform_event_outbox WHERE id='%s'" % pending) == "NO_CONSUMER", "Uninstalled consumer remained registered")
    assert int(smoke.sql("SELECT count(*) FROM plugin_qa_plugin_events_effect")) == baseline + 101
    assert smoke.sql("SELECT attempts FROM platform_event_outbox WHERE id='%s'" % pending) == "0"
    smoke.operate(plugin, "install")
    # Expedite the standard five-minute no-consumer retry for this isolated fixture only.
    smoke.sql("UPDATE platform_event_outbox SET available_at=now() WHERE id='%s' AND status='PENDING'" % pending)
    wait(lambda: delivered(pending), "Reinstalled consumer did not recover its retained event")
    assert int(smoke.sql("SELECT count(*) FROM plugin_qa_plugin_events_effect")) == baseline + 102
    print("PASS: active consumer drained through commit; uninstall revoked consumer; reinstall retained data and recovered backlog exactly once", flush=True)
finally:
    smoke.wait_for_readiness()
    row = next((row for row in smoke.jsoncall(smoke.P) if row['id'] == plugin), None)
    if row and row['installed']:
        smoke.operate(plugin, "uninstall")
