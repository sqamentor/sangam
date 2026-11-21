# SPDX-License-Identifier: Apache-2.0
# PARKED-DEC E-3 red proof (temporary, reverted in the next commit): leaves an untracked file x in the checkout root.
import pathlib
import unittest


class PlantedStrayFile(unittest.TestCase):
    def test_leaves_x(self):
        pathlib.Path(__file__).resolve().parents[3].joinpath("x").write_text("planted\n", encoding="utf-8")