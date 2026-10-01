from __future__ import annotations

from datetime import datetime, timedelta, timezone
import unittest

from supervisor_companion.pace import Pace
from supervisor_companion.progress import Unavailable, parse_progress
from supervisor_companion.view_model import build_card, build_full, kind_runs
from test_progress import progress_payload

NOW = datetime(2026, 9, 26, 12, 0, tzinfo=timezone.utc)


def observation(**changes):
    data = {"state": "BUILDING", "run_id": "run", "plan_id": "sha256:plan", "allowed_actions": ["PAUSE", "STOP"],
            "blockers": [], "last_error": None, "last_message": "Building.", "stable_verification_passes": 0,
            "control_token_configured": True,
            "last_progress_at": (NOW - timedelta(seconds=4)).isoformat(),
            "current_layer": {"order": "LAYERS", "stage": "STRUCTURE", "index": 30, "total": 76, "y": -34,
                              "chunk_index": 41, "chunk_total": 49},
            "last_control": {"sequence": 3, "action": "RESUME", "request_id": "monitor-abc"}}
    data.update(changes)
    return data


def snapshot(observation_value=None, **changes):
    data = {"connection": "online", "detail": None, "message": "Connected.", "fresh": True, "paired": True,
            "observation": observation() if observation_value is None else observation_value,
            "pending_actions": [], "control_message": "", "events": [], "state_since": None,
            "own_request_ids": [], "progress_status": "ok",
            "progress": parse_progress(progress_payload()), "pace": None}
    data.update(changes)
    return data


PAIRING_REASON = "Controls need a working pairing file."
BLOCKED_BY_PAIRING = {action: (False, PAIRING_REASON)
                      for action in ("START", "PAUSE", "RESUME", "STOP", "SCAN_DEPOTS")}


def controls(full):
    """Every button in a full view, as (enabled, reason)."""
    return {button.action: (button.enabled, button.reason) for button in full.buttons}


