# CarPlay audio ownership diagnostics investigation

## Evidence and scope

This change is diagnostic only. It does not reclaim focus automatically, suppress STOP/PAUSE, add a UI, modify AudioTrack usage/content attributes, disconnect Bluetooth, or reinterpret projected touch as user Play. Existing `audio-focus-change`, granted/denied and after-write `media-control outcome=sent|failed` receipts remain supported.

Read-only incident analysis lives in `/hermes_work/carplay-triage-20261013/audio-findings.md`. The capture proved authenticated LPCM ingress, positive own track writes, permanent focus LOSS, and repeated HID Pause. Native MediaSession STOP and PAUSE currently collapse into HID index 2. The owner reports Bluetooth-only failures and the vehicle banner “Voice recognition now handled by connected Bluetooth device” for Hey Siri with Bluetooth connected, absent with Bluetooth off. Neither the banner nor own bus device type 21 proves A2DP/SCO or acoustic audibility.

## Diagnostic boundaries

Original command ingress is recorded before the host maps commands to HID. Each exact owner carries a monotonically increasing sequence and a source-boundary `elapsedMs` measured synchronously before enqueue. Therefore rapid repeated STOP/PAUSE callbacks remain distinct events, while exact replay of the same provenance can be deduplicated. Do not infer callback ordering or latency from the later serialized status timestamps. Its immutable metadata-owner closure must enqueue to the receiver's bounded status executor; no log/archive/Binder consumer should execute inside the MediaSession callback. Both observation and dispatch retain exact metadata-owner validation. Finite controller categories are hints about the framework-reported requesting controller, not focus winner attribution, Bluetooth profile state, trusted identity or human intent. Unclassified nonempty packages are `other`; missing/unavailable controller information remains `unknown`; never export a package name, UID, PID, MAC, Bluetooth name or address.

Focus receipts distinguish stream-start from explicit user-play. A permanent-loss or denied latch remains muted on later streams, including navigation. Only the existing explicit Play path may ask again. Stale focus listeners are fenced by the existing request revision. Volume receipts distinguish requested focus attenuation from the last successful local AudioTrack setVolume return; neither proves HAL gain, nonzero PCM or audibility. Passive periodic snapshots reuse the existing route diagnostic worker rather than adding Binder queries on media IO.

## Classification sources

`com.android.bluetooth` is exactly the package declared in the AOSP Bluetooth manifest retrieved during this task: https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/AndroidManifest.xml . It permits only the finite label `bluetooth`, not an assertion of connection or profile. The local shipped `com.android.car` manifest and CarMediaService stop call chain in `oem-audio-investigation.md` justify the separate `car-service` category, while Car SystemUI is `system-ui`. The existing MediaSession source and local manifest identify `com.gm.rhmi`; local SDK aapt badging of the research APK identifies `com.gm.gmaudio.server`. Only those two exact known packages map to `oem`; arbitrary GM-prefixed packages remain `other`. Null, empty, unavailable or throwing controller getters are `unknown`. The package is read synchronously inside the framework callback and reduced immediately; no raw identifier is retained or exported.

## Verification boundary

Host Robolectric tests exercise production callback objects, production sink focus listeners and owned-track state, then the production fail-closed sanitizer/reporter and actual `LogUploadArchive` ZIP retention. These are executable host boundary tests, not live AAOS/iPhone/OEM acceptance. Release signing/upload and physical device operations are outside this task. Final test counts and log names are in the worker handoff.
