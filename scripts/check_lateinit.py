#!/usr/bin/env python3
"""Regression guard: every `lateinit var` in ANY Kotlin file of the app
must be assigned somewhere in the same file.

Why this exists: ClearVoice v1.0/v1.1 crashed instantly on launch
because `lateinit var lvRecordings` was never assigned in bindViews(),
yet setupListeners() used it during onCreate ->
UninitializedPropertyAccessException. The compiler cannot catch this
(lateinit is a runtime contract), so CI runs this check before every
build. Exit code 1 = a lateinit has no assignment = build blocked.

v2: the app has several activities now, so the scan covers every
*.kt file under the package, not just MainActivity.
"""
import glob
import re
import sys

PATTERN = "app/src/main/java/com/hasan/clearvoice/*.kt"


def main() -> int:
    paths = sorted(glob.glob(PATTERN))
    if not paths:
        print("FAIL: no Kotlin files found — wrong working directory?")
        return 1
    total = 0
    missing_all = []
    for path in paths:
        src = open(path).read()
        lateinits = re.findall(r"lateinit var (\w+)", src)
        missing = [
            name
            for name in lateinits
            if re.search(rf"^\s*{re.escape(name)}\s*=", src, re.M) is None
        ]
        for name in lateinits:
            status = "OK" if name not in missing else "NEVER ASSIGNED"
            print(f"{path.split('/')[-1]}: lateinit {name}: {status}")
            total += 1
            if name in missing:
                missing_all.append(f"{path}:{name}")
    if missing_all:
        print(f"FAIL: unassigned lateinit vars: {', '.join(missing_all)}")
        return 1
    print(f"PASS: all {total} lateinit vars across {len(paths)} files are assigned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
