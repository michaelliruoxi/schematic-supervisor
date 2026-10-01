from __future__ import annotations

import copy
import unittest

from supervisor_companion.progress import (
    MaterialCount,
    Progress,
    ProgressError,
    Unavailable,
    approximate_from_layer,
    chunk_shares,
    parse_progress,
)


def progress_payload(**changes):
    """Two chunks side by side and three stages; 5 of 8 actions done; stage 2 is current."""
    data = {"protocol_version": 1, "available": True, "revision": 4, "plan_id": "sha256:plan",
            "schedule_id": "layers-v1-structure-first",
            "layout": {"origin_x": 512, "origin_z": -1686, "columns": 2, "rows": 1},
            "totals": {"actions": 8, "done": 5, "stages": 3, "current_stage": 2},
            "stages": [{"kind": "STRUCTURE", "y": -63, "actions": 4, "done": 4, "chunks": "DD"},
                       {"kind": "LIGHTING", "y": -62, "actions": 2, "done": 1, "chunks": "DC"},
                       {"kind": "TILL", "y": -63, "actions": 2, "done": 0, "chunks": "-."}],
            "chunk_detail_truncated": False}
    data.update(changes)
    return data


class ProgressTests(unittest.TestCase):
    def test_valid_payload_is_parsed_with_derived_facts(self):
        progress = parse_progress(progress_payload())
        self.assertIsInstance(progress, Progress)
        self.assertEqual(progress.revision, 4)
        self.assertAlmostEqual(progress.fraction, 5 / 8)
        self.assertEqual(progress.remaining_actions, 3)
        self.assertEqual([progress.stage_status(index) for index in range(3)], ["done", "current", "todo"])
        self.assertEqual(progress.layout.chunk_coordinates(1), (513, -1686))

    def test_a_stage_behind_the_current_one_can_be_partly_done(self):
        payload = progress_payload()
        payload["totals"]["current_stage"] = 3
        payload["stages"][2]["chunks"] = "C."
        self.assertEqual(parse_progress(payload).stage_status(1), "partial")

    def test_unavailable_payload(self):
        result = parse_progress({"protocol_version": 1, "available": False, "revision": 9,
                                 "reason": "No plan is loaded."})
        self.assertEqual(result, Unavailable(9, "No plan is loaded."))

    def test_malformed_payloads_are_rejected(self):
        mutations = {
            "protocol version": lambda p: p.update(protocol_version=2),
            "available text": lambda p: p.update(available="yes"),
            "negative revision": lambda p: p.update(revision=-1),
            "boolean revision": lambda p: p.update(revision=True),
            "zero columns": lambda p: p["layout"].update(columns=0),
            "too many chunks": lambda p: p["layout"].update(columns=40, rows=40),
            "totals mismatch": lambda p: p["totals"].update(actions=9),
            "stage count mismatch": lambda p: p["totals"].update(stages=4),
            "current beyond stages": lambda p: p["totals"].update(current_stage=4),
            "current zero": lambda p: p["totals"].update(current_stage=0),
            "done above actions": lambda p: p["stages"][2].update(done=3),
            "short chunk letters": lambda p: p["stages"][0].update(chunks="D"),
            "unknown chunk letter": lambda p: p["stages"][0].update(chunks="DX"),
            "missing letters untruncated": lambda p: p["stages"][0].update(chunks=None),
            "empty kind": lambda p: p["stages"][0].update(kind=""),
            "numeric plan id": lambda p: p.update(plan_id=5),
            "stages not a list": lambda p: p.update(stages={}),
            "materials not an object": lambda p: p.update(materials=[]),
            "null materials": lambda p: p.update(materials=None),
            "material not an object": lambda p: p.update(materials={"dirt": 5}),
            "material done above planned": lambda p: p.update(materials={"dirt": {"planned": 1, "done": 2}}),
            "negative material count": lambda p: p.update(materials={"dirt": {"planned": -1, "done": 0}}),
            "missing material count": lambda p: p.update(materials={"dirt": {"planned": 1}}),
            "material count beyond a long": lambda p: p.update(materials={"dirt": {"planned": 2 ** 63, "done": 0}}),
            "blank material name": lambda p: p.update(materials={" ": {"planned": 1, "done": 0}}),
            "too many materials": lambda p: p.update(materials={f"m{index}": {"planned": 1, "done": 0}
                                                                for index in range(65)}),
        }
        for name, mutate in mutations.items():
            payload = copy.deepcopy(progress_payload())
            mutate(payload)
            with self.subTest(name), self.assertRaises(ProgressError):
                parse_progress(payload)

    def test_numbers_outside_the_java_long_range_are_rejected(self):
        # The mod's numbers are Java ints and longs; anything larger could only overflow the display.
        largest, smallest = 2 ** 63 - 1, -2 ** 63
        edges = progress_payload()
        edges["layout"].update(origin_x=largest, origin_z=smallest)
        self.assertIsInstance(parse_progress(edges), Progress)
        for field, value in (("origin_x", largest + 1), ("origin_z", smallest - 1)):
            payload = progress_payload()
            payload["layout"][field] = value
            with self.subTest(field), self.assertRaisesRegex(ProgressError, f"layout.{field}"):
                parse_progress(payload)
        # Every stage fits in a long, but their total is one more than the largest long.
        payload = progress_payload()
        payload["stages"][0].update(actions=2 ** 62, done=2 ** 62)
        payload["stages"][1]["actions"] = 2 ** 62 - 2
        payload["totals"].update(actions=largest + 1, done=2 ** 62 + 1)
        with self.assertRaisesRegex(ProgressError, "totals.actions"):
            parse_progress(payload)

    def test_material_counts_are_read_when_the_mod_sends_them(self):
        progress = parse_progress(progress_payload(materials={"dirt": {"planned": 313600, "done": 313600},
                                                              "wheat_seeds": {"planned": 156799, "done": 21504}}))
        self.assertEqual(progress.materials, (MaterialCount("dirt", 313600, 313600),
                                              MaterialCount("wheat_seeds", 156799, 21504)))
        self.assertEqual([count.remaining for count in progress.materials], [0, 135295])
        self.assertEqual(parse_progress(progress_payload(materials={})).materials, ())
        # Older mods don't send the field.
        self.assertIsNone(parse_progress(progress_payload()).materials)

    def test_truncated_detail_must_keep_the_current_stage(self):
        payload = progress_payload(chunk_detail_truncated=True)
        payload["stages"][0]["chunks"] = None
        payload["stages"][2]["chunks"] = None
        progress = parse_progress(payload)
        self.assertTrue(progress.chunk_detail_truncated)
        self.assertIsNone(chunk_shares(progress))
        payload["stages"][1]["chunks"] = None
        with self.assertRaises(ProgressError):
            parse_progress(payload)

    def test_chunk_shares_count_finished_stages_per_chunk(self):
        shares = chunk_shares(parse_progress(progress_payload()))
        self.assertAlmostEqual(shares[0], 2 / 3)
        self.assertAlmostEqual(shares[1], 1 / 2)

    def test_chunk_without_any_work_has_no_share(self):
        payload = progress_payload()
        payload["stages"] = [{"kind": "STRUCTURE", "y": 0, "actions": 1, "done": 1, "chunks": "D."}]
        payload["totals"] = {"actions": 1, "done": 1, "stages": 1, "current_stage": None}
        self.assertEqual(chunk_shares(parse_progress(payload)), [1.0, None])

    def test_approximate_progress_from_the_observation_layer(self):
        layer = {"order": "LAYERS", "stage": "STRUCTURE", "index": 3, "total": 10, "y": 0,
                 "chunk_index": 5, "chunk_total": 10}
        approximate = approximate_from_layer(layer)
        self.assertEqual((approximate.stage, approximate.stages), (3, 10))
        self.assertAlmostEqual(approximate.fraction, 0.24)
        finished = {"order": "LAYERS", "stage": "DONE", "index": 76, "total": 76, "y": None,
                    "chunk_index": 0, "chunk_total": 0}
        self.assertEqual(approximate_from_layer(finished).fraction, 1.0)
        for invalid in (None, "layer",
                        {"stage": "STRUCTURE", "index": 0, "total": 10, "chunk_index": 1, "chunk_total": 1},
                        {"stage": "STRUCTURE", "index": True, "total": 1, "chunk_index": 1, "chunk_total": 1}):
            self.assertIsNone(approximate_from_layer(invalid))

    def test_a_layer_whose_stage_is_not_text_gives_no_approximation(self):
        for stage in (["DONE"], {"name": "DONE"}):
            for chunk_index, chunk_total in ((0, 0), (5, 10)):
                layer = {"order": "LAYERS", "stage": stage, "index": 1, "total": 1, "y": None,
                         "chunk_index": chunk_index, "chunk_total": chunk_total}
                with self.subTest(stage=stage, chunk_total=chunk_total):
                    self.assertIsNone(approximate_from_layer(layer))

    def test_huge_layer_numbers_never_raise(self):
        huge = 10 ** 400  # valid JSON, but too large to convert to a float
        for layer in ({"stage": "DONE", "index": huge, "total": huge, "chunk_index": 0, "chunk_total": 0},
                      {"stage": "STRUCTURE", "index": 1, "total": huge, "chunk_index": 5, "chunk_total": 10}):
            with self.subTest(stage=layer["stage"]):
                self.assertIsNone(approximate_from_layer(layer))
        for name, chunk_index, chunk_total, fraction in (("huge chunk index and total", huge, huge, 0.1),
                                                         ("huge chunk total", 2, huge, 0.0),
                                                         ("huge chunk index", huge, 10, 0.0)):
            layer = {"stage": "STRUCTURE", "index": 1, "total": 10,
                     "chunk_index": chunk_index, "chunk_total": chunk_total}
            with self.subTest(name):
                self.assertAlmostEqual(approximate_from_layer(layer).fraction, fraction)


if __name__ == "__main__":
    unittest.main()