class CardViewTests(unittest.TestCase):
    def test_building_shows_progress_and_the_current_stage(self):
        card = build_card(snapshot(), now=NOW)
        self.assertEqual((card.state_label, card.tone), ("Building", "ok"))
        self.assertEqual((card.status_text, card.status_tone), ("Progress 4s ago", "muted"))
        self.assertEqual((card.percent_text, card.percent_suffix), ("62%", "built"))
        self.assertEqual(card.verify_text, "Verify 0/2")
        self.assertEqual([segment.status for segment in card.segments], ["done", "current", "todo"])
        self.assertEqual(card.kind_labels, (("Structure", 1), ("Lights", 1), ("Till", 1)))
        self.assertEqual(card.line_text, "Now: Structure · Y -34 · chunk 41 of 49")
        self.assertEqual((card.toggle.action, card.toggle.enabled), ("PAUSE", True))
        self.assertFalse(card.scan.enabled)
        self.assertEqual(card.title, "Building · 62% · Schematic Supervisor")
        self.assertFalse(card.stale)

    def test_slow_progress_turns_the_status_amber(self):
        slow = observation(last_progress_at=(NOW - timedelta(minutes=3)).isoformat())
        card = build_card(snapshot(slow), now=NOW)
        self.assertEqual((card.status_text, card.status_tone), ("Progress 3 m ago", "warn"))

    def test_no_progress_yet_needs_the_mod_to_say_so(self):
        # null means no progress since the plan loaded; an older mod doesn't send the field at all.
        for state in ("BUILDING", "RESTOCKING", "STUCK"):
            with self.subTest(state):
                none_yet = build_card(snapshot(observation(state=state, last_progress_at=None)), now=NOW)
                self.assertEqual((none_yet.status_text, none_yet.status_tone), ("No progress yet", "muted"))
                older_mod = observation(state=state)
                del older_mod["last_progress_at"]
                card = build_card(snapshot(older_mod), now=NOW)
                self.assertEqual((card.status_text, card.status_tone), ("", "muted"))
        unreadable = build_card(snapshot(observation(last_progress_at="soon")), now=NOW)
        self.assertEqual((unreadable.status_text, unreadable.status_tone), ("", "muted"))

    def test_paused_with_a_known_error_shows_its_fix(self):
        paused = observation(state="PAUSED", allowed_actions=["RESUME", "STOP", "SCAN_DEPOTS"],
                             last_error="Registered depots cannot satisfy the exact material shortage: "
                                        "{hoe=1} Missing: {hoe=1}")
        card = build_card(snapshot(paused, state_since=(NOW - timedelta(minutes=12)).isoformat()), now=NOW)
        self.assertEqual((card.state_label, card.tone), ("Paused", "warn"))
        self.assertEqual(card.status_text, "for 12 m")
        self.assertEqual(card.line_tone, "warn")
        self.assertIn("press Scan depots", card.line_text)
        self.assertEqual((card.toggle.action, card.toggle.label, card.toggle.enabled), ("RESUME", "Continue", True))
        self.assertTrue(card.scan.enabled)

    def test_unknown_blocker_is_shown_as_written_when_idle(self):
        idle = observation(state="IDLE", blockers=["Select exactly one enabled Litematica sub-region."])
        card = build_card(snapshot(idle), now=NOW)
        self.assertEqual(card.line_text, "Select exactly one enabled Litematica sub-region.")
        self.assertIsNone(card.toggle)

    def test_stale_telemetry_keeps_last_values_in_grey(self):
        card = build_card(snapshot(fresh=False, connection="stale", detail="stale",
                                   message="Minecraft stopped reporting. It may be on a loading screen or frozen."),
                          now=NOW)
        self.assertEqual((card.state_label, card.tone), ("Stale", "offline"))
        self.assertTrue(card.stale)
        self.assertEqual(card.percent_suffix, "built (last known)")
        self.assertIn("stopped reporting", card.line_text)
        self.assertEqual((card.toggle.action, card.toggle.enabled), ("PAUSE", True))
        self.assertEqual(card.status_text, "")

    def test_connection_problems_are_named(self):
        for detail, label in (("refused", "Not running"), ("timeout", "Not responding"),
                              ("reset", "Connection dropped"), ("unreachable", "Not reachable"),
                              ("not_ready", "Starting"), ("token_missing", "Pairing needed"),
                              ("token_rejected", "Pairing rejected"), ("invalid", "Version mismatch"),
                              ("http_error", "Error")):
            with self.subTest(detail):
                card = build_card(snapshot({}, fresh=False, connection="offline", detail=detail,
                                           message="Details."), now=NOW)
                self.assertEqual(card.state_label, label)
                self.assertEqual(card.line_text, "Details.")

    def test_done_build(self):
        done = observation(state="DONE", stable_verification_passes=2,
                           current_layer={"order": "LAYERS", "stage": "DONE", "index": 3, "total": 3, "y": None,
                                          "chunk_index": 0, "chunk_total": 0})
        payload = progress_payload()
        payload["totals"] = {"actions": 8, "done": 8, "stages": 3, "current_stage": None}
        for stage, letters in zip(payload["stages"], ("DD", "DD", "D.")):
            stage["done"] = stage["actions"]
            stage["chunks"] = letters
        card = build_card(snapshot(done, progress=parse_progress(payload)), now=NOW)
        self.assertEqual((card.state_label, card.tone, card.status_text), ("Done", "done", "Finished"))
        self.assertEqual(card.percent_text, "100%")
        self.assertEqual(card.verify_text, "Verify 2/2")
        self.assertEqual(card.line_text, "Build complete and verified.")
        self.assertIsNone(card.toggle)

    def test_failed_progress_fetch_keeps_the_last_value_marked_as_last_known(self):
        card = build_card(snapshot(progress_status="error"), now=NOW)
        self.assertEqual((card.percent_text, card.percent_suffix), ("62%", "built (last known)"))
        full = build_full(snapshot(progress_status="error"), now=NOW)
        self.assertEqual([(item.text, item.tone) for item in full.attention],
                         [("Progress data unavailable.", "muted")])
        nothing_kept = build_full(snapshot(progress=None, progress_status="error"), now=NOW)
        self.assertEqual(nothing_kept.attention, ())
        self.assertEqual(nothing_kept.map_message, "Progress data is unavailable. Details are in the log.")

    def test_loading_shows_the_load_percentage(self):
        loading = observation(state="LOADING", loading_progress=0.456, current_layer=None)
        card = build_card(snapshot(loading, progress=Unavailable(2, "The plan is loading."),
                                   progress_status="unavailable"), now=NOW)
        self.assertEqual(card.status_text, "Loading 45%")
        self.assertEqual((card.percent_text, card.percent_suffix), ("—", "Loading the plan"))

    def test_checking_shows_the_check_percentage_and_can_be_paused(self):
        checking = observation(state="CHECKING", current_layer=None, allowed_actions=["PAUSE", "STOP"],
                               build_check={"status": "RUNNING", "progress": 0.456,
                                            "summary": "Checking the build before starting."})
        card = build_card(snapshot(checking), now=NOW)
        self.assertEqual((card.state_label, card.tone), ("Checking", "ok"))
        self.assertEqual(card.status_text, "Checking 45%")
        self.assertEqual(card.line_text, "Checking what is already built before starting.")
        self.assertEqual((card.toggle.action, card.toggle.enabled), ("PAUSE", True))
        self.assertEqual(build_full(snapshot(checking), now=NOW).attention, ())
        unknown = build_card(snapshot(observation(state="CHECKING", build_check=None)), now=NOW)
        self.assertEqual(unknown.status_text, "Checking")

    def test_old_mod_shows_approximate_percent_and_update_note(self):
        old = observation(current_layer={"order": "LAYERS", "stage": "STRUCTURE", "index": 3, "total": 10, "y": 0,
                                         "chunk_index": 5, "chunk_total": 10})
        # An older mod never sends last_progress_at, so there is no progress time to show.
        del old["last_progress_at"]
        state = snapshot(old, progress=None, progress_status="unsupported")
        card = build_card(state, now=NOW)
        self.assertEqual((card.status_text, card.status_tone), ("", "muted"))
        self.assertEqual((card.percent_text, card.percent_suffix), ("≈ 24%", "built (approximate)"))
        self.assertEqual([segment.status for segment in card.segments][:4], ["done", "done", "current", "todo"])
        self.assertEqual(len(card.segments), 10)
        full = build_full(state, now=NOW)
        self.assertEqual(full.map_message, "Update the mod to see the chunk map.")
        self.assertEqual([(item.text, item.tone) for item in full.attention],
                         [("Update the mod for exact progress.", "muted")])
        stale = dict(state, fresh=False, connection="stale", detail="stale", message="Stopped.")
        self.assertEqual([item.text for item in build_full(stale, now=NOW).attention],
                         ["Stopped.", "Update the mod for exact progress."])

    def test_a_plan_with_too_many_stages_shows_the_approximate_percent(self):
        too_many = Unavailable(9, "The plan has too many stages to report progress.")
        card = build_card(snapshot(progress=too_many, progress_status="unavailable"), now=NOW)
        # Stage 30 of 76, chunk 41 of 49: (29 + 40/49) / 76.
        self.assertEqual((card.percent_text, card.percent_suffix), ("≈ 39%", "built (approximate)"))
        self.assertEqual(card.title, "Building · ≈ 39% · Schematic Supervisor")
        no_layer = build_card(snapshot(observation(current_layer=None), progress=too_many,
                                       progress_status="unavailable"), now=NOW)
        self.assertEqual((no_layer.percent_text, no_layer.percent_suffix),
                         ("—", "The plan has too many stages to report progress."))

    def test_pairing_problems_are_named_and_block_active_controls(self):
        missing = snapshot({}, fresh=False, paired=False, connection="unauthorized", detail="token_missing",
                           message="Pairing file not found at C:\\game\\protocol-token.txt. "
                                   "Launch the game once so the mod creates it.")
        card = build_card(missing, now=NOW)
        self.assertEqual(card.state_label, "Pairing needed")
        self.assertIn("Pairing file not found", card.line_text)
        self.assertFalse(card.scan.enabled)
        self.assertEqual(controls(build_full(missing, now=NOW)), BLOCKED_BY_PAIRING)
        # The monitor has a token, but the mod rejects it; the last observation was building.
        rejected = snapshot(fresh=False, connection="unauthorized", detail="token_rejected",
                            message="The pairing file doesn't match this game.")
        card = build_card(rejected, now=NOW)
        self.assertEqual(card.state_label, "Pairing rejected")
        self.assertEqual((card.toggle.action, card.toggle.enabled, card.toggle.reason),
                         ("PAUSE", False, PAIRING_REASON))
        self.assertEqual(controls(build_full(rejected, now=NOW)), BLOCKED_BY_PAIRING)
        tokenless = observation(state="PAUSED", allowed_actions=["PAUSE", "STOP"], control_token_configured=False)
        full = build_full(snapshot(tokenless, paired=False), now=NOW)
        self.assertIn("The mod has no pairing token", full.attention[-1].text)
        buttons = {button.action: button for button in full.buttons}
        self.assertFalse(buttons["RESUME"].enabled)
        self.assertEqual(buttons["RESUME"].reason, "The monitor needs the pairing file for this.")
        self.assertTrue(buttons["PAUSE"].enabled)
        self.assertTrue(buttons["STOP"].enabled)
        # A 401 means the mod has a token now, whatever an older observation said.
        stale_tokenless = snapshot(tokenless, fresh=False, paired=False, connection="unauthorized",
                                   detail="token_missing", message="Details.")
        self.assertEqual(controls(build_full(stale_tokenless, now=NOW)), BLOCKED_BY_PAIRING)

    def test_an_unreadable_or_invalid_pairing_file_is_named(self):
        invalid = snapshot({}, fresh=False, paired=False, connection="configuration_error", detail=None,
                           message="The pairing file at C:\\game\\protocol-token.txt is invalid.")
        card = build_card(invalid, now=NOW)
        self.assertEqual((card.state_label, card.tone), ("Pairing invalid", "offline"))
        self.assertEqual(card.line_text, "The pairing file at C:\\game\\protocol-token.txt is invalid.")
        self.assertFalse(card.scan.enabled)
        self.assertEqual(controls(build_full(invalid, now=NOW)), BLOCKED_BY_PAIRING)

    def test_only_known_pairing_failures_block_pause_and_stop(self):
        # The monitor also reports paired False before its first poll and after an unexpected read error.
        for connection, last_known, message in (
                ("connecting", {}, "Waiting for the local supervisor."),
                ("error", observation(), "Could not read the local supervisor; details are in the log.")):
            with self.subTest(connection):
                state = snapshot(last_known, fresh=False, paired=False, connection=connection, detail=None,
                                 message=message)
                buttons = controls(build_full(state, now=NOW))
                self.assertEqual((buttons["PAUSE"], buttons["STOP"]), ((True, ""), (True, "")))
                self.assertEqual(buttons["START"], (False, "Waiting for fresh status from Minecraft."))
        # The monitor's own read failed; that is an error, not the mod being offline.
        read_error = snapshot(fresh=False, paired=False, connection="error", detail=None, message="m")
        card = build_card(read_error, now=NOW)
        self.assertEqual((card.state_label, card.tone, card.toggle.action, card.toggle.enabled),
                         ("Error", "offline", "PAUSE", True))
        unnamed = build_card(snapshot({}, fresh=False, connection="offline", detail=None, message="m"), now=NOW)
        self.assertEqual((unnamed.state_label, unnamed.tone), ("Offline", "offline"))

    def test_garbage_observation_never_raises(self):
        garbage = {"state": ["BUILDING"], "blockers": 5, "last_error": 7, "current_layer": "x",
                   "last_control": "x", "allowed_actions": "START", "stable_verification_passes": "2",
                   "last_progress_at": 5, "loading_progress": "half", "control_token_configured": "yes",
                   "plan_id": 3}
        state = snapshot(garbage, pending_actions="PAUSE", own_request_ids=None, progress=object(), pace="fast")
        card = build_card(state, now=NOW)
        full = build_full(state, now=NOW, selected_stage=99, map_mode="all")
        self.assertEqual(card.state_label, "Unknown")
        self.assertEqual(full.cells, ())
        # The pairing check compares the connection by equality, so any value is safe.
        unhashable = build_full(dict(state, connection=["unauthorized"], progress_status={"error": 1}), now=NOW)
        self.assertEqual(controls(unhashable)["PAUSE"], (True, ""))


