package io.github.schematicsupervisor.fabric;

/** Distinguishes an active bounded break from a click that must only await acknowledgement. */
final class InteractionTickDispatch {
    enum Kind { CLICK, MOSS_MINING, SUPPORT_MINING }
    enum Next { ADVANCE, WAIT_RECEIPT, WAIT_SCREEN, SETTLE_MOSS, SETTLE_SUPPORT }
    private InteractionTickDispatch() { }

    static Next next(boolean pendingReceipt, Kind kind, boolean screenBlocksActions) {
        if (screenBlocksActions) {
            return switch (kind) {
                case MOSS_MINING -> Next.SETTLE_MOSS;
                case SUPPORT_MINING -> Next.SETTLE_SUPPORT;
                case CLICK -> pendingReceipt ? Next.WAIT_RECEIPT : Next.WAIT_SCREEN;
            };
        }
        return pendingReceipt && kind == Kind.CLICK ? Next.WAIT_RECEIPT : Next.ADVANCE;
    }
}
