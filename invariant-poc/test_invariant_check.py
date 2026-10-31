import unittest

from invariant_check import Observation, check, check_candidate


class FixtureAdapter:
    """In-memory stand-in, deliberately not the sandbox application."""
    def __init__(self, broken=False):
        self.broken = broken

    def run(self, update):
        return Observation(200, 7, 12 if self.broken and "0" in update.title else 7)


class InvariantCheckTest(unittest.TestCase):
    def test_structured_candidate_selects_trusted_property(self):
        candidate = {"status": "CANDIDATE", "ruleId": "post-author-preserved-on-update",
                     "version": 1, "adapter": "post-update-v1"}
        result = check_candidate(candidate, FixtureAdapter(), FixtureAdapter(True), max_examples=500)
        self.assertEqual(result["status"], "new_violation")
        with self.assertRaises(ValueError):
            check_candidate({**candidate, "ruleId": "unknown"}, FixtureAdapter(), FixtureAdapter())

    def test_new_violation_and_exact_replay(self):
        result = check(FixtureAdapter(), FixtureAdapter(True), max_examples=500)
        self.assertEqual(result["status"], "new_violation")
        self.assertIn("0", result["input"]["title"])
        self.assertEqual(result["base"][0], result["base"][1])
        self.assertEqual(result["head"][0], result["head"][1])

    def test_existing_violation(self):
        self.assertEqual(check(FixtureAdapter(True), FixtureAdapter(True), max_examples=500)["status"],
                         "existing_violation")

    def test_no_counterexample(self):
        self.assertEqual(check(FixtureAdapter(), FixtureAdapter(), max_examples=30)["status"],
                         "no_counterexample")

    def test_unstable_replay_is_flaky(self):
        class UnstableBase:
            def __init__(self):
                self.calls = 0

            def run(self, update):
                self.calls += 1
                return Observation(200, 7, 7 if self.calls % 2 else 12)

        self.assertEqual(check(UnstableBase(), FixtureAdapter(True), max_examples=500)["status"],
                         "flaky")

    def test_errors_are_not_reported_as_pass(self):
        class FailedAdapter:
            def run(self, update):
                return Observation(401, 7, 7)
        with self.assertRaisesRegex(ValueError, "HTTP 401"):
            check(FixtureAdapter(), FailedAdapter())


if __name__ == "__main__":
    unittest.main()
