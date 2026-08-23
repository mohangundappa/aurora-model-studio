package com.aurora.studio.initiative;

import java.util.Locale;
import java.util.Set;

final class InitiativeActors {
  static final String ORCHESTRATOR = "initiative-orchestrator";
  private static final Set<String> MACHINE_IDENTITIES = Set.of(ORCHESTRATOR);

  private InitiativeActors() {}

  static boolean isMachineIdentity(String actor) {
    return actor != null && MACHINE_IDENTITIES.contains(actor.trim().toLowerCase(Locale.ROOT));
  }
}
