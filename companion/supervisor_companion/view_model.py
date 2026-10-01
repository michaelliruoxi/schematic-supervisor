"""Display values for the monitor window, computed from a MonitorService snapshot.

Every function tolerates unexpected shapes: telemetry is data, and a display problem must never
stop the window from refreshing.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import math
from typing import Any, Mapping, Sequence

from .hints import hint_for
from .pace import Pace, format_remaining
from .progress import Layout, Progress, Unavailable, approximate_from_layer, chunk_shares

ACTIVE_STATES = frozenset({"BUILDING", "RESTOCKING", "VERIFYING", "LOADING", "CHECKING"})
WORKING_STATES = ACTIVE_STATES | {"STUCK"}
PROGRESS_STATES = frozenset({"BUILDING", "RESTOCKING", "STUCK"})
ATTENTION_STATES = frozenset({"PAUSED", "STUCK", "ERROR"})
SLOW_PROGRESS_SECONDS = 120

STATE_LABELS = {
    "BUILDING": ("Building", "ok"), "RESTOCKING": ("Restocking", "ok"), "VERIFYING": ("Verifying", "ok"),
    "LOADING": ("Loading", "ok"), "CHECKING": ("Checking", "ok"), "STUCK": ("Recovering", "warn"),
    "PAUSED": ("Paused", "warn"), "ERROR": ("Error", "error"), "DONE": ("Done", "done"), "IDLE": ("Idle", "idle"),
    "STOPPED": ("Stopped", "idle"),
}
CONNECTION_LABELS = {
    "refused": "Not running", "timeout": "Not responding", "reset": "Connection dropped",
    "unreachable": "Not reachable", "not_ready": "Starting", "token_missing": "Pairing needed",
    "token_rejected": "Pairing rejected", "invalid": "Version mismatch", "stale": "Stale",
    "http_error": "Error",
}
KIND_LABELS = {"STRUCTURE": "Structure", "LIGHTING": "Lights", "TILL": "Till", "PLANT": "Plant"}
KIND_GROUPS = {"STRUCTURE": "Build", "LIGHTING": "Build", "TILL": "Farm", "PLANT": "Farm"}
ACTION_LABELS = {"START": "Start", "PAUSE": "Pause", "RESUME": "Continue", "STOP": "Stop",
                 "SCAN_DEPOTS": "Scan depots", "RESET": "Reset", "UNLOAD": "Unload"}
UNAVAILABLE_LABELS = {"No plan is loaded.": "No build loaded", "The plan is loading.": "Loading the plan"}
TOO_MANY_STAGES = "The plan has too many stages to report progress."
CELL_STATUS = {"D": "done", "C": "current", "-": "todo", ".": "none"}
CELL_LABELS = {"done": "Done", "current": "Being built", "todo": "To do", "none": "No work at this stage"}
FULL_ACTIONS = ("START", "PAUSE", "RESUME", "STOP", "SCAN_DEPOTS")


@dataclass(frozen=True)
class Button:
    action: str
    label: str
    enabled: bool
    reason: str = ""


@dataclass(frozen=True)
class Segment:
    status: str
    tooltip: str


@dataclass(frozen=True)
class AttentionItem:
    text: str
    hint: str | None
    tone: str


@dataclass(frozen=True)
class Cell:
    status: str
    share: float | None
    tooltip: str


@dataclass(frozen=True)
class CardView:
    title: str
    state_label: str
    tone: str
    status_text: str
    status_tone: str
    percent_text: str
    percent_suffix: str
    verify_text: str
    segments: tuple[Segment, ...]
    kind_labels: tuple[tuple[str, int], ...]
    line_text: str
    line_tone: str
    toggle: Button | None
    scan: Button
    stale: bool


@dataclass(frozen=True)
class FullView:
    card: CardView
    pace_text: str
    last_control_text: str
    attention: tuple[AttentionItem, ...]
    selected_stage: int | None
    following_current: bool
    stage_caption: str
    map_mode: str
    map_columns: int
    map_rows: int
    cells: tuple[Cell, ...]
    map_message: str
    buttons: tuple[Button, ...]
    control_message: str


def build_card(snapshot: Mapping[str, Any], *, now: datetime | None = None) -> CardView:
    now = now or datetime.now(timezone.utc)
    observation = _mapping(snapshot.get("observation"))
    fresh = snapshot.get("fresh") is True
    paired = snapshot.get("paired") is True
    state = _state(observation)
    label, tone = _state_label(snapshot, state, fresh)
    status_text, status_tone = _status_text(snapshot, observation, state, fresh, now)
    percent_text, percent_suffix, fraction = _percent(snapshot, observation, fresh)
    progress = snapshot.get("progress")
    kinds = [stage.kind for stage in progress.stages] if isinstance(progress, Progress) else []
    line_text, line_tone = _card_line(snapshot, observation, state, fresh, paired)
    if state in WORKING_STATES:
        toggle: Button | None = _button("PAUSE", snapshot, observation, fresh, paired)
    elif state == "PAUSED":
        toggle = _button("RESUME", snapshot, observation, fresh, paired)
    else:
        toggle = None
    title = (f"{label} · {percent_text} · Schematic Supervisor" if fraction is not None
             else f"{label} · Schematic Supervisor")
    return CardView(title, label, tone, status_text, status_tone, percent_text, percent_suffix,
                    _verify_text(observation), _segments(snapshot, observation), kind_runs(kinds),
                    line_text, line_tone, toggle, _button("SCAN_DEPOTS", snapshot, observation, fresh, paired),
                    not fresh and bool(observation))


def build_full(snapshot: Mapping[str, Any], *, now: datetime | None = None,
               selected_stage: int | None = None, map_mode: str = "stage",
               preview_stage: int | None = None) -> FullView:
    """The map shows `preview_stage`, the stage under the pointer on the timeline, in place of the selected or
    current stage, which stays selected. The All stages map ignores it."""
    now = now or datetime.now(timezone.utc)
    card = build_card(snapshot, now=now)
    observation = _mapping(snapshot.get("observation"))
    fresh = snapshot.get("fresh") is True
    paired = snapshot.get("paired") is True
    state = _state(observation)
    mode = "all" if map_mode == "all" else "stage"
    index, following, caption, columns, rows, cells, message = _project(snapshot, selected_stage, mode,
                                                                        preview_stage)
    own = snapshot.get("own_request_ids")
    own_ids = own if isinstance(own, (list, tuple)) else ()
    return FullView(card, _pace_text(snapshot, state, fresh), _last_control_text(observation, own_ids),
                    _attention(snapshot, observation, state, fresh, paired), index, following, caption, mode,
                    columns, rows, cells, message,
                    tuple(_button(action, snapshot, observation, fresh, paired) for action in FULL_ACTIONS),
                    _control_message(snapshot))


def kind_label(kind: Any) -> str:
    if not isinstance(kind, str) or not kind:
        return ""
    return KIND_LABELS.get(kind, kind.title())


def kind_runs(kinds: Sequence[str]) -> tuple[tuple[str, int], ...]:
    """Labels for consecutive stages of the same kind; broader groups when the schedule interleaves."""
    runs = _runs([kind_label(kind) for kind in kinds])
    if len(runs) <= 4:
        return runs
    grouped = _runs([KIND_GROUPS.get(kind, kind_label(kind)) for kind in kinds])
    return grouped if len(grouped) <= 4 else ()


def _runs(labels: Sequence[str]) -> tuple[tuple[str, int], ...]:
    runs: list[tuple[str, int]] = []
    for label in labels:
        if runs and runs[-1][0] == label:
            runs[-1] = (label, runs[-1][1] + 1)
        else:
            runs.append((label, 1))
    return tuple(runs)


def _state_label(snapshot: Mapping[str, Any], state: str | None, fresh: bool) -> tuple[str, str]:
    if not fresh:
        connection = snapshot.get("connection")
        if connection == "connecting":
            return "Connecting…", "offline"
        if connection == "stopped":
            return "Closed", "offline"
        if connection == "configuration_error":
            # It has no detail code; the bridge reports it only for an unreadable or invalid pairing token.
            return "Pairing invalid", "offline"
        if connection == "error":
            # The monitor's own read failed, not the connection to the mod; the log has the details.
            return "Error", "offline"
        return CONNECTION_LABELS.get(_text(snapshot.get("detail"), ""), "Offline"), "offline"
    if state in STATE_LABELS:
        return STATE_LABELS[state]
    return (state.title() if state else "Unknown"), "idle"


def _status_text(snapshot: Mapping[str, Any], observation: Mapping[str, Any], state: str | None,
                 fresh: bool, now: datetime) -> tuple[str, str]:
    if not fresh:
        return "", "muted"
    if state in PROGRESS_STATES:
        value = observation.get("last_progress_at")
        if value is None:
            # null means no progress since the plan loaded; an older mod doesn't send the field at all.
            return ("No progress yet" if "last_progress_at" in observation else ""), "muted"
        age = _seconds_since(value, now)
        if age is None:
            return "", "muted"
        return f"Progress {_age_text(age)} ago", ("warn" if age >= SLOW_PROGRESS_SECONDS else "muted")
    if state == "VERIFYING":
        return "Checking the build", "muted"
    if state == "CHECKING":
        progress = build_check(observation).get("progress")
        if isinstance(progress, (int, float)) and not isinstance(progress, bool) and 0 <= progress <= 1:
            return f"Checking {math.floor(progress * 100)}%", "muted"
        return "Checking", "muted"
    if state == "LOADING":
        loading = observation.get("loading_progress")
        if isinstance(loading, (int, float)) and not isinstance(loading, bool) and 0 <= loading <= 1:
            return f"Loading {math.floor(loading * 100)}%", "muted"
        return "Loading", "muted"
    if state == "DONE":
        return "Finished", "muted"
    since = _seconds_since(snapshot.get("state_since"), now)
    return (f"for {_age_text(since)}" if since is not None else ""), "muted"


def _percent(snapshot: Mapping[str, Any], observation: Mapping[str, Any],
             fresh: bool) -> tuple[str, str, float | None]:
    progress = snapshot.get("progress")
    kept = snapshot.get("progress_status") == "error"
    last_known = " (last known)" if (not fresh and observation) or kept else ""
    if isinstance(progress, Progress):
        whole = 100 if progress.done_actions >= progress.total_actions else math.floor(progress.fraction * 100)
        return f"{whole}%", "built" + last_known, progress.fraction
    unavailable = progress.reason if (isinstance(progress, Unavailable)
                                      and snapshot.get("progress_status") == "unavailable") else None
    approximate = approximate_from_layer(observation.get("current_layer"))
    # A plan too large to report still has a current layer, so it gets the approximate %, like an older mod.
    if unavailable is not None and (unavailable != TOO_MANY_STAGES or approximate is None):
        return "—", UNAVAILABLE_LABELS.get(unavailable, unavailable), None
    if approximate is not None:
        return (f"≈ {math.floor(approximate.fraction * 100)}%", "built (approximate)" + last_known,
                approximate.fraction)
    return "—", ("No build loaded" if observation else "Waiting for Minecraft"), None


def _segments(snapshot: Mapping[str, Any], observation: Mapping[str, Any]) -> tuple[Segment, ...]:
    progress = snapshot.get("progress")
    if isinstance(progress, Progress):
        return tuple(Segment(progress.stage_status(index),
                             f"Stage {index + 1} · {kind_label(stage.kind)} · Y {stage.y} · "
                             f"{math.floor(stage.fraction * 100)}%")
                     for index, stage in enumerate(progress.stages))
    approximate = approximate_from_layer(observation.get("current_layer"))
    if approximate is None:
        return ()
    finished = approximate.fraction >= 1.0
    segments = []
    for index in range(approximate.stages):
        if finished or index < approximate.stage - 1:
            status = "done"
        elif index == approximate.stage - 1:
            status = "current"
        else:
            status = "todo"
        segments.append(Segment(status, f"Stage {index + 1}"))
    return tuple(segments)


def _verify_text(observation: Mapping[str, Any]) -> str:
    passes = _int(observation.get("stable_verification_passes"))
    if passes is None or not observation.get("plan_id"):
        return ""
    return f"Verify {min(max(passes, 0), 2)}/2"


def _card_line(snapshot: Mapping[str, Any], observation: Mapping[str, Any], state: str | None,
               fresh: bool, paired: bool) -> tuple[str, str]:
    if not fresh:
        return _text(snapshot.get("message"), "Waiting for Minecraft."), "warn"
    error = _text(observation.get("last_error"), "")
    blockers = _strings(observation.get("blockers")) if state not in ACTIVE_STATES else []
    if state in ATTENTION_STATES or blockers:
        tone = "error" if state == "ERROR" else "warn"
        candidates: list[str | None] = [hint_for(error) if error else None]
        candidates += [hint_for(blocker) for blocker in blockers]
        candidates += [error or None, *blockers, _pairing_text(observation, paired) or None]
        for candidate in candidates:
            if candidate:
                return candidate, tone
    return _now_line(observation, state), "muted"


def _now_line(observation: Mapping[str, Any], state: str | None) -> str:
    if not observation:
        return "Waiting for Minecraft."
    if state == "DONE":
        return "Build complete and verified."
    if state == "LOADING":
        return "Loading the plan."
    if state == "CHECKING":
        return "Checking what is already built before starting."
    layer = _mapping(observation.get("current_layer"))
    stage = layer.get("stage") if isinstance(layer.get("stage"), str) else None
    if state == "VERIFYING" or stage in ("VERIFY", "DONE"):
        return "Now: checking the finished build."
    if stage and _int(layer.get("index")) is not None:
        parts = [f"Now: {kind_label(stage)}"]
        y = _int(layer.get("y"))
        if y is not None:
            parts.append(f"Y {y}")
        chunk, chunks = _int(layer.get("chunk_index")), _int(layer.get("chunk_total"))
        if chunk is not None and chunks:
            parts.append(f"chunk {chunk} of {chunks}")
        return " · ".join(parts)
    return _text(observation.get("last_message"), "No build loaded.")


def _pairing_text(observation: Mapping[str, Any], paired: bool) -> str:
    if observation.get("control_token_configured") is False:
        return ("The mod has no pairing token. Start, Continue, and Scan depots are unavailable; "
                "Pause and Stop work.")
    if not paired:
        return "The monitor can't read status or send controls without the pairing file."
    return ""


def _attention(snapshot: Mapping[str, Any], observation: Mapping[str, Any], state: str | None,
               fresh: bool, paired: bool) -> tuple[AttentionItem, ...]:
    notes = _progress_notes(snapshot)
    if not fresh:
        return (AttentionItem(_text(snapshot.get("message"), "Waiting for Minecraft."), None, "warn"),) + notes
    items: list[AttentionItem] = []
    error = _text(observation.get("last_error"), "")
    if error:
        if state in ATTENTION_STATES or state == "STOPPED":
            items.append(AttentionItem(error, hint_for(error), "error" if state == "ERROR" else "warn"))
        elif state in ACTIVE_STATES:
            items.append(AttentionItem(f"Last error (may be old): {error}", None, "muted"))
    if state not in ACTIVE_STATES:
        items.extend(AttentionItem(blocker, hint_for(blocker), "warn")
                     for blocker in _strings(observation.get("blockers")))
    pairing = _pairing_text(observation, paired)
    if pairing:
        items.append(AttentionItem(pairing, None, "warn"))
    items.extend(_check_attention(observation))
    return tuple(items) + notes


def build_check(observation: Mapping[str, Any]) -> Mapping[str, Any]:
    """The mod's start build check: running, or the last one's result. Empty for older mods."""
    return _mapping(observation.get("build_check"))


def problem_text(problem: Any) -> str | None:
    """One block the builder won't fix, such as "extra cobblestone at x 8198, y -40, z -26920 (chunk 12)"."""
    problem = _mapping(problem)
    x, y, z = (_int(problem.get(axis)) for axis in ("x", "y", "z"))
    if x is None or y is None or z is None:
        return None
    chunk = _int(problem.get("chunk"))
    where = f"x {x}, y {y}, z {z}" + (f" (chunk {chunk})" if chunk is not None else "")
    actual = _block_name(problem.get("actual"))
    if problem.get("kind") == "EXTRA":
        return f"extra {actual} at {where}"
    return f"{actual} instead of {_block_name(problem.get('expected'))} at {where}"


