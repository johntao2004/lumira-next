"""Repeated real LibreOffice conversions against an explicitly owned, private QA worker only."""
import base64
import io
import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import time
import urllib.request
import zipfile

configured = os.environ.get("LUMIRA_OFFICE_REAPING_CONFIG")
assert configured, "Set LUMIRA_OFFICE_REAPING_CONFIG to an explicit private QA worker config"
path = Path(configured)
assert path.is_file() and not path.is_symlink() and stat.S_IMODE(path.stat().st_mode) & 0o077 == 0
config = json.loads(path.read_text())
assert re.fullmatch(r"lumira-office-(?:site|qa)-[a-f0-9]{10}", config["container"])
assert config["ingress"] == config["container"] + "-ingress"
assert isinstance(config["hostPort"], int) and 1024 <= config["hostPort"] <= 65535
iterations = int(os.environ.get("LUMIRA_OFFICE_REAPING_ITERATIONS", "20"))
assert 1 <= iterations <= 50
pid_limit = config.get("pidsLimit", 128)
assert pid_limit in {64, 128}
for identifier in [config["container"], config["ingress"]]:
    inspected = json.loads(subprocess.check_output(["docker", "inspect", identifier]))[0]
    assert inspected["Config"]["Labels"].get("com.lumira.qa.owner") == config["owner"]
    assert inspected["Config"]["Labels"].get("com.lumira.qa")
    if identifier == config["container"]:
        assert "LUMIRA_OFFICE_WORKER_TEST_MODE=true" in inspected["Config"]["Env"]
        assert inspected["HostConfig"]["PidsLimit"] == pid_limit
    else:
        bindings = inspected["HostConfig"]["PortBindings"][str(config["hostPort"]) + "/tcp"]
        assert bindings == [{"HostIp": "127.0.0.1", "HostPort": str(config["hostPort"])}]


def processes():
    output = subprocess.check_output(["docker", "exec", config["container"], "sh", "-c",
                                      "cat /sys/fs/cgroup/pids.current; ps -e -o stat="], text=True)
    lines = output.splitlines()
    return {"pids": int(lines[0]), "zombies": sum(line.strip().startswith("Z") for line in lines[1:])}


document = io.BytesIO()
with zipfile.ZipFile(document, "w", zipfile.ZIP_DEFLATED) as archive:
    archive.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    archive.writestr("_rels/.rels", '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    archive.writestr("word/document.xml", '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Owned QA worker reaping fixture</w:t></w:r></w:p><w:sectPr/></w:body></w:document>')
metadata = json.dumps({"bindings": {}, "values": {}}).encode()
body = b"LDO1" + struct.pack(">I", len(metadata)) + metadata + document.getvalue()
baseline = processes()
assert baseline["zombies"] == 0, "Start from a fresh explicitly owned QA worker"
for index in range(iterations):
    request = urllib.request.Request("http://127.0.0.1:" + str(config["hostPort"]) + "/v1/office", body, {
        "X-Lumira-Office-Token": config["token"],
        "X-Lumira-Office-Operation": "document-pdf",
        "X-Lumira-Office-Filename": base64.urlsafe_b64encode(b"reaping.docx").decode().rstrip("="),
        "Content-Type": "application/octet-stream",
    }, method="POST")
    with urllib.request.urlopen(request, timeout=40) as response:
        assert response.status == 200
        result = json.load(response)
    assert result["kind"] == "document-pdf"
    assert base64.b64decode(result["documentPdf"]).startswith(b"%PDF")
    deadline = time.monotonic() + 2
    while True:
        observed = processes()
        if observed["zombies"] == 0 or time.monotonic() >= deadline:
            break
        time.sleep(.1)
    print(json.dumps({"conversion": index + 1, **observed}), flush=True)
    assert observed["zombies"] == 0, "Orphaned helpers must be reaped after each real conversion"
    assert observed["pids"] < pid_limit, "The configured PID budget must remain enforced"
print(json.dumps({"passed": True, "realPdfConversions": iterations, "baseline": baseline, "final": processes()}))
