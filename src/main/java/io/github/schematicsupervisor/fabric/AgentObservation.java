package io.github.schematicsupervisor.fabric;

import io.github.schematicsupervisor.core.SupervisorStatus;
import java.time.Instant;
import java.util.List;

/** Immutable client-thread snapshot; HTTP readers never access live game objects. */
record AgentObservation(
        String runId,
        String state,
        Instant updatedAt,
        boolean worldConnected,
        boolean contextMatches,
        boolean controlTokenConfigured,
        List<String> blockers,
        List<String> allowedActions,
        String planId,
        Double loadingProgress,
        SupervisorStatus status,
        String lastError,
        String lastMessage,
        String baritoneStatus,
        long controlSequence,
        String controlAction,
        String controlRequestId,
        InventoryObservation inventory,
        DirtShopObservation shop,
        PlayerObservation player,
        DepotObservation depots,
        ExecutionObservation execution,
        MossDepositObservation mossDeposit,
        MaterialShopObservation materialShop,
        SoilWatchpointObservation soilWatchpoints,
        MinecraftSurplusDisposal.Observation surplusDisposal,
        long progressRevision,
        Instant lastProgressAt,
        BuildCheckObservation buildCheck
) {
    AgentObservation {
        blockers = List.copyOf(blockers);
        allowedActions = List.copyOf(allowedActions);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId, InventoryObservation inventory,
                     DirtShopObservation shop, PlayerObservation player, DepotObservation depots,
                     ExecutionObservation execution, MossDepositObservation mossDeposit,
                     MaterialShopObservation materialShop, SoilWatchpointObservation soilWatchpoints,
                     MinecraftSurplusDisposal.Observation surplusDisposal, long progressRevision,
                     Instant lastProgressAt) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, inventory, shop, player,
                depots, execution, mossDeposit, materialShop, soilWatchpoints, surplusDisposal, progressRevision,
                lastProgressAt, null);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId, InventoryObservation inventory,
                     DirtShopObservation shop, PlayerObservation player, DepotObservation depots,
                     ExecutionObservation execution, MossDepositObservation mossDeposit,
                     MaterialShopObservation materialShop, SoilWatchpointObservation soilWatchpoints,
                     MinecraftSurplusDisposal.Observation surplusDisposal) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, inventory, shop, player,
                depots, execution, mossDeposit, materialShop, soilWatchpoints, surplusDisposal, 0L, null);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId, InventoryObservation inventory,
                     DirtShopObservation shop, PlayerObservation player, DepotObservation depots,
                     ExecutionObservation execution, MossDepositObservation mossDeposit,
                     MaterialShopObservation materialShop, SoilWatchpointObservation soilWatchpoints) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, inventory, shop, player,
                depots, execution, mossDeposit, materialShop, soilWatchpoints, null);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId, InventoryObservation inventory,
                     DirtShopObservation shop, PlayerObservation player, DepotObservation depots,
                     ExecutionObservation execution, MossDepositObservation mossDeposit,
                     MaterialShopObservation materialShop) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, inventory, shop, player,
                depots, execution, mossDeposit, materialShop, null);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId, InventoryObservation inventory,
                     DirtShopObservation shop, PlayerObservation player, DepotObservation depots,
                     ExecutionObservation execution, MossDepositObservation mossDeposit) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, inventory, shop, player,
                depots, execution, mossDeposit, null);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId, InventoryObservation inventory,
                     DirtShopObservation shop, PlayerObservation player, DepotObservation depots,
                     ExecutionObservation execution) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, inventory, shop, player,
                depots, execution, null);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, null, null, null, null, null);
    }

    AgentObservation(String runId, String state, Instant updatedAt, boolean worldConnected,
                     boolean contextMatches, boolean controlTokenConfigured, List<String> blockers,
                     List<String> allowedActions, String planId, Double loadingProgress, SupervisorStatus status,
                     String lastError, String lastMessage, String baritoneStatus, long controlSequence,
                     String controlAction, String controlRequestId, InventoryObservation inventory,
                     DirtShopObservation shop, PlayerObservation player, DepotObservation depots) {
        this(runId, state, updatedAt, worldConnected, contextMatches, controlTokenConfigured,
                blockers, allowedActions, planId, loadingProgress, status, lastError, lastMessage,
                baritoneStatus, controlSequence, controlAction, controlRequestId, inventory, shop, player, depots, null);
    }

    AgentObservation withTelemetry(InventoryObservation inventory, DirtShopObservation shop) {
        return withTelemetry(inventory, shop, null);
    }

    AgentObservation withTelemetry(InventoryObservation inventory, DirtShopObservation shop,
                                   PlayerObservation player) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, buildCheck);
    }

    AgentObservation withDepots(DepotObservation depots) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, buildCheck);
    }

    AgentObservation withExecution(ExecutionObservation execution) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, buildCheck);
    }

    AgentObservation withMossDeposit(MossDepositObservation mossDeposit) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, buildCheck);
    }

    AgentObservation withMaterialShop(MaterialShopObservation materialShop) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, buildCheck);
    }

    AgentObservation withSoilWatchpoints(SoilWatchpointObservation soilWatchpoints) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, buildCheck);
    }

    AgentObservation withSurplusDisposal(MinecraftSurplusDisposal.Observation surplusDisposal) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, buildCheck);
    }

    AgentObservation withProgress(long revision, Instant lastProgress) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, revision, lastProgress, buildCheck);
    }

    AgentObservation withBuildCheck(BuildCheckObservation check) {
        return new AgentObservation(runId, state, updatedAt, worldConnected, contextMatches,
                controlTokenConfigured, blockers, allowedActions, planId, loadingProgress, status,
                lastError, lastMessage, baritoneStatus, controlSequence, controlAction, controlRequestId,
                inventory, shop, player, depots, execution, mossDeposit, materialShop, soilWatchpoints,
                surplusDisposal, progressRevision, lastProgressAt, check);
    }
}