def _check_attention(observation: Mapping[str, Any]) -> list[AttentionItem]:
    check = build_check(observation)
    status = check.get("status")
    if status == "FAILED":
        return [AttentionItem(_text(check.get("summary"), "The build check failed."), None, "muted")]
    if status != "COMPLETE":
        return []
    items: list[AttentionItem] = []
    wrong = _left(check, "wrong_blocks", "wrong_blocks_cleared")
    extra = _left(check, "extra_blocks", "extra_blocks_cleared")
    if wrong or extra:
        found = [_plural(count, noun) for count, noun in ((wrong, "wrong block"), (extra, "extra block")) if count]
        problems = [text for text in (problem_text(item) for item in _list(check.get("problems"))) if text]
        hint = "Replace or remove them, or final verification will fail."
        if problems:
            hint += f" First: {problems[0]}."
        if len(problems) > 1:
            hint += " Status details lists more."
        items.append(AttentionItem("The build check found " + " and ".join(found) + " that the builder won't fix.",
                                   hint, "warn"))
    total, checked = _int(check.get("chunks_total")), _int(check.get("chunks_checked"))
    if total is not None and checked is not None and 0 <= checked < total:
        missing = total - checked
        items.append(AttentionItem(
            f"{_plural(missing, 'chunk')} {'was' if missing == 1 else 'were'} out of range during the build check; "
            f"the builder checks {'it' if missing == 1 else 'them'} when it gets there.", None, "muted"))
    return items


