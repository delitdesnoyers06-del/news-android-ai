# CI release signing

The `release-apk` job in `.github/workflows/ci.yml` builds a minified,
arm64-v8a-only release APK for both flavors (`mlNone`, `mlGemma`) and uploads
them as artifacts. It signs them when the signing secrets are present, and
falls back to an **unsigned** release APK when they are not — so CI stays green
before the secrets are configured.

## Why release + arm64-only

The `mlGemma` flavor bundles the native runtimes (LiteRT, MediaPipe,
sherpa-onnx). A debug build with both ABIs (`arm64-v8a` + `x86_64`) is large
enough to overrun the GitHub Actions artifact storage quota. The release job
drops the emulator-only `x86_64` ABI (`-PmlGemmaAbi=arm64-v8a`) and minifies the
code, which keeps the artifact well within the quota. Real devices are arm64, so
the artifact still installs.

Local and F-Droid builds are unchanged: without `-PmlGemmaAbi` the build keeps
both ABIs, and without the keystore env vars the release build stays unsigned.

## Required GitHub secrets

Add these under **Settings → Secrets and variables → Actions → New repository
secret**:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | The release keystore, base64-encoded (see below) |
| `SIGNING_KEYSTORE_PASSWORD` | Keystore (store) password |
| `SIGNING_KEY_ALIAS` | Key alias inside the keystore |
| `SIGNING_KEY_PASSWORD` | Password for that key |

### Create a keystore (once)

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias news-release \
  -keyalg RSA -keysize 2048 -validity 10000
```

### Encode it for the secret

```bash
base64 -w0 release.keystore   # macOS: base64 -i release.keystore
```

Paste the output into `SIGNING_KEYSTORE_BASE64`. Keep `release.keystore` safe and
out of git — losing it means you can no longer ship updates that overwrite an
installed signed build.
