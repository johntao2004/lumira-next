"""Bounded local HTTP load and hotplug isolation on an explicitly guarded QA database.

This records a closed-loop baseline, not a production capacity/SLO certification.
"""
import concurrent.futures
import importlib.util
import json
import math
import os
import subprocess
import tempfile
import threading
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location("plugin_smoke", Path(__file__).with_name("plugins-smoke.py"))
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
owners = ["qa-plugin-load", "qa-plugin-neighbor"]


def loader_count():
    pid = os.environ.get("LUMIRA_SMOKE_BACKEND_PID")
    if not pid:
        return None
    smoke.runtime_identity()  # Validates ownership and target database before attaching.
    command = [str(smoke.JAVA / "jcmd"), pid]
    subprocess.run(command + ["GC.run"], check=True, capture_output=True, timeout=30)
    result = subprocess.run(command + ["VM.classloader_stats"], check=True,
                            capture_output=True, text=True, timeout=30)
    count = sum("com.lumira.platform.plugins.PluginRuntime$" in line for line in result.stdout.splitlines())
    assert count > 0, "Runtime plugin classloaders were not identified"
    return count


def load(owner, concurrency, count=512):
    local = threading.local()

    def request(_):
        if not hasattr(local, "client"):
            local.client = smoke.client()
        started = time.monotonic()
        status, body = smoke.call(local.client, "/plugins/" + owner + "/status")
        assert status == 200, (owner, status, body[:256])
        assert json.loads(body)["code"] == "new-runtime-code"
        return (time.monotonic() - started) * 1000

    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        samples = sorted(pool.map(request, range(count)))
    elapsed = time.monotonic() - started
    return dict(concurrency=concurrency, requests=count, errors=0,
                requestsPerSecond=round(count / elapsed, 2),
                p95Ms=round(samples[math.ceil(count * .95) - 1], 2),
                p99Ms=round(samples[math.ceil(count * .99) - 1], 2))


def overload(owner):
    if os.environ.get("LUMIRA_ADMISSION_SMOKE") != "1":
        print("SKIP: explicit bounded admission burst is not enabled", flush=True)
        return
    assert smoke.config.get("LUMIRA_HTTP_MAX_IN_FLIGHT") == "64", "Admission burst requires the dedicated 64-slot QA configuration"
    barrier = threading.Barrier(96, timeout=20)

    def request(_):
        caller = smoke.client()
        barrier.wait()
        status, body = smoke.call(caller, "/plugins/" + owner + "/status?delay=750")
        if status == 503:
            assert json.loads(body).get("code") == "serverBusy", body[:256]
        else:
            assert status == 200, (status, body[:256])
        return status

    with concurrent.futures.ThreadPoolExecutor(max_workers=96) as pool:
        statuses = list(pool.map(request, range(192)))
    assert 200 in statuses and 503 in statuses, statuses
    load(owner, 8, 32)
    print("PASS: bounded admission burst accepted %d and explicitly rejected %d requests; normal service recovered" % (statuses.count(200), statuses.count(503)), flush=True)
    smoke.runtime_identity()
    burst = subprocess.run(["node", str(Path(__file__).with_name("plugin-burst-load.mjs")),
                            smoke.config["LUMIRA_BACKEND_BASE_URL"].rstrip("/") + "/api/plugins/" + owner + "/status"],
                           capture_output=True, text=True, timeout=180)
    assert burst.returncode == 0, (burst.stdout[:4096], burst.stderr[:512])
    result = json.loads(burst.stdout)
    assert result["peakInFlight"] == 1000 and result["unexpected"] == 0, result
    print("THOUSAND_CONCURRENT_PROBES: " + json.dumps(result), flush=True)
    load(owner, 8, 32)
    print("PASS: 1000 concurrent synthetic probes completed 10000 requests with bounded overload and recovery", flush=True)


try:
    original_pid = smoke.runtime_identity()
    original_loaders = loader_count()
    for owner in owners:
        smoke.imports(smoke.fixture(owner))
        smoke.operate(owner, "install")
        # Exercise exception resolution too: it maintains a separate controller-class cache.
        assert smoke.call(smoke.client(), "/plugins/" + owner + "/status?delay=invalid")[0] == 400
    documentation = smoke.jsoncall("/platform/api-docs")
    assert "/api/plugins/qa-plugin-load/status" in documentation["paths"], "Dynamic API is missing from generated documentation"
    load(owners[0], 4, 32)
    for concurrency in [8, 32, 64]:
        print("BASELINE: " + json.dumps(load(owners[0], concurrency)), flush=True)
    overload(owners[0])
    # Load an unrelated plugin throughout repeated removal/reinstallation.
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(load, owners[1], 32, 1024)
        for _ in range(3):
            smoke.operate(owners[0], "uninstall")
            smoke.operate(owners[0], "install")
        print("NEIGHBOR_DURING_HOTPLUG: " + json.dumps(pending.result()), flush=True)
    assert smoke.runtime_identity() == original_pid
    print("PASS: bounded HTTP baseline and three hotplug cycles preserve unrelated plugin responses and backend identity", flush=True)
finally:
    smoke.wait_for_readiness()
    for owner in owners:
        current = next((row for row in smoke.jsoncall(smoke.P) if row['id'] == owner), None)
        if current and current['installed']:
            smoke.operate(owner, "uninstall")

if original_loaders is not None:
    # Reference processing and class unloading can span collections. Await the same strict
    # postcondition for a bounded interval; persistent cache roots must still fail the suite.
    deadline = time.monotonic() + 15
    observations = []
    while True:
        remaining_loaders = loader_count()
        classes = subprocess.run([str(smoke.JAVA / "jcmd"), os.environ["LUMIRA_SMOKE_BACKEND_PID"],
                                  "VM.classloaders", "show-classes=true", "fold=false"],
                                 check=True, capture_output=True, text=True, timeout=30)
        retained = [owner for owner in owners if "com.lumira.plugins." + owner.replace("-", "_") + "." in classes.stdout]
        observations.append({"loaders": remaining_loaders, "retainedFixtures": retained})
        if (remaining_loaders <= original_loaders and not retained) or time.monotonic() >= deadline:
            break
        time.sleep(.5)
    print("CLASSLOADER_RECLAMATION: " + json.dumps(observations), flush=True)
    if remaining_loaders > original_loaders or retained:
        # Capture only this explicitly PID/database-bound QA JVM, never an existing local service.
        diagnostic = Path(tempfile.mkdtemp(prefix="plugin-reclamation-", dir=smoke.WORK)) / "heap.hprof"
        subprocess.run([str(smoke.JAVA / "jcmd"), os.environ["LUMIRA_SMOKE_BACKEND_PID"],
                        "GC.heap_dump", str(diagnostic)], check=True, capture_output=True, timeout=60)
        print("Classloader failure evidence retained in private QA work directory", flush=True)
    assert remaining_loaders <= original_loaders, (original_loaders, remaining_loaders)
    assert not retained, ("Uninstalled fixture classes remain loaded", retained)
    print("PASS: runtime classloaders after GC returned from %d to %d after fixture cleanup" % (original_loaders, remaining_loaders), flush=True)
else:
    print("SKIP: classloader reclamation requires an explicitly bound local Linux backend PID", flush=True)
