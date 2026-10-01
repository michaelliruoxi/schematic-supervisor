package io.github.schematicsupervisor.fabric;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Distinguishes a complete storage failure from an explicitly selected direct-discard policy. */
record SurplusDisposalAuthorization(Mode mode, List<MossDepositJournal.Context> registered,
        List<MossDepositJournal.Observation> chests, MossDepositJournal.Observation finalInventory,
        long oldestObservedAtNanos, long capturedAtNanos) {
    enum Mode { STORAGE_FULL, DIRECT, IN_PLACE_SHOP }

    static final String SHOP_RECEIPT_ID = "shop-inventory-receipt";

    static final int MAXIMUM_REFRESHES = 2;

    boolean canRefresh(boolean pending, int refreshes) {
        return !pending && mode != Mode.STORAGE_FULL && refreshes >= 0 && refreshes < MAXIMUM_REFRESHES;
    }

    SurplusDisposalAuthorization refresh(MossDepositJournal.Observation observed, long nowNanos) {
        if (mode == Mode.STORAGE_FULL || !finalInventory.context().equals(observed.context())
                || !observed.stamp().isLaterReopenThan(finalInventory.stamp())) {
            throw new IllegalArgumentException("Disposal refresh requires a later full receipt in the original context");
        }
        return mode == Mode.IN_PLACE_SHOP ? inPlace(observed, nowNanos) : direct(registered, observed, nowNanos);
    }

    SurplusDisposalAuthorization {
        Objects.requireNonNull(mode); Objects.requireNonNull(finalInventory);
        registered = List.copyOf(registered); chests = List.copyOf(chests);
        if (mode == Mode.STORAGE_FULL) {
            new SurplusStorageExhaustion(registered, chests, finalInventory, oldestObservedAtNanos, capturedAtNanos);
        } else if (registered.isEmpty() || registered.size() > SurplusStorageExhaustion.MAXIMUM_CHESTS
                || new HashSet<>(registered).size() != registered.size()
                || registered.stream().map(MossDepositJournal.Context::depotId).distinct().count() != registered.size()
                || !registered.contains(finalInventory.context()) || !chests.equals(List.of(finalInventory))
                || oldestObservedAtNanos != capturedAtNanos || !finalInventory.slots().cursor().empty()
                || registered.stream().anyMatch(context -> !context.worldIdentityHash().equals(finalInventory.context().worldIdentityHash())
                    || !context.dimension().equals(finalInventory.context().dimension())
                    || !context.planId().equals(finalInventory.context().planId()))
                || finalInventory.slots().main().stream().noneMatch(slot -> slot.canTake() && slot.stack().plainPickup()
                    && SurplusPickupPolicy.allowed(slot.stack().itemId()))) {
            throw new IllegalArgumentException("Direct disposal requires one exact fresh inventory from a registered receipt chest");
        }
        if (mode == Mode.IN_PLACE_SHOP && (registered.size() != 1 || !shopContext(finalInventory.context()))
                || mode != Mode.IN_PLACE_SHOP && registered.stream().anyMatch(SurplusDisposalAuthorization::shopContext)) {
            throw new IllegalArgumentException("Shop receipt authorization must remain separate from physical depot authorization");
        }
    }

    static SurplusDisposalAuthorization afterStorage(SurplusStorageExhaustion proof) {
        return new SurplusDisposalAuthorization(Mode.STORAGE_FULL, proof.registered(), proof.chests(),
                proof.finalInventory(), proof.oldestObservedAtNanos(), proof.capturedAtNanos());
    }

    static SurplusDisposalAuthorization direct(List<MossDepositJournal.Context> registered,
            MossDepositJournal.Observation observed, long nowNanos) {
        return new SurplusDisposalAuthorization(Mode.DIRECT, registered, List.of(observed), observed, nowNanos, nowNanos);
    }

    static SurplusDisposalAuthorization inPlace(MossDepositJournal.Observation observed, long nowNanos) {
        return new SurplusDisposalAuthorization(Mode.IN_PLACE_SHOP, List.of(observed.context()),
                List.of(observed), observed, nowNanos, nowNanos);
    }

    static boolean shopContext(MossDepositJournal.Context context) {
        return SHOP_RECEIPT_ID.equals(context.depotId()) && context.depotX() == 0
                && context.depotY() == 0 && context.depotZ() == 0;
    }
}
