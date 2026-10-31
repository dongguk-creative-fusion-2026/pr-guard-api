import unittest
from runner.run import compare


def side(broken):
    return {'input': {'title': '0', 'content': 'a'}, 'observations': [
        {'status': 200, 'author_before': 1, 'author_after': 2 if broken else 1},
        {'status': 200, 'author_before': 11, 'author_after': 12 if broken else 11}]}


class ComparisonTest(unittest.TestCase):
    def test_compares_property_not_database_generated_ids(self):
        self.assertEqual(compare(side(False), side(True))['status'], 'new_violation')
        self.assertEqual(compare(side(True), side(True))['status'], 'existing_violation')

    def test_non_reproducible_results_are_not_regressions(self):
        head = side(True)
        head['observations'][1]['author_after'] = 11
        self.assertEqual(compare(side(False), head)['status'], 'flaky')

    def test_failed_requests_are_not_property_violations(self):
        head = side(True)
        head['observations'][0]['status'] = 500
        with self.assertRaises(ValueError):
            compare(side(False), head)
