#!/usr/bin/env python3
"""Generate a shields.io endpoint badge payload from the scoverage report.

Reads the statement rate from the root scoverage aggregate report produced by
`sbt coverageAggregate` and builds a shields.io endpoint JSON payload. When
GIST_ID and GIST_TOKEN are set, the payload is pushed to the gist file named by
GIST_FILENAME; otherwise it is written locally to `coverage.json`.
"""
import glob
import json
import os
import sys
import urllib.request
import xml.etree.ElementTree as ET

OUTPUT = "coverage.json"


def find_report() -> str:
    candidates = sorted(glob.glob("target/scala-*/scoverage-report/scoverage.xml"))
    if not candidates:
        candidates = sorted(glob.glob("**/scoverage-report/scoverage.xml", recursive=True))
    if not candidates:
        print("No scoverage report found", file=sys.stderr)
        sys.exit(1)
    return candidates[0]


def statement_rate(path: str) -> float:
    root = ET.parse(path).getroot()
    return float(root.attrib["statement-rate"])


def color_for(pct: float) -> str:
    if pct >= 90:
        return "brightgreen"
    if pct >= 80:
        return "green"
    if pct >= 70:
        return "yellowgreen"
    if pct >= 60:
        return "yellow"
    if pct >= 50:
        return "orange"
    return "red"


def render(pct: float) -> str:
    payload = {
        "schemaVersion": 1,
        "label": "coverage",
        "message": f"{pct:.0f}%",
        "color": color_for(pct),
    }
    return json.dumps(payload)


def publish_to_gist(content: str) -> None:
    gist_id = os.environ["GIST_ID"]
    token = os.environ["GIST_TOKEN"]
    filename = os.environ.get("GIST_FILENAME", OUTPUT)
    body = json.dumps({"files": {filename: {"content": content}}}).encode()
    req = urllib.request.Request(
        f"https://api.github.com/gists/{gist_id}",
        data=body,
        method="PATCH",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "coverage-badge",
        },
    )
    with urllib.request.urlopen(req) as resp:
        resp.read()
    print(f"Updated gist {gist_id} file {filename}")


def main() -> None:
    report = find_report()
    pct = statement_rate(report)
    content = render(pct)
    if os.environ.get("GIST_ID") and os.environ.get("GIST_TOKEN"):
        publish_to_gist(content)
    else:
        with open(OUTPUT, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"Wrote {OUTPUT} ({pct:.0f}% from {report})")


if __name__ == "__main__":
    main()
