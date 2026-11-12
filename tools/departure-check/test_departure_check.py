"""Synthetic decision tests, never actual provider/vehicle or transmission evidence."""

from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from departure_check import decide


class DepartureDecisionTest(unittest.TestCase):
    now = datetime(2026, 11, 12, 1, 0, 0, tzinfo=timezone.utc)

    def snapshot(self, start="2026-11-12T10:30:00+09:00"):
        return {
            "appointment": {"startAt": start, "candidateId": "pangyo_station"},
            "destinationSetting": {"ok": True, "source": "pleos_emulator", "status": "APPLIED",
                                   "destination": {"candidateId": "pangyo_station"}},
            "navigation": {"ok": True, "source": "pleos_emulator", "status": "COMPLETE",
                           "destination": {"name": "판교역"}, "receivedAt": self.now.isoformat(),
                           "remainTimeSeconds": 1200,
                           "estimatedArrivalAt": (self.now + timedelta(seconds=1200)).isoformat()},
        }

    def check_stop(self, data, code):
        result = decide(data, self.now)
        self.assertEqual(("STOP", code, "NONE", False),
                         (result["decision"], result["code"], result["messageAction"], result["maySend"]))

    def test_on_time_has_no_message(self):
        result = decide(self.snapshot(), self.now)
        self.assertEqual(("ON_TIME", "NONE", False),
                         (result["decision"], result["messageAction"], result["maySend"]))

    def test_late_prepares_only_a_draft(self):
        result = decide(self.snapshot("2026-11-12T10:10:00+09:00"), self.now)
        self.assertEqual(("LATE", 600, "PREPARE_DRAFT", False),
                         (result["decision"], result["lateSeconds"], result["messageAction"], result["maySend"]))

    def test_exact_arrival_at_start_is_on_time(self):
        self.assertEqual("ON_TIME", decide(self.snapshot("2026-11-12T10:20:00+09:00"), self.now)["decision"])

    def test_equivalent_timezones_have_the_same_result(self):
        for start in ("2026-11-12T10:30:00+09:00", "2026-11-12T01:30:00Z", "2026-11-11T20:30:00-05:00"):
            self.assertEqual("ON_TIME", decide(self.snapshot(start), self.now)["decision"])

    def test_all_day_naive_missing_and_invalid_start_are_not_guessed(self):
        for start in (None, "2026-11-12", "2026-11-12T10:30:00", "not a date"):
            self.check_stop(self.snapshot(start), "APPOINTMENT_TIME_UNAVAILABLE")

    def test_no_appointment_stops_before_navigation(self):
        self.check_stop({}, "NO_APPOINTMENT")

    def test_appointment_already_started_needs_new_user_decision(self):
        self.check_stop(self.snapshot("2026-11-12T10:00:00+09:00"), "APPOINTMENT_ALREADY_STARTED")

    def test_unlisted_place_is_not_invented(self):
        data = self.snapshot()
        data["appointment"]["candidateId"] = "unknown"
        self.check_stop(data, "LOCATION_UNCONFIRMED")

    def test_not_sent_unknown_outcome_and_restriction_never_proceed(self):
        for status in ("OUTCOME_UNKNOWN", "DRIVING_RESTRICTED", "DRIVING_STATE_UNKNOWN", None):
            data = self.snapshot()
            data["destinationSetting"]["status"] = status
            self.check_stop(data, "NAVIGATION_NOT_CONFIRMED")

    def test_wrong_selected_or_changed_current_destination_stops(self):
        data = self.snapshot()
        data["destinationSetting"]["destination"]["candidateId"] = "coex"
        self.check_stop(data, "DESTINATION_MISMATCH")
        data = self.snapshot()
        data["navigation"]["destination"]["name"] = "코엑스"
        self.check_stop(data, "DESTINATION_MISMATCH")

    def test_read_failure_partial_and_no_destination_do_not_become_on_time(self):
        for changes, expected in (({"ok": False}, "NAVIGATION_UNAVAILABLE"),
                                  ({"status": "PARTIAL"}, "NAVIGATION_INCOMPLETE"),
                                  ({"status": "NO_DESTINATION"}, "NAVIGATION_INCOMPLETE")):
            data = self.snapshot()
            data["navigation"].update(changes)
            self.check_stop(data, expected)

    def test_missing_zero_boolean_and_bad_eta_do_not_mean_arrival(self):
        for seconds in (None, 0, -1, True, "1200"):
            data = self.snapshot()
            data["navigation"]["remainTimeSeconds"] = seconds
            self.check_stop(data, "ETA_UNAVAILABLE")

    def test_forged_absolute_eta_is_not_used(self):
        data = self.snapshot()
        data["navigation"]["estimatedArrivalAt"] = "2026-11-12T02:20:00Z"
        self.check_stop(data, "ETA_INCONSISTENT")

    def test_expired_receipt_and_future_clock_stop(self):
        for shift in (-61, 1):
            data = self.snapshot()
            received = self.now + timedelta(seconds=shift)
            data["navigation"]["receivedAt"] = received.isoformat()
            data["navigation"]["estimatedArrivalAt"] = (received + timedelta(seconds=1200)).isoformat()
            self.check_stop(data, "RECEIPT_TOO_OLD_OR_CLOCK_MISMATCH")

    def test_mock_and_missing_sources_are_not_real_sdk_evidence(self):
        for stage in ("destinationSetting", "navigation"):
            for source in ("simulation", None, "real_vehicle"):
                data = self.snapshot()
                data[stage]["source"] = source
                self.check_stop(data, "UNVERIFIED_SOURCE")

    def test_existing_destination_is_accepted_without_requiring_a_new_change(self):
        data = self.snapshot()
        data["destinationSetting"]["status"] = "ALREADY_SET"
        self.assertEqual("ON_TIME", decide(data, self.now)["decision"])

    def test_decision_does_not_mutate_supplied_results(self):
        data = self.snapshot()
        before = deepcopy(data)
        decide(data, self.now)
        self.assertEqual(before, data)

    def run_cli(self, raw):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "case.snapshot.json"
            path.write_bytes(raw)
            return subprocess.run([sys.executable, str(Path(__file__).with_name("departure_check.py")),
                                   str(path), "--now", self.now.isoformat()],
                                  capture_output=True, text=True)

    def test_windows_utf16_and_utf8_bom_snapshots_work_through_cli(self):
        for encoding in ("utf-16", "utf-8-sig"):
            reply = self.run_cli(json.dumps(self.snapshot()).encode(encoding))
            self.assertEqual(0, reply.returncode)
            result = json.loads(reply.stdout)
            self.assertEqual("ON_TIME", result["decision"])
            self.assertFalse(result["maySend"])
            self.assertEqual("derived_input_check_not_e2e_evidence", result["scope"])

    def test_malformed_input_does_not_echo_private_contents_or_retry(self):
        reply = self.run_cli(b'{"private_body":"do-not-print-this-secret"')
        self.assertEqual(2, reply.returncode)
        self.assertEqual("INVALID_INPUT", json.loads(reply.stdout)["code"])
        self.assertNotIn("do-not-print-this-secret", reply.stdout + reply.stderr)
        self.assertEqual("", reply.stderr)


if __name__ == "__main__":
    unittest.main()
