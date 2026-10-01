"""Fix hints for known mod messages. Unknown messages get no hint and are shown as written."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable


@dataclass(frozen=True)
class HintRule:
    matches: Callable[[str], bool]
    hint: str


def _exact(text: str) -> Callable[[str], bool]:
    return lambda message: message == text


def _prefix(text: str) -> Callable[[str], bool]:
    return lambda message: message.startswith(text)


def _contains(text: str) -> Callable[[str], bool]:
    return lambda message: text in message


RULES: tuple[HintRule, ...] = (
    HintRule(_exact("Join the target world before starting or resuming."),
             "Open Minecraft and join the world this build belongs to."),
    HintRule(_exact("The loaded run belongs to a different server, save, or dimension."),
             "You're in another world or dimension. Go back to the build's world, "
             "or run /schematic-supervisor unload in game."),
    # Mods before 0.2.0 asked for a manual takeoff; newer ones take off by themselves on Start or Resume.
    HintRule(_contains("requires flight to already be active"),
             "Run /schematic-supervisor takeoff in game, then Continue."),
    HintRule(_prefix("This floating schematic needs flight, and the server has not granted it."),
             "Turn on your server flight ability, then Continue. The mod takes off by itself."),
    HintRule(_exact("Wait for registered-depot scans to finish."),
             "Chests are still being scanned. This clears on its own."),
    HintRule(_prefix("Registered depots cannot satisfy the exact material shortage:"),
             "Put the missing items in a registered chest, then press Scan depots."),
    HintRule(_prefix("Depot withdrawal failed: real chest stock does not cover"),
             "A chest held less than expected. Restock it, then press Scan depots."),
    HintRule(_prefix("Inventory capacity blocked:"),
             "The inventory is too full. The mod's cleanup usually clears this; "
             "if not, free some slots in game."),
    HintRule(_exact("The checkpoint requires reconciliation; use an explicit reset before restarting."),
             "Check the inventory in game, then run /schematic-supervisor reset. This discards the "
             "saved position; placed blocks stay and are re-checked."),
    HintRule(_exact("A local protocol token is required to activate automation."),
             "Restart Minecraft; the mod creates its pairing file at startup."),
    HintRule(_contains("Manual movement input interrupted"),
             "A movement key was pressed during flight. Release it, then Continue."),
    HintRule(_exact("The supervisor runtime is closed."), "Restart Minecraft."),
    HintRule(_prefix("The last dirt purchase was not acknowledged."),
             "A dirt purchase hasn't been confirmed yet. Wait a moment; if it doesn't clear, "
             "check the inventory in game and restart Minecraft."),
    HintRule(_exact("Takeoff requires flight permission already granted by the server."),
             "The server hasn't granted flight. Turn on your server flight ability, "
             "then run /schematic-supervisor takeoff."),
    HintRule(_prefix("Flight placement will not overwrite an occupied target at "),
             "A block that isn't in the plan sits on a target (position in the message). "
             "Remove it in game if that's safe, then Continue."),
    # The recovery outcomes below are appended to a cause that has no rule of its own.
    HintRule(_contains("retrying automatically in"),
             "The mod waits and tries again by itself. Nothing to do unless it pauses."),
    HintRule(_contains("Flight was restored automatically"),
             "Flight dropped and the mod took off again by itself. Nothing to do."),
    HintRule(_contains("Flight could not be restored"),
             "Flight dropped and the mod couldn't take off again. Check that your server flight "
             "ability is on, then Continue."),
    # Last: the mod appends this text to other causes, and a cause with its own rule keeps its hint.
    HintRule(_contains("Advisor unavailable after deterministic recovery"),
             "The mod's own recovery didn't fix this. The cause is the text before "
             "\"Advisor unavailable\"; that part only means no diagnosis helper is running."),
)


def hint_for(message: Any) -> str | None:
    if not isinstance(message, str) or not message.strip():
        return None
    text = " ".join(message.split())
    for rule in RULES:
        if rule.matches(text):
            return rule.hint
    return None
