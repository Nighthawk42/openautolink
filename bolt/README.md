# Bolt self-publishing fork

This fork of [mossyhub/openautolink](https://github.com/mossyhub/openautolink) publishes the car app to
Google Play under its own application ID, so it can be installed on a locked GM head unit (2027 Chevrolet
Bolt) from a personal internal testing track.

There are no source changes. The fork only adds:

| File | Purpose |
|---|---|
| `.github/workflows/bolt-sync.yml` | Daily: merge upstream `main`, keep upstream workflows disabled, build new upstream release tags |
| `.github/workflows/bolt-car-release.yml` | Build one upstream tag with upstream's pinned builder image, sign, upload to Play, record a `car-<tag>` release |
| `bolt/README.md` | This runbook |

These names don't exist upstream, so merges never conflict.

## How it works

1. `bolt-sync` merges upstream `main` through the GitHub merge-upstream API.
2. It disables every workflow that isn't `bolt-*`. Upstream's workflows break or publish into a fork:
   - `ci.yml` and `release-apk.yml` download `native-deps` from the fork's releases.
   - `build-builder-image.yml` pushes to GHCR.
   - `fork-build.yml` never sets a versionCode.
3. If upstream's latest release `v0.1.N` has no **published** `car-v0.1.N` release in this fork, it calls
   `bolt-car-release`.
4. `bolt-car-release` reads the builder image digest from `build-aab.sh` *at that tag*, then runs
   upstream's builder containers:
   - `build-unsigned`: the key is hidden behind a tmpfs.
   - `sign`: networking off, key mounted read-only. It verifies the bundle, signature, package ID,
     version, and signer.
5. It uploads the AAB to the Play `internal` track, then publishes `car-v0.1.N` with the AAB, its checksum,
   and build metadata.

The build compiles upstream's tag itself (`OAL_SOURCE_REPO`, default `mossyhub/openautolink`), which is exactly
the maintainer's release. The phone uses upstream's signed companion APK from the same tag.

### Version scheme

| | Value |
|---|---|
| versionCode | PATCH of the tag (`v0.1.497` → `497`) |
| versionName | `0.1.<versionCode>` (matches the upstream tag and companion) |

This matches upstream's own Play numbering. It only works while upstream stays on `0.1.x`, and both workflows
fail on purpose when a tag leaves that line. When that happens, choose the new numbering deliberately: it must
stay **above** the highest code already uploaded. Run `bolt-car-release` manually with `version_code` and
update this section. A versionCode can never be reused or lowered on Play.

## Configuration

Repository **variables** (Settings → Secrets and variables → Actions → Variables):

| Variable | Value |
|---|---|
| `APP_ID` | `moe.nighthawk.openautolink` (permanent once uploaded) |
| `UPLOAD_KEY_ALIAS` | `upload` |
| `UPLOAD_CERT_SHA256` | Upload certificate SHA-256 fingerprint. The build fails if the AAB signer differs |
| `PLAY_TRACK` | *(optional)* Track for automatic uploads. Default `internal`. This app uses `automotive:qa`: automotive-only bundles must go to the dedicated Android Automotive OS track, whose internal testing track is named `qa` in the Play API |
| `OAL_SOURCE_REPO` | *(optional)* `owner/repo` to build tags from instead of upstream |

Repository **secrets**:

| Secret | Value |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | Base64 of the upload keystore (PKCS12 `.jks`) |
| `UPLOAD_STORE_PASSWORD` | Keystore password |
| `UPLOAD_KEY_PASSWORD` | Key password (same as the store password for builder-generated keys) |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud service account JSON key with Play release permission |
| `SYNC_TOKEN` | Fine-grained PAT for **this fork only**: Contents, Workflows, Actions, Issues, all read/write |

## One-time setup

### 1. Upload key

The key is generated on a Linux machine with Docker, using upstream's pinned builder image, so no local JDK is needed. It
lives in `~/.oal-bolt/secrets/`.

```bash
IMAGE=$(curl -fsSL https://raw.githubusercontent.com/mossyhub/openautolink/main/build-aab.sh \
  | sed -n -E 's/^OAL_BUILDER_IMAGE="\$\{OAL_BUILDER_IMAGE:-([^}]+)\}"$/\1/p')
mkdir -p ~/.oal-bolt/{config,secrets,home} && chmod 700 ~/.oal-bolt ~/.oal-bolt/secrets
docker run --rm --init --network none --read-only --user "$(id -u):$(id -g)" \
  --env HOME=/workspace/home --env OAL_SIGNER_NAME="Nighthawk42" --env OAL_COUNTRY=US --env OAL_KEY_ALIAS=upload \
  --tmpfs /tmp:rw,noexec,nosuid,size=16m \
  -v ~/.oal-bolt/config:/workspace/config:ro -v ~/.oal-bolt/secrets:/workspace/secrets -v ~/.oal-bolt/home:/workspace/home \
  "$IMAGE" prepare-key
```

Load it into GitHub from Windows. The key is piped over SSH (replace `<build-host>` with the SSH alias of that machine) and never written to the Windows disk:

```powershell
ssh <build-host> "base64 -w0 ~/.oal-bolt/secrets/upload-key.jks" | gh secret set UPLOAD_KEYSTORE_BASE64 -R Nighthawk42/openautolink
ssh <build-host> "tr -d '\n' < ~/.oal-bolt/secrets/keystore-password" | gh secret set UPLOAD_STORE_PASSWORD -R Nighthawk42/openautolink
ssh <build-host> "tr -d '\n' < ~/.oal-bolt/secrets/key-password" | gh secret set UPLOAD_KEY_PASSWORD -R Nighthawk42/openautolink
```

> [!WARNING]
> Back up `~/.oal-bolt/secrets/` offline, encrypted. Without the upload key, updates stop until Google
> completes an upload-key reset in Play Console. Never commit or share it.

### 2. Bootstrap build (no Play yet)

Actions → **Bolt car release** → Run workflow with `tag` set to the latest upstream release and `upload`
unchecked. Download the AAB from the resulting **draft** release `car-<tag>`.

### 3. Play Console app

Play requires the first upload of a new app to go through the Console UI.

1. **Create app**: free app, any name (for example "OpenAutoLink Bolt").
2. **Test and release → Advanced settings → Form factors**: add **Android Automotive OS**.
3. **App content**:
   - Privacy policy: `https://github.com/Nighthawk42/openautolink/blob/main/docs/privacy-policy.md`
   - Ads: none
   - Data safety: consistent with that policy
   - Also complete content rating and target audience.
4. **Testing → Internal testing**:
   1. Add a tester list containing the car's Google account.
   2. Create a release and upload the AAB.
   3. Accept Play App Signing (Google holds the app signing key; this key is only the upload key).
   4. Roll out.
5. Copy the tester opt-in link.

### 4. Service account for automatic uploads

1. Google Cloud Console:
   1. Create a project and enable **Google Play Android Developer API**.
   2. Create a service account and add a JSON key.
   3. Store the JSON as `PLAY_SERVICE_ACCOUNT_JSON`.
2. Play Console → **Users and permissions**:
   1. Invite the service account email.
   2. On this app, grant *View app information* and *Release apps to testing tracks*.

### 5. Arm the automation

1. Create the `SYNC_TOKEN` PAT (scopes above) and store it as a secret.
2. Mark the bootstrap release as done, since it was uploaded by hand:
   `gh release edit car-<tag> -R Nighthawk42/openautolink --draft=false`
3. Run **Bolt upstream sync** manually. It should merge, disable upstream workflows, and skip the build
   because `car-<tag>` is published.

### 6. Car and phone

- **Car**: open the opt-in link signed in as the car's Google account, then install from the car's Play Store.
  Grant **Car information** and microphone under Settings → Apps → OpenAutoLink → Permissions.
- **Phone**: install `openautolink-companion-<tag>.apk` from the matching upstream release (linked in each
  `car-<tag>` release).
- Continue with [Wireless WPP setup](../docs/wireless-wpp.md).

## Operations

### Rebuild or build a specific tag

Actions → **Bolt car release** → Run workflow. You can't re-upload a tag that's already on Play, because
its versionCode is taken. Use `version_code` only for deliberate renumbering.

### Upstream merge conflict

`bolt-sync` fails and opens an **Upstream merge conflict** issue (issues must be enabled on the fork). To fix:

```bash
git fetch upstream
git checkout main && git pull
git merge upstream/main
git push origin main
```

The only fork-owned files are listed at the top of this document. A conflict means upstream added a
file with one of those names.

### Carrying your own patches

1. Commit the patches in the fork and push a tag shaped `vMAJOR.MINOR.PATCH` on the patched commit.
2. Set `OAL_SOURCE_REPO=Nighthawk42/openautolink`.
3. Run **Bolt car release** manually with that tag. If its PATCH number is already on Play, also pass a
   higher `version_code`.

`bolt-sync` still watches upstream's release list and builds those tags from `OAL_SOURCE_REPO`. Your patches
must be merged onto each new upstream tag, or you can unset the variable to go back to plain upstream builds.

### Failure modes

| Symptom | Cause / fix |
|---|---|
| `native-deps release does not match aasdk` | Upstream tagged before rebuilding native deps. The next daily sync retries automatically |
| `Only releases with status draft may be created on draft app` | Play app setup is incomplete. Finish the manual internal rollout (step 3) |
| `APK specifies a version code that has already been used` | The tag was already uploaded. Publish its `car-<tag>` release so sync skips it |
| `AAB signer ... does not match UPLOAD_CERT_SHA256` | The wrong keystore is in secrets. Restore it from the backup |
| Sync `403` / `Resource not accessible` | `SYNC_TOKEN` expired or lacks a permission |
