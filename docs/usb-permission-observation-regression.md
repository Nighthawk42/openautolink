# USB permission observation regression (car0.1.521)

## Vehicle evidence and cause

Source archive: `20261013_211912Z__3dd694c085dd45bd__oal_car_2026-10-13_14-20-20.zip`, Lance's 2024 Blazer; `logcat_2026-10-13_14-17-26.log`.

- Initial selected iPhone `/dev/bus/usb/002/020`: permission granted 14:17:53.499, mode transition pending 53.515, detach 53.566, replacement `/002/021` attached 53.938. No authorized `UsbAttachedActivity` handoff/grant is logged. At 14:18:08.538 startup fails CANCELLED and restores the prior projection. Selecting the replacement at 14:18:15.083 and granting at 18.866 succeeds through USBMUX and rendering.
- Repeated attempt: initial grant 14:19:21.617, transition 21.629, replacement attach 22.032, expiry/failure 36.627, replacement selected 38.874, grant 40.779, render 54.755.

The application falsely treated a static manifest match as evidence that Android would supply authorization. `UsbCarPlayPermission.await()` suppressed `requestPermission()` for the USBMUX replacement and waited for a grant that never arrived. The exact selected owner then expired at its existing 15-second transition deadline. The AOA manager used the same suppression for Google accessory replacements and cold-start accessories. This is an app-side liveness regression, not evidence that permission transfers across enumeration or that GM persists an Always-use default.

`UsbAttachedActivity` is an authorized OS handoff only: it checks the exact live census entry and actual `hasPermission()` before forwarding to MainActivity. Its presence in the manifest does not establish that Android's resolver is open or that a grant will arrive. Exported attach intents and Boolean grant extras remain untrusted.

## User-facing correction and limits

Startup must request ordinary Android USB permission for each currently selected, attached enumeration whose actual `hasPermission()` is false. After the initial phone changes mode, an additional replacement-device approval can be necessary; startup should continue under the same owner after that approval, without selecting the phone again solely to defeat permission suppression. Already-authorized devices bypass the request. Denial, cancellation, detach, ambiguous replacement and ownership retirement remain terminal/fail-closed.

Keep the narrow Apple USBMUX and Google accessory manifest filters so Android can offer **Always use** where supported. Do not rely on that option for startup or claim a persisted default on GM. Android exposes no ordinary-app API to determine whether its resolver is active. A concurrent OS/default grant can race the explicit request; Android's already-granted request path can return immediately, but physical testing is required before claiming no duplicate system prompt. This repair does **not** promise a single prompt across initial and replacement devices, nor does it use a delay to conceal the limitation.

## Validation

Execution evidence and reviewed source hashes are archived separately under `build/usb-permission-evidence/`; the final test report records actual RED/GREEN results. JVM tests establish request/callback/ownership behavior, not vehicle rendering or OEM UI behavior. No commit, push, merge, version change, signing or upload is authorized in this worktree.
