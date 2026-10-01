package io.github.schematicsupervisor.fabric;

/** Separates shop closure, visible player inventory, one discard, and a later receipt request. */
final class PlayerInventoryDiscardSequence {
    enum View { CLOSED, OWNED_INVENTORY, FOREIGN }
    enum Action { WAIT, OPEN_INVENTORY, DISCARD_ONCE, OPEN_RECEIPT, BLOCKED }
    private enum Stage { CLOSING, INVENTORY, DISPATCH, SETTLING, RECEIPT, BLOCKED }
    private Stage stage = Stage.CLOSING;
    private int ticks;

    Action tick(View view, boolean appliedEmptySlotReceipt) {
        if (stage == Stage.BLOCKED) { return Action.BLOCKED; }
        if (stage == Stage.RECEIPT) { return Action.WAIT; }
        View expected = stage == Stage.CLOSING ? View.CLOSED : View.OWNED_INVENTORY;
        if (view != expected) { stage = Stage.BLOCKED; return Action.BLOCKED; }
        ticks++;
        if (stage == Stage.CLOSING && ticks >= 10) {
            stage = Stage.INVENTORY; ticks = 0; return Action.OPEN_INVENTORY;
        }
        if (stage == Stage.INVENTORY && ticks >= 10) {
            stage = Stage.DISPATCH; ticks = 0; return Action.DISCARD_ONCE;
        }
        if (stage == Stage.SETTLING && (ticks >= 4 && appliedEmptySlotReceipt || ticks >= 40)) {
            // Accepted predicted clicks may have no slot update. The later full receipt still decides the outcome.
            stage = Stage.RECEIPT; return Action.OPEN_RECEIPT;
        }
        return Action.WAIT;
    }

    void dispatched() {
        if (stage != Stage.DISPATCH) { throw new IllegalStateException("Discard was not admitted from the player inventory"); }
        stage = Stage.SETTLING; ticks = 0;
    }
}