def _left(check: Mapping[str, Any], total_key: str, cleared_key: str) -> int:
    total, cleared = _int(check.get(total_key)), _int(check.get(cleared_key))
    return max(0, (total or 0) - (cleared or 0))


def _plural(count: int, noun: str) -> str:
    return f"{count:,} {noun}" + ("" if count == 1 else "s")


def _block_name(value: Any) -> str:
    name = _text(value, "unknown block")
    return name.split(":", 1)[-1].replace("_", " ")


def _list(value: Any) -> list:
    return value if isinstance(value, list) else []


def _progress_notes(snapshot: Mapping[str, Any]) -> tuple[AttentionItem, ...]:
    status = snapshot.get("progress_status")
    if status == "unsupported":
        return (AttentionItem("Update the mod for exact progress.", None, "muted"),)
    if status == "error" and isinstance(snapshot.get("progress"), Progress):
        # The last progress stays on screen as last known; without one, the map explains the gap.
        return (AttentionItem("Progress data unavailable.", None, "muted"),)
    return ()


def _button(action: str, snapshot: Mapping[str, Any], observation: Mapping[str, Any],
            fresh: bool, paired: bool) -> Button:
    label = ACTION_LABELS[action]
    # A refused or unreadable token stops every control, whatever an older observation said about the mod's
    # token. Not `paired`: it is also False before the first poll and after unexpected read errors.
    if snapshot.get("connection") in ("unauthorized", "configuration_error"):
        return Button(action, label, False, "Controls need a working pairing file.")
    pending = _pending(snapshot)
    if action in ("PAUSE", "STOP"):
        if action in pending:
            return Button(action, label, False, "Waiting for the previous control to finish.")
        return Button(action, label, True)
    allowed = observation.get("allowed_actions")
    allowed = allowed if isinstance(allowed, list) else []
    if not fresh:
        return Button(action, label, False, "Waiting for fresh status from Minecraft.")
    if not paired:
        return Button(action, label, False, "The monitor needs the pairing file for this.")
    if pending:
        return Button(action, label, False, "Waiting for the previous control to finish.")
    if action not in allowed:
        return Button(action, label, False, "The mod doesn't allow this right now.")
    return Button(action, label, True)


