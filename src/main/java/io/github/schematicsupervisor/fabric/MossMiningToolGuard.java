package io.github.schematicsupervisor.fabric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/** Conservative wear allowance shared by identical tools even when their hotbar slots change. */
final class MossMiningToolGuard<T> {
    private static final int MAXIMUM_IDENTITIES = 36;
    private final BiPredicate<T, T> sameIdentity;
    private final List<TrackedTool<T>> tools = new ArrayList<>();
    private final Object tokenOwner = new Object();
    private long chargeSequence;
    private RefreshTicket<T> refreshTicket;
    private boolean custodyInvalid;
    enum Preparation { USE_HOE, REPAIR, WAIT_REPAIR, USE_PLAIN_HAND }
    enum ChargeOutcome { CONFIRMED, UNKNOWN }

    record Durability(int damage, int maximum, boolean unbreakable) {
        Durability {
            if (maximum < 1 || damage < 0 || damage > maximum) {
                throw new IllegalArgumentException("Invalid moss-mining tool durability");
            }
        }
        int remaining() { return maximum - damage; }
        int reserve() { return Math.min(HoeRepairSession.REPAIR_RESERVE, Math.max(1, maximum / 10)); }
    }

    static final class TrackedTool<T> {
        private final T identity;
        private final int maximum;
        private final boolean unbreakable;
        private final Runnable invalidateTicket;
        private final Set<String> resolvedRepairs = new HashSet<>();
        private int allowance;
        private String pendingRepair = "";
        private ChargeToken<T> pendingCharge;
        private boolean refreshDisabled;
        private boolean custodyInvalid;

        private TrackedTool(T identity, Durability durability, Runnable invalidateTicket) {
            this.identity = Objects.requireNonNull(identity);
            this.invalidateTicket = Objects.requireNonNull(invalidateTicket);
            maximum = durability.maximum();
            unbreakable = durability.unbreakable();
            allowance = durability.remaining();
        }

        private boolean compatible(Durability durability) {
            return maximum == durability.maximum() && unbreakable == durability.unbreakable();
        }

        boolean canStart(Durability durability) {
            if (custodyInvalid || pendingCharge != null || !compatible(durability) || !pendingRepair.isEmpty()) { return false; }
            // Local or delayed inventory updates can reduce, but never replenish, this allowance.
            allowance = Math.min(allowance, durability.remaining());
            return unbreakable || allowance > durability.reserve();
        }

        Preparation prepare(Durability durability, boolean repairEnabled) {
            if (custodyInvalid || pendingCharge != null) { return Preparation.USE_PLAIN_HAND; }
            if (!pendingRepair.isEmpty()) { return Preparation.WAIT_REPAIR; }
            if (canStart(durability)) { return Preparation.USE_HOE; }
            return repairEnabled && compatible(durability) && !durability.unbreakable()
                    && durability.damage() > 0 && durability.remaining() > 0
                    && durability.remaining() <= durability.reserve()
                    ? Preparation.REPAIR : Preparation.USE_PLAIN_HAND;
        }

        void awaitRepair(String operationId) {
            if (pendingCharge != null || operationId == null || operationId.isBlank() || resolvedRepairs.contains(operationId)
                    || !pendingRepair.isEmpty() && !pendingRepair.equals(operationId)) {
                throw new IllegalArgumentException("Moss-mining wear requires its original unresolved repair intent");
            }
            invalidateTicket.run();
            pendingRepair = operationId;
        }

        boolean acceptRepair(String operationId, Durability durability) {
            if (operationId == null || operationId.isBlank() || durability.damage() != 0
                    || !pendingRepair.equals(operationId) || !compatible(durability)
                    || !resolvedRepairs.add(operationId)) { return false; }
            // A repair receipt settles this binding only. A later covered depot receipt may refresh clean wear.
            invalidateTicket.run();
            pendingRepair = "";
            return true;
        }

        boolean abandonCompletedRepair(HoeRepairSession.Status status, String operationId) {
            if (status != HoeRepairSession.Status.READY || !pendingRepair.equals(operationId)
                    || pendingRepair.isEmpty()) { return false; }
            // Completing the durable repair may abandon this optional optimization, never add wear credit.
            resolvedRepairs.add(operationId);
            invalidateTicket.run();
            pendingRepair = "";
            return true;
        }

        int allowance() { return allowance; }
        boolean refreshDisabled() { return refreshDisabled; }
    }

    static final class ChargeToken<T> {
        private final Object owner;
        private final TrackedTool<T> tracked;
        private final long sequence;

