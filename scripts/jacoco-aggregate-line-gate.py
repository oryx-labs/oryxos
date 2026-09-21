#!/usr/bin/env python3
"""Fail if JaCoCo aggregate LINE COVEREDRATIO is below the configured minimum.

Reads oryxos-boot/target/site/jacoco-aggregate/jacoco.xml (report-aggregate output).
Usage:
  python3 scripts/jacoco-aggregate-line-gate.py \
    --xml oryxos-boot/target/site/jacoco-aggregate/jacoco.xml \
    --minimum 0.65
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--xml", required=True, type=Path)
    parser.add_argument("--minimum", required=True, type=float)
    args = parser.parse_args()

    if not args.xml.is_file():
        print(f"JaCoCo aggregate report missing: {args.xml}", file=sys.stderr)
        return 1

    text = args.xml.read_text(encoding="utf-8")
    counters = re.findall(
        r'<counter type="LINE" missed="(\d+)" covered="(\d+)"/>', text
    )
    if not counters:
        print("Could not parse LINE counters from aggregate jacoco.xml", file=sys.stderr)
        return 1

    # Report-level totals are the last LINE counter in the document.
    missed, covered = map(int, counters[-1])
    total = missed + covered
    ratio = 1.0 if total == 0 else covered / total
    pct = ratio * 100.0
    print(
        f"JaCoCo aggregate LINE coverage: {pct:.2f}% ({covered}/{total}), "
        f"minimum={args.minimum}"
    )
    if ratio + 1e-12 < args.minimum:
        print(
            f"JaCoCo aggregate LINE covered ratio {pct:.2f}% "
            f"< minimum {args.minimum * 100:.2f}%",
            file=sys.stderr,
        )
        return 1
    print(f"JaCoCo aggregate LINE gate passed ({pct:.2f}% >= {args.minimum})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
