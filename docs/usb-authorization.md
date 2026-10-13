# USB authorization and Android's “Always use” default

OAL advertises only Google AOA `18D1:2D00`/`18D1:2D01` and Apple USBMUX
(Apple VID `05AC`, device or interface `FF/FE/02`) as USB attach handlers.
There is no all-USB filter, no all-Apple-peripheral filter and no pre-AOA
Android OEM VID wildcard. USBMUX shape is not proof of iPhone: the runtime
still performs its existing private identity and CarPlay configuration checks.
The manifest is static: Android may offer OAL as a handler even with CarPlay
disabled. The runtime enabled/admission checks still prohibit CarPlay startup
and prohibit routing Apple devices into Android Auto.

Android owns its attach confirmation/resolver and its “Always use” checkbox.
The saved default is a concrete device fingerprint, not a transferable OAL
permission. A mode switch changes the attachment, clears temporary permission,
and may change the default's fingerprint. Initial Android OEM enumeration and
AOA enumeration have different VID/PID, so one total prompt cannot be promised.
Apple mode switching also requires fresh exact-device platform permission;
a checked default helps only when Android matches the replacement fingerprint.
OAL has no privileged `MANAGE_USB` grant or permission-transfer bypass.

Raw attach broadcasts and device census polls may run before Android finishes
resolving its default. Android provides ordinary apps no reliable indication
that this dialog has closed or been dismissed. Accordingly, automatic OAL
paths never call `requestPermission` for an OS-matched device without explicit
user recovery. They poll actual `UsbManager.hasPermission` and exact current
attachment identity; elapsed time never authorizes a second app dialog.
Existing OS grants work after cold process/manager startup too. The exported
attach Activity's Intent and any Boolean grant extra are not authorization.

If the OS dialog was denied/dismissed, or a previously connected device is
still plugged in without authorization: first dismiss/finish the OS USB resolver,
then explicitly select the exact phone in OAL's USB picker. Pending Apple USB
authorization exposes this picker with CarPlay enabled even while Android Auto
uses its normal Wireless/WPP mode; changing the saved AA transport to USB is not
required. The picker is available on either projection screen when the manager
is awaiting selection, and does not cover Settings, Diagnostics or the safe-area
editor. If a failed CarPlay attempt is no longer awaiting selection, the existing
Retry CarPlay/USB action retires that attempt and restores the passive same-cable
wait; select the phone in the picker afterwards. The retry itself does not grant
permission authority. A picker row tap grants one exact-device permission
request, not permission transfer or permission to replace the active projection:
existing Switch/Stay and awaited retirement still apply. Stay revokes the request
authority without starting CarPlay. No visibility change writes saved AA/CarPlay
settings or initiates a permission request.

This is deliberate recovery, not automatic reprompting. A plain automatic
rescan/restart does not authorize an app request. Replug is another fresh attach
opportunity. The picker remains usable during an automatic wait and after timeout;
timeout never raises another dialog. A cancelled wait, exact detach or terminal
app-request denial cannot be upgraded by a late grant.

CarPlay's replacement wait retains its existing 15-second transition budget;
no unconditional settling sleep is added. That deadline bounds startup, not
permission authority. A slow OS confirmation can expire it and require an
explicit retry. Ownership, projection retirement, same-bus/private-serial
validation and disabled-protocol admission remain independent requirements.

Physical GM/OEM verification is still required: confirm “Always use” visibility,
saved-default reconnect, mode-change fingerprint behavior, denial/retry and
cold plug with each intended head-unit/phone pair. JVM tests cannot establish
OEM SystemUI persistence or guarantee a particular physical prompt count.