def _pace_text(snapshot: Mapping[str, Any], state: str | None, fresh: bool) -> str:
    pace = snapshot.get("pace")
    # While progress can't be read, the tracker keeps sampling the last known count, so the rate decays.
    if (not isinstance(pace, Pace) or not fresh or state not in PROGRESS_STATES
            or snapshot.get("progress_status") != "ok"):
        return ""
    text = f"{round(pace.per_minute):,} per min"
    if pace.remaining_seconds is not None:
        text += f" · ≈ {format_remaining(pace.remaining_seconds)} left, excluding verification"
    return text


def _last_control_text(observation: Mapping[str, Any], own_ids: Sequence[Any]) -> str:
    control = _mapping(observation.get("last_control"))
    action = control.get("action")
    if not isinstance(action, str) or not action:
        return ""
    request_id = control.get("request_id")
    if request_id is None:
        source = "in game"
    elif isinstance(request_id, str) and (request_id.startswith("monitor-") or request_id in own_ids):
        source = "this monitor"
    elif isinstance(request_id, str) and request_id.startswith("agent-"):
        source = "the AI runner"
    else:
        source = "another tool"
    # Actions without a label, such as the mod's own operator commands, read in sentence case.
    label = ACTION_LABELS.get(action, action.replace("_", " ").capitalize())
    return f"Last control: {label} · from {source}"


