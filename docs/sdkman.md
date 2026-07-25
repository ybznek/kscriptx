# Publishing kscriptx on SDKMAN!

SDKMAN is the recommended install path with upgrade support:

```bash
sdk install kscriptx
sdk upgrade kscriptx
```

## What this repo already does

1. **Universal zip** — `./scripts/package-sdkman.sh` builds `dist/kscriptx-<ver>-bin.zip`
   (same layout as classic `kscript-*-bin.zip`: `kscriptx-<ver>/bin/...`, JVM-only).
2. **Release CI** — `.github/workflows/release.yml` uploads that zip on every `v*` tag and,
   when vendor API secrets are present, publishes + sets the SDKMAN default.
3. **Candidate registration** — PR against
   [sdkman/sdkman-db-migrations](https://github.com/sdkman/sdkman-db-migrations)
   adding the `kscriptx` candidate (see below).

## One-time vendor onboarding (required before `sdk install` works)

Follow [Vendor onboarding](https://github.com/sdkman/sdkman-cli/wiki/Vendor-onboarding-process):

1. Merge the `kscriptx` candidate migration PR into `sdkman-db-migrations`
   (candidate appears as *Coming Soon* until a version is published).
2. Export your **armoured public GPG key** and email it as a plain-text attachment to
   **info@sdkman.io**, asking for Vendor API credentials for candidate `kscriptx`.
3. Decrypt the reply, then add GitHub repo secrets on `ybznek/kscriptx`:
   - `SDKMAN_CONSUMER_KEY`
   - `SDKMAN_CONSUMER_TOKEN`
4. Re-run the Release workflow (or push a new `v*` tag). The `sdkman_publish` job will
   call the Vendor API and set the default version.

Optional: join the SDKMAN Discord `#vendors` channel if onboarding is stuck.

## Manual publish (if needed)

```bash
VERSION=0.1.4
URL="https://github.com/ybznek/kscriptx/releases/download/v${VERSION}/kscriptx-${VERSION}-bin.zip"

curl -X POST \
  -H "Consumer-Key: $SDKMAN_CONSUMER_KEY" \
  -H "Consumer-Token: $SDKMAN_CONSUMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"candidate\":\"kscriptx\",\"version\":\"${VERSION}\",\"url\":\"${URL}\",\"platform\":\"UNIVERSAL\"}" \
  https://vendors.sdkman.io/release

curl -X PUT \
  -H "Consumer-Key: $SDKMAN_CONSUMER_KEY" \
  -H "Consumer-Token: $SDKMAN_CONSUMER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"candidate\":\"kscriptx\",\"version\":\"${VERSION}\"}" \
  https://vendors.sdkman.io/default
```

## Notes

- The SDKMAN zip is **JVM-only** (no bundled native kotlinc / no Linux `kscriptx-dclient`).
  For native kotlinc, use the Debian/Linux release packages or
  `./scripts/build-native-kotlinc.sh`.
- Classic kscript remains a separate candidate (`sdk install kscript`).
