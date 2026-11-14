"""Read-only host-side departure decision helper. Never calls an API or sends a message."""

import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
MAX_RECEIPT_AGE_SECONDS = 60  # Working demo policy, not a guarantee of sensor freshness.


def instant(value):
    if not isinstance(value, str) or not re.search(r"(?:Z|[+-]\d{2}:\d{2})$", value):
        raise ValueError("Explicit timezone required")
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("Explicit timezone required")
    return result


def stopped(code):
    return {"decision": "STOP", "code": code, "messageAction": "NONE", "maySend": False,
            "scope": "derived_input_check_not_e2e_evidence"}


def normalized(value):
    return "".join(value.split()).casefold() if isinstance(value, str) else ""


def decide(snapshot, now=None):
    """Consumes derived event fields and MCP structuredContent, not calendar/conversation bodies.

    This checks supplied data; it cannot authenticate a provider or prove a tool was called.
    Source and request IDs in an input file are not independent E2E evidence.
    """
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None or not isinstance(snapshot, dict):
        return stopped("INVALID_INPUT")
    appointment = snapshot.get("appointment")
    if not isinstance(appointment, dict):
        return stopped("NO_APPOINTMENT")
    try:
        start = instant(appointment.get("startAt"))
    except (ValueError, TypeError):
        return stopped("APPOINTMENT_TIME_UNAVAILABLE")
    if start <= now:
        return stopped("APPOINTMENT_ALREADY_STARTED")
    places = json.loads((ROOT / "contracts/navigation/places.json").read_text(encoding="utf-8"))["places"]
    place = next((p for p in places if p["key"] == appointment.get("candidateId")), None)
    if place is None:
        return stopped("LOCATION_UNCONFIRMED")
    setting = snapshot.get("destinationSetting")
    if not isinstance(setting, dict) or setting.get("ok") is not True:
        return stopped("NAVIGATION_NOT_CONFIRMED")
    if setting.get("source") != "pleos_emulator":
        return stopped("UNVERIFIED_SOURCE")
    if setting.get("status") not in ("APPLIED", "ALREADY_SET"):
        return stopped("NAVIGATION_NOT_CONFIRMED")
    destination = setting.get("destination")
    if not isinstance(destination, dict) or destination.get("candidateId") != place["key"]:
        return stopped("DESTINATION_MISMATCH")
    nav = snapshot.get("navigation")
    if not isinstance(nav, dict) or nav.get("ok") is not True:
        return stopped("NAVIGATION_UNAVAILABLE")
    if nav.get("source") != "pleos_emulator":
        return stopped("UNVERIFIED_SOURCE")
    if nav.get("status") != "COMPLETE":
        return stopped("NAVIGATION_INCOMPLETE")
    actual = nav.get("destination")
    known_names = [place["displayName"], *place.get("aliases", []), place.get("capture", {}).get("name", "")]
    if not isinstance(actual, dict) or not normalized(actual.get("name")):
        return stopped("DESTINATION_MISMATCH")
    if normalized(actual["name"]) not in {normalized(name) for name in known_names if name}:
        return stopped("DESTINATION_MISMATCH")
    seconds = nav.get("remainTimeSeconds")
    if type(seconds) is not int or seconds <= 0:
        return stopped("ETA_UNAVAILABLE")
    try:
        received = instant(nav.get("receivedAt"))
        arrival = instant(nav.get("estimatedArrivalAt"))
        computed = received + timedelta(seconds=seconds)
    except (ValueError, TypeError, OverflowError):
        return stopped("ETA_UNAVAILABLE")
    if not 0 <= (now - received).total_seconds() <= MAX_RECEIPT_AGE_SECONDS:
        return stopped("RECEIPT_TOO_OLD_OR_CLOCK_MISMATCH")
    if computed != arrival:
        return stopped("ETA_INCONSISTENT")
    lateness = (arrival - start).total_seconds()
    return {
        "decision": "LATE" if lateness > 0 else "ON_TIME",
        "lateSeconds": max(0, lateness),
        "messageAction": "PREPARE_DRAFT" if lateness > 0 else "NONE",
        "maySend": False,
        "basis": "app_estimated_arrival_not_guaranteed",
        "scope": "derived_input_check_not_e2e_evidence",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path, help="Local derived event + MCP structuredContent JSON")
    parser.add_argument("--now", help="Explicit-offset comparison instant; use only for synthetic replay")
    args = parser.parse_args()
    try:
        payload = args.snapshot.read_bytes()
        encoding = "utf-16" if payload.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
        snapshot = json.loads(payload.decode(encoding))
        result = decide(snapshot, instant(args.now) if args.now else None)
    except (ValueError, TypeError, OSError, UnicodeError):
        # Never echo a local path, raw body or provider error into evidence.
        print(json.dumps(stopped("INVALID_INPUT")))
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