def _project(snapshot: Mapping[str, Any], selected_stage: int | None, mode: str, preview_stage: int | None):
    progress = snapshot.get("progress")
    status = snapshot.get("progress_status")
    if not isinstance(progress, Progress):
        if status == "unsupported":
            message = "Update the mod to see the chunk map."
        elif isinstance(progress, Unavailable):
            message = UNAVAILABLE_LABELS.get(progress.reason, progress.reason.rstrip(".")) + "."
        elif status == "error":
            message = "Progress data is unavailable. Details are in the log."
        else:
            message = "Waiting for progress data."
        return None, True, "", 0, 0, (), message
    if not progress.stages:
        return None, True, "", 0, 0, (), "This plan has no work to show."
    last = len(progress.stages) - 1
    current = progress.current_stage - 1 if progress.current_stage is not None else last
    following = not _is_stage(selected_stage, last)
    index = current if following else selected_stage
    layout = progress.layout
    if mode == "all":
        caption = "All stages · share of each chunk's stages finished"
        shares = chunk_shares(progress)
        if shares is None:
            return (index, following, caption, layout.columns, layout.rows, (),
                    "Chunk detail isn't available for this plan.")
        cells = tuple(_share_cell(layout, cell, share) for cell, share in enumerate(shares))
        return index, following, caption, layout.columns, layout.rows, cells, ""
    shown = preview_stage if _is_stage(preview_stage, last) else index
    stage = progress.stages[shown]
    caption = (f"Stage {shown + 1} of {len(progress.stages)} · {kind_label(stage.kind)} · Y {stage.y} · "
               f"{math.floor(stage.fraction * 100)}%")
    if stage.chunks is None:
        return (index, following, caption, layout.columns, layout.rows, (),
                "Chunk detail isn't available for this stage.")
    cells = tuple(_status_cell(layout, cell, letter) for cell, letter in enumerate(stage.chunks))
    return index, following, caption, layout.columns, layout.rows, cells, ""


