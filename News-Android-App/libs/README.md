# Bundled AARs (mlGemma flavor)

## sherpa-onnx (neural TTS)

sherpa-onnx powers the downloadable Kokoro / Piper / Matcha voices in the `mlGemma`
flavor. It is **not** published to Maven Central, so its Android AAR is vendored here
as `sherpa-onnx.aar`.

`build.gradle` consumes it via:

```gradle
repositories { flatDir { dirs 'libs' } }
dependencies { mlGemmaImplementation(name: 'sherpa-onnx', ext: 'aar') }
```

### Version

- **Pinned: v1.10.46.**
- Source: `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.10.46/sherpa-onnx-1.10.46.aar`
  (renamed to `sherpa-onnx.aar`).
- Contains `jni/arm64-v8a`, `jni/armeabi-v7a`, `jni/x86`, `jni/x86_64`; the flavor's
  `abiFilters` keep only `arm64-v8a` + `x86_64`.
- `SherpaTts.java` is written against the `com.k2fsa.sherpa.onnx` Kotlin API of this
  release: `OfflineTtsConfig` / `OfflineTtsModelConfig` /
  `OfflineTts{Vits,Kokoro,Matcha}ModelConfig` (no-arg constructor + setters),
  `OfflineTts(AssetManager, OfflineTtsConfig)`, `generate(String, int, float)`,
  `numSpeakers()`, `release()`, and `GeneratedAudio.getSamples()/getSampleRate()`.
  If you bump the AAR and a symbol changed, adjust `SherpaTts.configFor` / the call
  sites accordingly.

### Replacing it

Download the release asset above and drop it in as `sherpa-onnx.aar`, or build from
source with sherpa-onnx's `build-android-arm64-v8a.sh` + `build-android-x86-64.sh`.

The `.aar` is ~36 MB. If you prefer not to keep it in git history, remove it from the
commit, add `News-Android-App/libs/*.aar` to `.gitignore`, and fetch it in a CI step
instead — the build only needs it present at build time.
