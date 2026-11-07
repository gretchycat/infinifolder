#!/usr/bin/env python3
"""Export the linked libraries' runtime capability catalog as a CSV inventory."""
import argparse
import csv
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELDS = [
    "handler_id", "library", "title", "profile_index", "operations",
    "mime_types", "extensions", "uri_schemes", "magic_signatures",
    "metadata", "limitations", "attributes", "availability_state",
    "availability_reason", "availability_dependencies",
]


def joined(values):
    return ";".join(str(value) for value in sorted(values or []))


def catalog_profiles(catalog):
    handlers = {handler["id"]: handler for handler in catalog["handlers"]}
    profiles = catalog.get("supportedFormats")
    if profiles is None:
        # Read older schemaVersion 1 snapshots while they are regenerated.
        profiles = []
        for handler in catalog["handlers"]:
            for index, profile in enumerate(handler.get("formats", [])):
                profiles.append({
                    "handlerId": handler["id"], "library": handler["library"],
                    "title": handler["title"], "operations": handler.get("operations", []),
                    "attributes": handler.get("attributes", {}), "profileIndex": index,
                    **profile,
                })
    rows = []
    profiled_handlers = set()
    for profile in profiles:
        handler_id = profile["handlerId"]
        handler = handlers.get(handler_id)
        if handler is None:
            raise ValueError(f"Format profile references missing handler: {handler_id}")
        profiled_handlers.add(handler_id)
        availability = handler.get("availability", {})
        magic = [
            {"hex": item.get("hex", ""), "offset": item.get("offset", 0),
             "description": item.get("description", "")}
            for item in profile.get("magic", [])
        ]
        rows.append({
            "handler_id": handler_id,
            "library": profile.get("library", handler.get("library", "")),
            "title": profile.get("title", handler.get("title", "")),
            "profile_index": profile.get("profileIndex", ""),
            "operations": joined(profile.get("operations", handler.get("operations", []))),
            "mime_types": joined(profile.get("mimeTypes", [])),
            "extensions": joined(profile.get("extensions", [])),
            "uri_schemes": joined(profile.get("uriSchemes", [])),
            "magic_signatures": json.dumps(magic, ensure_ascii=False, separators=(",", ":")),
            "metadata": joined(handler.get("metadata", [])),
            "limitations": " | ".join(handler.get("limitations", [])),
            "attributes": json.dumps(profile.get("attributes", handler.get("attributes", {})),
                                      ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            "availability_state": availability.get("state", "NOT_EVALUATED"),
            "availability_reason": availability.get("reason") or "",
            "availability_dependencies": json.dumps(availability.get("dependencies", {}),
                                                     sort_keys=True, separators=(",", ":")),
        })
    for handler in catalog["handlers"]:
        if handler["id"] in profiled_handlers:
            continue
        availability = handler.get("availability", {})
        rows.append({
            "handler_id": handler["id"], "library": handler.get("library", ""),
            "title": handler.get("title", ""), "profile_index": "",
            "operations": joined(handler.get("operations", [])), "mime_types": "",
            "extensions": "", "uri_schemes": "", "magic_signatures": "[]",
            "metadata": joined(handler.get("metadata", [])),
            "limitations": " | ".join(handler.get("limitations", [])),
            "attributes": json.dumps(handler.get("attributes", {}), ensure_ascii=False,
                                      sort_keys=True, separators=(",", ":")),
            "availability_state": availability.get("state", "NOT_EVALUATED"),
            "availability_reason": availability.get("reason") or "",
            "availability_dependencies": json.dumps(availability.get("dependencies", {}),
                                                     sort_keys=True, separators=(",", ":")),
        })
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path,
                        default=ROOT / "app/build/generated/capabilities/catalog.json")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/supported-types.csv")
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    if catalog.get("schemaVersion") != 1:
        raise ValueError("Unsupported runtime capability catalog schema")
    if catalog.get("failures"):
        raise ValueError("Capability catalog has provider failures: " + json.dumps(catalog["failures"]))
    if not isinstance(catalog.get("handlers"), list):
        raise ValueError("Capability catalog has no handler list")
    rows = catalog_profiles(catalog)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(sorted(rows, key=lambda row: (
            row["library"], row["handler_id"], row["profile_index"]
        )))
    print(f"{args.output}: {len(rows)} capability profiles; "
          f"{dict(Counter(row['library'] for row in rows))}")


if __name__ == "__main__":
    main()