def _is_stage(value: Any, last: int) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= last


def _status_cell(layout: Layout, index: int, letter: str) -> Cell:
    status = CELL_STATUS.get(letter, "none")
    x, z = layout.chunk_coordinates(index)
    return Cell(status, None, f"Chunk {index + 1} · x {x * 16}, z {z * 16} · {CELL_LABELS[status]}")


def _share_cell(layout: Layout, index: int, share: float | None) -> Cell:
    x, z = layout.chunk_coordinates(index)
    place = f"Chunk {index + 1} · x {x * 16}, z {z * 16}"
    if share is None:
        return Cell("none", None, f"{place} · No work")
    return Cell("share", share, f"{place} · {math.floor(share * 100)}% of its stages finished")


def _control_message(snapshot: Mapping[str, Any]) -> str:
    pending = _pending(snapshot)
    if pending:
        return "Waiting for acknowledgment: " + ", ".join(ACTION_LABELS.get(action, action)
                                                          for action in pending) + "."
    return _text(snapshot.get("control_message"), "")


def _pending(snapshot: Mapping[str, Any]) -> list[str]:
    pending = snapshot.get("pending_actions")
    return [action for action in pending if isinstance(action, str)] if isinstance(pending, list) else []


def _state(observation: Mapping[str, Any]) -> str | None:
    state = observation.get("state")
    return state if isinstance(state, str) and state else None


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, dict) else {}


def _int(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _text(value: Any, fallback: str) -> str:
    return " ".join(value.split()) if isinstance(value, str) and value.strip() else fallback


def _strings(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [" ".join(item.split()) for item in value if isinstance(item, str) and item.strip()]


def _seconds_since(value: Any, now: datetime) -> float | None:
    if not isinstance(value, str):
        return None
    try:
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if moment.tzinfo is None:
        return None
    return max(0.0, (now - moment).total_seconds())


def _age_text(seconds: float) -> str:
    seconds = max(0, int(seconds))
    if seconds < 60:
        return f"{seconds}s"
    minutes = seconds // 60
    if minutes < 60:
        return f"{minutes} m"
    return f"{minutes // 60} h {minutes % 60} m"
