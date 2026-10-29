#!/usr/bin/env python3
"""Regression guard: every `lateinit var` in MainActivity.kt must be
assigned somewhere in the file.

Why this exists: ClearVoice v1.0/v1.1 crashed instantly on launch
because `lateinit var lvRecordings` was never assigned in bindViews(),
yet setupListeners() used it during onCreate ->
UninitializedPropertyAccessException. The compiler cannot catch this
(lateinit is a runtime contract), so CI runs this check before every
build. Exit code 1 = a lateinit has no assignment = build blocked.
"""
import re
import sys

PATH = "app/src/main/java/com/hasan/clearvoice/MainActivity.kt"


def main() -> int:
    src = open(PATH).read()
    lateinits = re.findall(r"lateinit var (\w+)", src)
    missing = [
        name
        for name in lateinits
        if re.search(rf"^\s*{re.escape(name)}\s*=", src, re.M) is None
    ]
    for name in lateinits:
        print(f"lateinit {name}: {'OK' if name not in missing else 'NEVER ASSIGNED'}")
    if missing:
        print(f"FAIL: unassigned lateinit vars: {', '.join(missing)}")
        return 1
    print(f"PASS: all {len(lateinits)} lateinit vars are assigned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