        private ChargeToken(Object owner, TrackedTool<T> tracked, long sequence) {
            this.owner = owner;
            this.tracked = tracked;
            this.sequence = sequence;
        }

        long sequence() { return sequence; }
    }

    record OwnedTool<T>(int slot, TrackedTool<T> tracked, int startingDamage, ChargeToken<T> charge) { }

    /** Caller supplies copied exact identities for the complete main-inventory hoe cohort. */
    record CohortMember<T>(int slot, T identity, Durability durability) {
        CohortMember {
            if (slot < 0 || slot >= 36) { throw new IllegalArgumentException("Invalid main-inventory tool slot"); }
            Objects.requireNonNull(identity);
            Objects.requireNonNull(durability);
        }
    }

    static final class RefreshTicket<T> {
        private final List<CohortMember<T>> cohort;

        private RefreshTicket(List<CohortMember<T>> cohort) { this.cohort = List.copyOf(cohort); }
    }

    MossMiningToolGuard(BiPredicate<T, T> sameIdentity) {
        this.sameIdentity = Objects.requireNonNull(sameIdentity);
    }

    record Diagnostic(boolean identityTracked, int trackedIdentities, int identityCap,
                      Integer allowance, Integer effectiveAllowance, int reserve, String reason) { }

    /** Observes existing accounting without admitting an identity or clamping its allowance. */
    Diagnostic diagnostic(T identity, Durability durability) {
        for (TrackedTool<T> tracked : tools) {
            if (sameIdentity.test(tracked.identity, identity) && tracked.compatible(durability)) {
                int effective = Math.min(tracked.allowance, durability.remaining());
                String reason = custodyInvalid ? "CUSTODY_INVALIDATED"
                        : tracked.pendingCharge != null ? "WAIT_CHARGE"
                        : !tracked.pendingRepair.isEmpty() ? "WAIT_REPAIR"
                        : durability.unbreakable() || effective > durability.reserve() ? "ELIGIBLE"
                        : durability.remaining() <= durability.reserve() ? "DURABILITY_RESERVE" : "ALLOWANCE_RESERVE";
                return new Diagnostic(true, tools.size(), MAXIMUM_IDENTITIES, tracked.allowance,
                        effective, durability.reserve(), reason);
            }
        }
        return new Diagnostic(false, tools.size(), MAXIMUM_IDENTITIES, null, null, durability.reserve(),
                custodyInvalid ? "CUSTODY_INVALIDATED" : tools.size() >= MAXIMUM_IDENTITIES ? "IDENTITY_LIMIT"
                        : !durability.unbreakable() && durability.remaining() <= durability.reserve()
                        ? "DURABILITY_RESERVE" : "UNTRACKED_AVAILABLE");
    }

    int trackedIdentityCount() { return tools.size(); }
    int identityCap() { return MAXIMUM_IDENTITIES; }

    Optional<TrackedTool<T>> track(T identity, Durability durability) {
        for (TrackedTool<T> tracked : tools) {
            if (sameIdentity.test(tracked.identity, identity) && tracked.compatible(durability)) {
                tracked.canStart(durability);
                return Optional.of(tracked);
            }
        }
        if (custodyInvalid || tools.size() >= MAXIMUM_IDENTITIES) { return Optional.empty(); }
        invalidateRefreshTicket();
        TrackedTool<T> tracked = new TrackedTool<>(identity, durability, this::invalidateRefreshTicket);
        tools.add(tracked);
        return Optional.of(tracked);
    }

    boolean matches(TrackedTool<T> tracked, T identity, Durability durability) {
        return tools.contains(tracked) && tracked.compatible(durability)
                && sameIdentity.test(tracked.identity, identity);
    }

    Optional<OwnedTool<T>> begin(int slot, TrackedTool<T> tracked, T identity, Durability durability) {
        if (custodyInvalid || slot < 0 || slot >= 9 || !matches(tracked, identity, durability)
                || !tracked.canStart(durability)) { return Optional.empty(); }
        if (chargeSequence == Long.MAX_VALUE) {
            invalidateCustody();
            return Optional.empty();
        }
        invalidateRefreshTicket();
        // Charge before START even if the server later rejects it. Unbreaking cannot overdraw wear.
        if (!durability.unbreakable()) { tracked.allowance--; }
        ChargeToken<T> charge = new ChargeToken<>(tokenOwner, tracked, ++chargeSequence);
        tracked.pendingCharge = charge;
        return Optional.of(new OwnedTool<>(slot, tracked, durability.damage(), charge));
    }

