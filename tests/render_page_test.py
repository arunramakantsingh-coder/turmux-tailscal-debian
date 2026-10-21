#!/usr/bin/env python3
"""Render the distribution page exactly like the publish workflow does and check the result.

The page template and the renderer live in the workflow, so this test extracts both and runs
them with sample values. It catches a template that leaves a placeholder behind, a renderer
that crashes, or a change summary that ends up empty.
"""
import os
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/pages.yml"
INDENT = " " * 10


def fail(message):
    sys.exit("FAIL: " + message)


def extract(pattern, text, what):
    match = re.search(pattern, text, re.S)
    if not match:
        fail("the %s was not found in the workflow" % what)
    body = match.group(1)
    return "\n".join(line[len(INDENT):] if line.startswith(INDENT) else line
                     for line in body.splitlines())


def main():
    if not WORKFLOW.exists():
        fail("the publish workflow is missing")
    text = WORKFLOW.read_text(encoding="utf-8").replace("\r\n", "\n")

    template = extract(r"cat > site/index\.html <<'HTML'\n(.*?)\n[ ]*HTML\n", text, "page template")
    renderer = extract(r"python3 - <<'PY'\n(.*?)\n[ ]*PY\n", text, "page renderer")

    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        (work / "site").mkdir()
        (work / "site" / "index.html").write_text(template + "\n", encoding="utf-8")
        changelog = ROOT / "CHANGELOG.md"
        if changelog.exists():
            (work / "CHANGELOG.md").write_text(changelog.read_text(encoding="utf-8"), encoding="utf-8")

        env = dict(os.environ)
        env.update({
            "VERSION": "0.0.0-test",
            "VERSION_CODE": "1",
            "BUILD_SHA": "deadbee",
            "BUILD_DATE": "2026-01-01T00:00Z",
            "APK_NAME": "android-development-appliance-v0.0.0-test.apk",
            "GITHUB_SHA": "0" * 40,
            "GITHUB_RUN_ID": "1",
        })
        result = subprocess.run([sys.executable, "-c", renderer], cwd=str(work), env=env,
                                capture_output=True, text=True)
        if result.returncode != 0:
            fail("the page renderer failed:\n" + result.stdout + result.stderr)

        html = (work / "site" / "index.html").read_text(encoding="utf-8")
        left = sorted(set(re.findall(r"__[A-Z_]+__", html)))
        if left:
            fail("unresolved placeholder(s) in the rendered page: " + ", ".join(left))
        if "0.0.0-test" not in html:
            fail("the rendered page does not contain the version")
        if "android-development-appliance-v0.0.0-test.apk" not in html:
            fail("the rendered page does not link the APK")
        summary = re.search(r"<pre>(.*?)</pre>", html, re.S)
        if not summary or not summary.group(1).strip():
            fail("the rendered page has an empty change summary")
        if "- - " in summary.group(1):
            fail("the rendered change summary has doubled bullet markers")

    print("PASS: the distribution page renders")


if __name__ == "__main__":
    main()
