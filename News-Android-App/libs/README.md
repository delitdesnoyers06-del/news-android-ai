# Bundled AARs (mlGemma flavor)

## sherpa-onnx (neural TTS)

sherpa-onnx powers the downloadable Kokoro / Piper / Matcha voices in the `mlGemma`
flavor. It is **not** published to Maven Central, so its Android AAR must be placed
here as `sherpa-onnx.aar`.

`build.gradle` consumes it via:

```gradle
repositories { flatDir { dirs 'libs' } }
dependencies { mlGemmaImplementation(name: 'sherpa-onnx', ext: 'aar') }
```

### Which file to drop in

Download the prebuilt Android AAR from the sherpa-onnx GitHub releases and rename it
to `sherpa-onnx.aar`:

- Release assets: <https://github.com/k2-fsa/sherpa-onnx/releases>
- Asset name pattern: `sherpa-onnx-<version>-android.aar` (or build it from source with
  `./build-android-arm64-v8a.sh` + `./build-android-x86-64.sh`).
- **Pinned API version: v1.10.46.** `SherpaTts.java` is written against the
  `com.k2fsa.sherpa.onnx` Kotlin classes as they exist in that release
  (`OfflineTtsConfig`, `OfflineTtsModelConfig`, `OfflineTtsVitsModelConfig`,
  `OfflineTtsKokoroModelConfig`, `OfflineTtsMatchaModelConfig`, `OfflineTts.generate`,
  `GeneratedAudio`). If you bundle a newer release and a config field was renamed,
  adjust `SherpaTts.configFor` accordingly.

The AAR must contain `jni/arm64-v8a` and `jni/x86_64` to match the flavor's
`abiFilters` in `build.gradle`.

The AAR itself is intentionally **not** committed (it is a ~30 MB binary blob); this
README is the record of what belongs here, mirroring how the LiteRT-LM / MediaPipe
blobs are pulled from Maven rather than vendored.
