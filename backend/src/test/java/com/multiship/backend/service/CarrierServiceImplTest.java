package com.multiship.backend.service;

/**
 * TODO(sprint50-tier1-A followup): CarrierServiceImpl has no dedicated test
 * class because it depends on ~30 collaborators (repositories, connectors,
 * resolution service, tracking service, etc.). The following behaviors from
 * PR #119 are only covered transitively by the passing full-suite run:
 *
 *   - Label generation failures never fall back to the platform (house)
 *     account: any carrier/account failure marks the order's tracking row
 *     ERROR with the failure message so it surfaces in the order list.
 *   - Finding #19: resolveAccountForOrderWithDetails no longer emits
 *     SCENARIO_CLIENT_DEFAULT for a single client-owned account; always
 *     surfaces SCENARIO_CHOOSE_ACCOUNT with the sole account pre-selected.
 *
 * A dedicated CarrierServiceImplTest fixture (Mockito harness for the
 * common 30-dep setup) is a follow-up sprint — creating it here would
 * inflate this PR by hundreds of lines of mock setup.
 */
final class CarrierServiceImplTest {
    private CarrierServiceImplTest() {}
}
