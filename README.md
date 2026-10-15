<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/a3babe14-55cf-4eba-82cd-a07edacd89c9

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## Native core (Rust)

The suggestion engine is an original Rust crate in `rust-core/`, packaged as
`libkeyboard_core.so` and consumed over JNI by
`app/src/main/java/com/example/keyboard/native/NativeEngineBridge.kt`.

It memory-maps a compact `PLKD` dictionary (`app/src/main/assets/keyboard_dictionary.bin`),
does prefix/correction/next-word ranking, and adapts to the words you accept or
type during a session.

Build and test it:

```sh
cd rust-core
cargo test                                   # engine + asset tests
cargo fmt --check
cargo clippy --all-targets -- -D warnings
export ANDROID_NDK_HOME=/path/to/ndk
cargo ndk -t aarch64-linux-android -t x86_64-linux-android build --release
cp target/aarch64-linux-android/release/libkeyboard_core.so ../app/src/main/jniLibs/arm64-v8a/
cp target/x86_64-linux-android/release/libkeyboard_core.so  ../app/src/main/jniLibs/x86_64/
```

Regenerate the dictionary from the word list with
`python3 tools/build_dictionary.py`. Engineering notes live in
`rust-core/README.md` and the work queue in `NATIVE_ROADMAP.md`.
The `.github/workflows/native-build.yml` workflow (manual trigger) runs the Rust
and Kotlin tests and produces an APK.