class FullViewTests(unittest.TestCase):
    def test_pace_and_last_control_are_described(self):
        full = build_full(snapshot(pace=Pace(214.4, 6 * 3600 + 20 * 60)), now=NOW)
        self.assertEqual(full.pace_text, "214 per min · ≈ 6 h 20 m left, excluding verification")
        self.assertEqual(full.last_control_text, "Last control: Continue · from this monitor")
        for request_id, source in ((None, "in game"), ("agent-1", "the AI runner"), ("mcp-7", "another tool")):
            control = {"sequence": 4, "action": "PAUSE", "request_id": request_id}
            text = build_full(snapshot(observation(last_control=control)), now=NOW).last_control_text
            self.assertEqual(text, f"Last control: Pause · from {source}")
        # Actions the monitor has no label for, such as in-game operator commands, read in sentence case.
        command = {"sequence": 5, "action": "REGISTER_NEARBY_DEPOTS", "request_id": None}
        text = build_full(snapshot(observation(last_control=command)), now=NOW).last_control_text
        self.assertEqual(text, "Last control: Register nearby depots · from in game")

    def test_build_check_findings_become_attention_items(self):
        check = {"status": "COMPLETE", "progress": 1.0, "summary": "Build check (2.3 s): …",
                 "chunks_checked": 45, "chunks_total": 49,
                 "wrong_blocks": 3, "wrong_blocks_cleared": 2, "extra_blocks": 7, "extra_blocks_cleared": 1,
                 "problems": [{"kind": "EXTRA", "x": 8198, "y": -40, "z": -26920, "chunk": 12,
                               "expected": "minecraft:air", "actual": "minecraft:cobblestone"},
                              {"kind": "WRONG", "x": 8199, "y": -38, "z": -26920, "chunk": 12,
                               "expected": "minecraft:glowstone", "actual": "minecraft:sea_lantern"}]}
        full = build_full(snapshot(observation(build_check=check)), now=NOW)
        self.assertEqual([(item.text, item.hint, item.tone) for item in full.attention], [
            ("The build check found 1 wrong block and 6 extra blocks that the builder won't fix.",
             "Replace or remove them, or final verification will fail. First: extra cobblestone at "
             "x 8198, y -40, z -26920 (chunk 12). Status details lists more.", "warn"),
            ("4 chunks were out of range during the build check; the builder checks them when it gets there.",
             None, "muted")])

        clean = dict(check, chunks_checked=49, wrong_blocks=0, wrong_blocks_cleared=0, extra_blocks=2,
                     extra_blocks_cleared=2, problems=[])
        self.assertEqual(build_full(snapshot(observation(build_check=clean)), now=NOW).attention, ())
        one = dict(check, chunks_checked=48, wrong_blocks=1, wrong_blocks_cleared=0, extra_blocks=0,
                   extra_blocks_cleared=0, problems=[check["problems"][1]])
        items = build_full(snapshot(observation(build_check=one)), now=NOW).attention
        self.assertEqual(items[0].text, "The build check found 1 wrong block that the builder won't fix.")
        self.assertTrue(items[0].hint.endswith("First: sea lantern instead of glowstone at x 8199, y -38, "
                                               "z -26920 (chunk 12)."))
        self.assertEqual(items[1].text, "1 chunk was out of range during the build check; "
                                        "the builder checks it when it gets there.")
        failed = {"status": "FAILED", "summary": "The build check failed (boom); construction started from the "
                                                 "first stage and re-checks each piece instead."}
        self.assertEqual([(item.text, item.tone) for item in
                          build_full(snapshot(observation(build_check=failed)), now=NOW).attention],
                         [(failed["summary"], "muted")])
        garbage = {"status": "COMPLETE", "wrong_blocks": "3", "extra_blocks": None, "problems": [None, {"x": 1}],
                   "chunks_total": 49}
        self.assertEqual(build_full(snapshot(observation(build_check=garbage)), now=NOW).attention, ())

    def test_pace_is_hidden_while_progress_cannot_be_read(self):
        full = build_full(snapshot(progress_status="error", pace=Pace(214.4, 6 * 3600 + 20 * 60)), now=NOW)
        self.assertEqual(full.pace_text, "")

    def test_old_error_during_building_is_marked_as_possibly_old(self):
        building = observation(last_error="Depot withdrawal failed: real chest stock does not cover x")
        full = build_full(snapshot(building), now=NOW)
        self.assertEqual([item.tone for item in full.attention], ["muted"])
        self.assertTrue(full.attention[0].text.startswith("Last error (may be old): "))
        self.assertEqual(build_card(snapshot(observation(last_error="Old.")), now=NOW).line_text,
                         "Now: Structure · Y -34 · chunk 41 of 49")

    def test_map_follows_the_current_stage_until_another_is_selected(self):
        full = build_full(snapshot(), now=NOW)
        self.assertTrue(full.following_current)
        self.assertEqual(full.selected_stage, 1)
        self.assertEqual(full.stage_caption, "Stage 2 of 3 · Lights · Y -62 · 50%")
        self.assertEqual((full.map_columns, full.map_rows), (2, 1))
        self.assertEqual([cell.status for cell in full.cells], ["done", "current"])
        self.assertIn("x 8208, z -26976", full.cells[1].tooltip)
        chosen = build_full(snapshot(), now=NOW, selected_stage=2)
        self.assertFalse(chosen.following_current)
        self.assertEqual([cell.status for cell in chosen.cells], ["todo", "none"])

    def test_a_hovered_stage_takes_the_map_without_changing_the_selection(self):
        following = build_full(snapshot(), now=NOW, preview_stage=2)
        self.assertEqual((following.selected_stage, following.following_current), (1, True))
        self.assertEqual(following.stage_caption, "Stage 3 of 3 · Till · Y -63 · 0%")
        self.assertEqual([cell.status for cell in following.cells], ["todo", "none"])
        chosen = build_full(snapshot(), now=NOW, selected_stage=0, preview_stage=2)
        self.assertEqual((chosen.selected_stage, chosen.following_current), (0, False))
        self.assertEqual(chosen.stage_caption, following.stage_caption)
        # Nothing hovered, or a stage past the end of a plan that changed, shows the selected stage.
        for preview in (None, 3, -1):
            with self.subTest(preview=preview):
                self.assertEqual(build_full(snapshot(), now=NOW, selected_stage=0, preview_stage=preview),
                                 build_full(snapshot(), now=NOW, selected_stage=0))
        self.assertEqual(build_full(snapshot(), now=NOW, map_mode="all", preview_stage=2),
                         build_full(snapshot(), now=NOW, map_mode="all"))

    def test_all_stages_map_shades_by_share(self):
        full = build_full(snapshot(), now=NOW, map_mode="all")
        self.assertEqual([cell.status for cell in full.cells], ["share", "share"])
        self.assertAlmostEqual(full.cells[0].share, 2 / 3)

    def test_truncated_stage_detail_explains_the_missing_map(self):
        payload = progress_payload(chunk_detail_truncated=True)
        payload["stages"][0]["chunks"] = None
        full = build_full(snapshot(progress=parse_progress(payload)), now=NOW, selected_stage=0)
        self.assertEqual(full.map_message, "Chunk detail isn't available for this stage.")

    def test_buttons_follow_the_safety_rules(self):
        paused = observation(state="PAUSED", allowed_actions=["RESUME", "STOP", "START"])
        buttons = {button.action: button for button in build_full(snapshot(paused), now=NOW).buttons}
        self.assertTrue(buttons["START"].enabled)
        self.assertTrue(buttons["RESUME"].enabled)
        self.assertFalse(buttons["SCAN_DEPOTS"].enabled)
        self.assertEqual(buttons["SCAN_DEPOTS"].reason, "The mod doesn't allow this right now.")
        busy = build_full(snapshot(paused, pending_actions=["STOP"]), now=NOW)
        pending = {button.action: button for button in busy.buttons}
        self.assertFalse(pending["STOP"].enabled)
        self.assertFalse(pending["RESUME"].enabled)
        self.assertTrue(pending["PAUSE"].enabled)
        self.assertEqual(busy.control_message, "Waiting for acknowledgment: Stop.")


class KindRunTests(unittest.TestCase):
    def test_runs_merge_when_the_schedule_interleaves(self):
        self.assertEqual(kind_runs(["STRUCTURE"] * 3 + ["LIGHTING"] * 2 + ["TILL"]),
                         (("Structure", 3), ("Lights", 2), ("Till", 1)))
        self.assertEqual(kind_runs(["STRUCTURE", "LIGHTING"] * 3 + ["TILL", "PLANT"] * 2),
                         (("Build", 6), ("Farm", 4)))
        self.assertEqual(kind_runs([]), ())


if __name__ == "__main__":
    unittest.main()
