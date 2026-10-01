from __future__ import annotations

import unittest

from supervisor_companion.hints import RULES, hint_for

JOIN_WORLD_HINT = "Open Minecraft and join the world this build belongs to."
OTHER_WORLD_HINT = ("You're in another world or dimension. Go back to the build's world, "
                    "or run /schematic-supervisor unload in game.")
TAKEOFF_FIRST_HINT = "Run /schematic-supervisor takeoff in game, then Continue."
FLIGHT_NOT_GRANTED_HINT = "Turn on your server flight ability, then Continue. The mod takes off by itself."
SCANNING_HINT = "Chests are still being scanned. This clears on its own."
DEPOT_SHORTAGE_HINT = "Put the missing items in a registered chest, then press Scan depots."
CHEST_SHORT_HINT = "A chest held less than expected. Restock it, then press Scan depots."
INVENTORY_FULL_HINT = ("The inventory is too full. The mod's cleanup usually clears this; "
                       "if not, free some slots in game.")
RESET_HINT = ("Check the inventory in game, then run /schematic-supervisor reset. This discards the "
              "saved position; placed blocks stay and are re-checked.")
PAIRING_HINT = "Restart Minecraft; the mod creates its pairing file at startup."
MOVEMENT_HINT = "A movement key was pressed during flight. Release it, then Continue."
RESTART_HINT = "Restart Minecraft."
DIRT_PURCHASE_HINT = ("A dirt purchase hasn't been confirmed yet. Wait a moment; if it doesn't clear, "
                      "check the inventory in game and restart Minecraft.")
FLIGHT_PERMISSION_HINT = ("The server hasn't granted flight. Turn on your server flight ability, "
                          "then run /schematic-supervisor takeoff.")
OCCUPIED_TARGET_HINT = ("A block that isn't in the plan sits on a target (position in the message). "
                        "Remove it in game if that's safe, then Continue.")
RETRYING_HINT = "The mod waits and tries again by itself. Nothing to do unless it pauses."
FLIGHT_RESTORED_HINT = "Flight dropped and the mod took off again by itself. Nothing to do."
FLIGHT_NOT_RESTORED_HINT = ("Flight dropped and the mod couldn't take off again. Check that your server flight "
                            "ability is on, then Continue.")
ADVISOR_HINT = ("The mod's own recovery didn't fix this. The cause is the text before "
                "\"Advisor unavailable\"; that part only means no diagnosis helper is running.")

OCCUPIED_TARGET = ("Flight placement will not overwrite an occupied target at 8207, -33, -26961: "
                   "actual=Block{minecraft:stone}, chunkReceived=true, worldY=-64..319")
# When recovery gives up and no advisor answers, the mod appends this to the cause.
ADVISOR_UNAVAILABLE = "; Advisor unavailable after deterministic recovery: Companion request unavailable"

# The exact strings the mod builds today, with realistic values in its placeholders.
CASES = [
    ("Join the target world before starting or resuming.", JOIN_WORLD_HINT),
    ("The loaded run belongs to a different server, save, or dimension.", OTHER_WORLD_HINT),
    ("This floating schematic requires flight to already be active before starting.", TAKEOFF_FIRST_HINT),
    ("This floating schematic needs flight, and the server has not granted it. Turn on your server flight "
     "ability, then try again.", FLIGHT_NOT_GRANTED_HINT),
    ("Wait for registered-depot scans to finish.", SCANNING_HINT),
    ("Registered depots cannot satisfy the exact material shortage: {hoe=1}", DEPOT_SHORTAGE_HINT),
    ("Depot withdrawal failed: real chest stock does not cover the allocation at depot-014",
     CHEST_SHORT_HINT),
    ("Inventory capacity blocked: dirt shortage=64, empty main slots=0, compatible dirt capacity=0, "
     "reserved main slots=0, scanned depot dirt=512. Whole-stack shop purchases need an empty main slot; "
     "the exact depot refill does not fit. Resume becomes available when the shortage is supplied or a "
     "refill route has room.", INVENTORY_FULL_HINT),
    ("The checkpoint requires reconciliation; use an explicit reset before restarting.", RESET_HINT),
    ("A local protocol token is required to activate automation.", PAIRING_HINT),
    ("Manual movement input interrupted flight construction", MOVEMENT_HINT),
    ("The supervisor runtime is closed.", RESTART_HINT),
    ("The last dirt purchase was not acknowledged. Waiting for its matching inventory update; no further "
     "purchase will be sent. If it does not arrive, inspect the inventory and restart the client before "
     "continuing.", DIRT_PURCHASE_HINT),
    ("Takeoff requires flight permission already granted by the server.", FLIGHT_PERMISSION_HINT),
    (OCCUPIED_TARGET, OCCUPIED_TARGET_HINT),
    # A cause with its own rule keeps its hint; the advisor hint covers the other causes.
    (OCCUPIED_TARGET + ADVISOR_UNAVAILABLE, OCCUPIED_TARGET_HINT),
    ("Manual movement input interrupted flight construction" + ADVISOR_UNAVAILABLE, MOVEMENT_HINT),
    ("No build progress for 15 seconds" + ADVISOR_UNAVAILABLE, ADVISOR_HINT),
    # Recovery outcomes the mod appends; a retry wins over the advisor text it also carries.
    ("The server did not confirm the cleared replacement target within the bounded attempt; Return to safe "
     "position failed: no route" + ADVISOR_UNAVAILABLE + "; retrying automatically in 30 s (1 of 3)", RETRYING_HINT),
    ("Active flight was lost; construction stopped before further interaction; Flight was restored "
     "automatically", FLIGHT_RESTORED_HINT),
    ("Active flight was lost; construction stopped before further interaction; Flight could not be restored: "
     "Takeoff requires flight permission already granted by the server." + ADVISOR_UNAVAILABLE,
     FLIGHT_NOT_RESTORED_HINT),
]


class HintTests(unittest.TestCase):
    def test_known_messages_get_their_hint(self):
        for message, expected in CASES:
            with self.subTest(message):
                self.assertEqual(hint_for(message), expected)

    def test_every_rule_is_the_first_match_for_a_case(self):
        hints = [rule.hint for rule in RULES]
        # Distinct hints mean the hint returned names the rule that matched first.
        self.assertEqual(len(set(hints)), len(hints))
        self.assertEqual({expected for _, expected in CASES}, set(hints))

    def test_unknown_or_empty_messages_have_no_hint(self):
        for message in (None, "", "   ", "Something new happened.", 42):
            self.assertIsNone(hint_for(message))

    def test_whitespace_is_normalized_before_matching(self):
        self.assertEqual(hint_for("  Join the target world before\nstarting or resuming.  "), JOIN_WORLD_HINT)


if __name__ == "__main__":
    unittest.main()
