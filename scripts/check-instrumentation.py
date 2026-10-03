"""Reject silent adb/am success when AndroidJUnitRunner reported a test failure."""
import re
import sys
from pathlib import Path

report = Path(sys.argv[1]).read_text()
if re.search(r"(?:FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[1-9])", report):
    raise SystemExit("Instrumentation failed; inspect the saved runner report")
result = re.search(r"OK \((\d+) tests?\)", report)
if result is None or int(result.group(1)) < 1:
    raise SystemExit("Runner did not confirm at least one successful test")
