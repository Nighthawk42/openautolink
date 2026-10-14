# CarPlay background-session retention implementation plan

> **For Hermes:** Use subagent-driven-development for bounded tasks; retain one parent integration/release controller.

**Goal:** AAOS Home/native-app navigation must not terminate a healthy CarPlay connection; returning must resume real rendered video without USB replug or protocol reconnect.

**Architecture:** The coordinator/runtime own transport/control/audio/metadata/admission independently of a replaceable UI Surface. Presentation attachment is exact-current-surface qualified, coalesced and handled on the single video worker; detach releases/suspends only decoding and retains codec configuration. Return requests a fresh random-access frame using the existing exact-screen CarPlay forceKeyFrame event. Real retirement semantics remain unchanged.

**Tech stack:** Kotlin, Android MediaCodec/Surface/foreground-service lifecycle, Compose SurfaceView, JUnit/Robolectric, Gradle on established host JDK17/SDK.

## Source and coordination

Base: v0.1.520 / b10a8751a041bf32214ec925406ea5e9e88de240. Worktree /hermes_work/oal-carplay-background-investigation; branch fix/carplay-background-session. Do not edit other worktrees, stash/reset sibling work, allocate versions, sign/upload or merge independently. Parent session d4a4753b02f7 proposes sole next-release controller; require acknowledgments from cluster/audio bf150b254bd3, USB permissions b80578478ad7 and EV lab 20261012_235621_fb99a7. Release includes only finished authorized changes; unfinished tablet experiments excluded. Exchange final commit/hash/path/gate handoffs and integrate one frozen tree.

## Task 1: Replace decoder presentation without terminating the receiver

Files: carplay-lab/.../media/ProjectionReceiver.kt; carplay-lab/.../media/AndroidMediaSink.kt; new presentation owner helper and behavioral tests under corresponding test packages.

1. RED: production sink/receiver test detaches original surface and proves session/audio/control owner stays live; attach replacement and prove config/fresh-IDR recovery. Also test stale detach, replacement coalescing, codec error, detach during configure and post-retirement attachment rejection.
2. Add public `fun attachSurface(surface: Surface)` and `fun detachSurface(surface: Surface)` to receiver and sink. Callers never release the supplied Surface.
3. Make sink own a bounded latest presentation receipt rather than immutable runtime target. The sole video worker alone creates/stops/releases/configures codecs. Detach releases video codec and drains/discards bounded video frames, retains latest validated config, suppresses recovery requests while offscreen; reattach configures target and requests fresh IDR through existing recovery callback. Fence old frame-rendered callbacks by exact codec/presentation generation. Never queue unbounded surface jobs or terminate receiver for lost presentation.
4. Remove original-surface liveness checks from receiver startup after initial qualification; background bootstrap can finish from frozen valid geometry even after initial view goes away. Keep cancellation/admission guards.
5. GREEN: focused receiver/media tests. Preserve audio focus/routing behavior belonging to sibling stream.

## Task 2: Wire live application/runtime presentation ownership

Files: app/.../MainActivity.kt; app/.../ui/projection/ProjectionViewModel.kt; app/.../carplay/{CarPlayCoordinator,OalCarPlayRuntime,OalUsbCarPlayRuntime}.kt; scoped tests.

1. RED: MainActivity stop must not call full session stop; surface destruction must only detach. Test exact surface replacement/current ownership and late runtime publication after detach. Maintain explicit stop/ignition-off/cable-loss retirement tests.
2. Extend CarPlayRuntime with default `attachSurface(Surface)` and `detachSurface(Surface)` so existing nonpresentation fakes compile. Coordinator keeps current desired surface receipt; publication/construction races must reconcile latest receipt without holding transport shutdown or UI on codec work. ViewModel forwards attach/detach to exact active attempt and still publishes geometry independently for NEXT startup.
3. Both runtime adapters retain current presentation state under existing resourcesLock; apply it to exact receiver on install, forward later updates nonblockingly. Remove original Surface.isValid as runtime/control/permission cancellation predicate; cancellation remains exact attempt-owned. Original initial qualification remains bounded.
4. Remove onStop full CarPlay teardown and surface-destroy stop. Keep coordinator stop for ignition-off, explicit user stop/Exit, disable, genuine cable detach, protocol switch and terminal ViewModel cleanup. No automatic reconnect merely on UI return.
5. GREEN: application coordinator/runtime tests; do not alter USB permission/default-handler logic owned by sibling session. Provide context-only overlapping diff handoff for final integration.

## Task 3: Background execution and diagnostics (parent)

Files: new app/.../carplay/CarPlaySessionService.kt if required; app manifest; lifecycle diagnostics/sanitizer tests and docs.

1. Audit established service/manifest and bound USB VPN lifetime. Add a minimal exact-owner connectedDevice foreground service lease if required for reliable ordinary-app background execution; start while foreground before runtime ownership and release only at actual retirement. Reuse app notification channel, keep explicit terminal cleanup, no new transport/start policy or sticky auto-receiver.
2. Do not request microphone/background permission speculatively; declared service types and API34 prerequisites must reflect actual connected-device ownership. Isolate lease manager testability and support multiple retiring/current leases without one stale release stopping a successor.
3. Export finite presentation detach/attach/recovery receipts through existing sanitizer and uploaded archive path; no raw Surface identity, credentials or payloads. Actual rendered frames are positive evidence; no claim of recovery from attach intent alone.
4. Test lease retirement, stale releases, service-start failure containment and sanitizer allowlist retention.

## Task 4: Exact-tree gates and combined next-release handoff

1. Source/bootstrap: isolated local.properties SDK path; initialize pinned submodules; reuse unchanged native deps only after pin/source/hash equality. Host tree path /mnt/Temp/Hermes_Work/oal-carplay-background-investigation.
2. Every behavioral change gets compiling RED -> GREEN evidence. Run host tasks via background tracked SSH and shared `/mnt/Temp/Hermes_Work/.oal-gradle.lock`, not foreground SSH Gradle. Example tasks: `:carplay-lab:testDebugUnitTest :app:testDebugUnitTest :app:compileReleaseKotlin :carplay-lab:compileReleaseKotlin`; focused runs may filter tests but final gates must not exclude source.
3. Spec-compliance review, then independent ownership/race/security quality review. Fix blockers and rerun completed exact tree; count actual suite XML by invoked modules/variants.
4. Commit only this stream's exact owned files, provide SHA and patch/gates to combined controller. Integrate sibling completed commits, resolve shared receiver/sink/runtime/diagnostic changes by preserving both behaviors, re-run full app/receiver/companion tests and release/native build on frozen combined source, then allocate ONE common monotonically safe version.
5. Only combined controller performs source publication/signing/Play all authorized listings and GitHub artifact verification. No early CarPlay-only upload.

## Runtime acceptance and limitations

Vehicle validation after combined release: same USB/iAP2/AirPlay owner retained across Home and native energy menu (short and prolonged background); desired media continues; fresh rendered frames return without Save & Reconnect/replug; settings overlays, explicit Stop/Exit, ignition-off, unplug and hot-plug replacement still retire cleanly. The separately reported Bolt black-until-replug case remains its own physical acceptance test. Unit tests/builds are not vehicle proof. No access to owner's iPhone or tablet required for implementation; respect other sessions' device holds.