    boolean owns(OwnedTool<T> owned, int slot, T identity, Durability durability) {
        return !custodyInvalid && owned != null && owned.slot() == slot
                && owned.tracked().pendingCharge == owned.charge() && matches(owned.tracked(), identity, durability)
                && (durability.unbreakable() || durability.remaining() > 0)
                && durability.damage() >= owned.startingDamage()
                && (long) durability.damage() <= (long) owned.startingDamage() + 1;
    }

    /** A confirmed block receipt releases the pending token, never the conservative debit. */
    boolean settleCharge(ChargeToken<T> charge, ChargeOutcome outcome) {
        Objects.requireNonNull(outcome);
        if (charge == null || charge.owner != tokenOwner || charge.tracked.pendingCharge != charge) { return false; }
        invalidateRefreshTicket();
        charge.tracked.pendingCharge = null;
        if (outcome == ChargeOutcome.UNKNOWN) { charge.tracked.refreshDisabled = true; }
        return true;
    }

    /** One ticket is issued before an owned open; receipt provenance remains the adapter's responsibility. */
    Optional<RefreshTicket<T>> beforeOwnedOpen(List<CohortMember<T>> cohort) {
        invalidateRefreshTicket();
        if (custodyInvalid || !settled() || !validCohort(cohort)) { return Optional.empty(); }
        boolean covered = tools.stream().anyMatch(tool -> !tool.refreshDisabled
                && cohort.stream().anyMatch(member -> matches(tool, member.identity(), member.durability())));
        if (!covered) { return Optional.empty(); }
        refreshTicket = new RefreshTicket<>(cohort);
        return Optional.of(refreshTicket);
    }

    /** Use the owned initial full receipt or a confirmed pickup-deposit reopen, before any next transfer. */
    boolean refreshFromReceipt(RefreshTicket<T> ticket, List<CohortMember<T>> cohort) {
        if (ticket == null || ticket != refreshTicket) { return false; }
        invalidateRefreshTicket();
        if (custodyInvalid || !settled() || !validCohort(cohort) || !sameCohort(ticket.cohort, cohort)) { return false; }
        int[] minimums = new int[tools.size()];
        boolean[] covered = new boolean[tools.size()];
        // Complete all comparisons before changing an allowance; a failed comparator cannot partly refresh.
        for (int index = 0; index < tools.size(); index++) {
            TrackedTool<T> tool = tools.get(index);
            if (tool.refreshDisabled) { continue; }
            int minimum = Integer.MAX_VALUE;
            for (CohortMember<T> member : cohort) {
                if (matches(tool, member.identity(), member.durability())) {
                    minimum = Math.min(minimum, member.durability().remaining());
                    covered[index] = true;
                }
            }
            minimums[index] = minimum;
        }
        boolean refreshed = false;
        for (int index = 0; index < tools.size(); index++) {
            if (!covered[index]) { continue; }
            tools.get(index).allowance = minimums[index];
            refreshed = true;
        }
        return refreshed;
    }

    /** No rearm: unowned mutations make every optional hoe START unavailable for this guard. */
    void invalidateCustody() {
        custodyInvalid = true;
        invalidateRefreshTicket();
        for (TrackedTool<T> tool : tools) { tool.custodyInvalid = true; }
    }

    boolean custodyInvalid() { return custodyInvalid; }

    private void invalidateRefreshTicket() { refreshTicket = null; }

    private boolean settled() {
        return tools.stream().noneMatch(tool -> tool.pendingCharge != null || !tool.pendingRepair.isEmpty());
    }

    private boolean validCohort(List<CohortMember<T>> cohort) {
        if (cohort == null || cohort.isEmpty() || cohort.size() > 36) { return false; }
        boolean[] slots = new boolean[36];
        for (CohortMember<T> member : cohort) {
            if (member == null || slots[member.slot()]) { return false; }
            slots[member.slot()] = true;
        }
        return true;
    }

    private boolean sameCohort(List<CohortMember<T>> before, List<CohortMember<T>> after) {
        if (before.size() != after.size()) { return false; }
        for (CohortMember<T> prior : before) {
            boolean present = after.stream().anyMatch(current -> current.slot() == prior.slot()
                    && sameIdentity.test(prior.identity(), current.identity())
                    && prior.durability().maximum() == current.durability().maximum()
                    && prior.durability().unbreakable() == current.durability().unbreakable());
            if (!present) { return false; }
        }
        return true;
    }
}
