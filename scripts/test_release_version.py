#!/usr/bin/env python3
"""
Unit tests for scripts/release_version.py
"""

import unittest
from scripts.release_version import compute_next_version, compute_version_code


class TestReleaseVersion(unittest.TestCase):
    def test_compute_next_version_initial(self):
        self.assertEqual(compute_next_version(None), "v0.1.0")
        self.assertEqual(compute_next_version(""), "v0.1.0")
        self.assertEqual(compute_next_version("invalid"), "v0.1.0")

    def test_compute_next_version_increment(self):
        self.assertEqual(compute_next_version("v0.1.0"), "v0.1.1")
        self.assertEqual(compute_next_version("v0.1.9"), "v0.1.10")
        self.assertEqual(compute_next_version("0.1.0"), "v0.1.1")
        self.assertEqual(compute_next_version("v1.2.3"), "v1.2.4")

    def test_compute_version_code(self):
        code = compute_version_code()
        self.assertIsInstance(code, int)
        self.assertGreater(code, 0)


if __name__ == "__main__":
    unittest.main()
