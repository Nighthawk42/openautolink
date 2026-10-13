# CarPlay cluster route lifecycle

## Contract

CarPlay route activity and the availability of its selected maneuver are different facts. The metadata intake exposes `CarPlayRouteMetadata(epoch, instruction)` in addition to the backward-compatible nullable `navigation` instruction property. A null route snapshot means no retained route. A nonnull route with a null instruction means **known route, no trustworthy selected turn**. It must not be converted to `navigationEnded()` or an old turn.

An intake epoch starts when accepted route data establishes activity after a clear. Accepted route refreshes, index changes, absent/empty current lists, selected-detail cache misses and later instruction arrival keep the epoch. Parsed state 0 (stop) and 2 (arrived), revoke and finite route expiry clear it and purge the bounded maneuver cache. Stop/start replacement remains distinguishable even when the worker coalesces away the intermediate null snapshot. The parser does not infer an unreported route identity/replacement merely from a new selected maneuver ID.

The exact-link worker publishes route snapshots to the production metadata host. Older test/host adapters retain the instruction-only `publish` API through the default `publishRoute` bridge; they do **not** gain route-lifecycle semantics automatically. The production host tracks the source epoch and updates shared cluster activity separately from its nullable instruction. Android Auto's existing nonnull update and explicit clear APIs remain unchanged.

Host stop is route-owned suppression: loading, instruction refresh and recovery cannot undo it. Only an actual route clear/replacement permits renewed navigation. Consumer generation fences, snapshot validation and the lifecycle lock remain effective through loading and normal Trip submission. Instruction loss, a nonterminal update and clear immediately revoke a state-side terminal-validity token under that lock. An arrival job captures this token as well as its route/generation fences, so terminal -> loading/nonterminal -> terminal within the 200 ms Trip debounce cannot revive the old timer. The token is observable independently of the instruction StateFlow, preserving revocation even if intermediate instructions are conflated. Restored terminal details receive a fresh ten-second timer after their debounced submission; uninterrupted terminal refreshes retain the existing timer. Explicit terminal handling and the ten-second terminal timer remain separate policies.

## AndroidX loading representation

The pinned AndroidX Car App **1.7.0** packaged `Trip.Builder` supports `setLoading(true)` with empty steps. Its bytecode rejects step information while loading and permits no destination. Loading submissions therefore use exactly:

```kotlin
Trip.Builder().setLoading(true).build()
```

No old maneuver, distance, road, destination, estimate, arrival time or invented ordinal is attached. Navigation is started before `NavigationManager.updateTrip`, which requires a navigating manager and the main thread. The normal session collector still performs start/update/end effects on `Dispatchers.Main`.

References: [Trip.Builder](https://developer.android.com/reference/androidx/car/app/navigation/model/Trip.Builder), [NavigationManager](https://developer.android.com/reference/androidx/car/app/navigation/NavigationManager). The inspected packaged bytecode is retained in the private implementation evidence directory. API model validity and a successful host call are **not** proof that GM renders a spinner, blank turn card or HUD indicator, or that it stops displaying a previous frame immediately; that requires vehicle validation.

## Local freshness policy

The timers are **OAL policy**, not Apple requirements:

- Before 30 seconds since the last accepted route frame: publish only the cached maneuver selected by that route, if present. Other/future cached maneuvers cannot substitute for it.
- At 30 seconds: clear selected instruction/cache and its retained estimates; keep the known route/epoch as loading. Maneuver-only, music, artwork, video and other runtime traffic do not renew route freshness or resurrect guidance.
- At 120 seconds without an accepted route frame: clear the route and cache, relinquishing cluster navigation. A subsequent accepted active route gets a new epoch and needs trustworthy selected details.
- Fresh accepted route frames renew the finite route deadline even when the selected details are missing. A stream of active route frames is evidence of ongoing route activity, not a reason to fabricate a turn. Such a route may stay loading while those explicit route frames continue.

Both intake ticks and fully validated navigation frames enforce the **previous** route timestamp before any frame mutation or deadline renewal. Thus a valid route received just after 30 seconds first purges old cached details/estimates and retains its epoch; one received just after 120 seconds first clears the old route/cache and establishes a new epoch if active. Omitted route fields inherit only the post-expiry retained state, not values captured before purging. Accepted maneuver traffic also enforces crossed deadlines but never renews route liveness or restores an expired route. Invalid or rejected frames do not mutate route/cache freshness; an independent tick still enforces their outstanding deadline. No independent watchdog was added: a completely silent receiver still relies on its existing tick loop.

The 30-second instruction safety threshold is retained from the previous implementation. The two-minute ownership grace separates conservative instruction expiry from a longer finite missing-route window; it is an availability/safety compromise to avoid a destructive end at the first 30-second pause without holding navigation ownership forever after silent/lost route metadata. It has not been phone- or vehicle-qualified and may require future evidence-based adjustment. It does not claim to explain why the original sender omitted maneuver details or stopped sending route frames.

## Diagnostics and acceptance limits

Instruction receipts (`guidance`, `publishable`, instruction transition) describe instruction availability, **not route ownership**. A `guidance=false transition=clear` after-return receipt may represent loading rather than route end. Route-aware receipts and sanitizer acceptance must be reviewed together before relying on exported logs for route ownership.

The independent final-retirement defect remains out of scope: metadata-host `close` can call cluster release from its worker, while NavigationManager teardown requires the main thread. This patch does not claim that bug is fixed. A future repair must preserve exact-owner retirement and avoid waiting on the main thread while main-thread effects wait for the worker.

Focused host tests are documented in the implementation and review-fix handoffs, with behavioral RED/GREEN logs and XML. Review-fix regressions cover just-below tick / just-above accepted frame boundaries, malformed-frame atomicity, terminal/loading/terminal and terminal/nonterminal/terminal ABA near the old deadline, and retention of the timer across uninterrupted terminal refresh. They do not establish final combined-tree release gates, packaged/minified diagnostics, audible audio routing, USB transport behavior or physical cluster/HUD rendering. No publication or release is authorized by this document.
