# Book Reader

An EPUB and PDF reader for Android and iOS, built as a Kotlin Multiplatform app
with a single Compose Multiplatform UI shared by both platforms.

- **Read** EPUB (2 and 3) and PDF, with table of contents, images and saved
  reading position.
- **Listen** with the device voice, the sentence being spoken highlighted as it
  is read, and the current word highlighted within it where the engine supports
  it.
- **Look up** any word or phrase by tapping it — English definition, Persian
  meaning, IPA pronunciation and letter-by-letter spelling.
- **Study** the words you saved as flashcards, scheduled by SM-2 spaced
  repetition.

---

## Getting the app

**The Android APK is built by CI, not committed here.** Go to
[Actions](../../actions), open the latest successful **Build** run, and download
the **`android-apk`** artifact — it contains both the debug and release APKs.
The release APK is signed with the debug key, so it installs directly on any
Android 8.0+ device once "install from unknown sources" is enabled.

There is no `.ipa`, and CI cannot make one: signing an iOS app requires an Apple
Developer certificate that only the developer holds. CI compiles the iOS
framework and builds the app unsigned; to get something installable, build it
yourself on a Mac — see [iOS](#ios).

### What is actually verified

| Layer | State |
|---|---|
| Platform-independent core | **115 tests passing** in CI on every push |
| Android APK | **Compiles and packages** in CI (debug + release) |
| iOS shared framework | **Compiles** in CI for device and simulator targets |
| iOS Xcode host app | Best-effort in CI, currently failing to link — build it in Xcode |
| Anything at runtime | **Not verified — nothing has run on a device** |

That last row matters. The core logic is genuinely tested, but the UI, the
speech engine, PDF rendering and the dictionary wiring have never executed.
"It builds" is not "it works"; expect runtime bugs on first launch.

The tested core is the EPUB container parser, the ZIP reader, the DEFLATE
decompressor, the XML/XHTML tokenizer, sentence segmentation, word
tokenization, English lemmatization, the read-aloud plan, and the SM-2
scheduler — including an end-to-end parse of a real EPUB archive. Run it with
nothing but a JDK:

```
cd tools/core-verify && gradle test
```

---

## Building

### Android

Requires JDK 17+ and the Android SDK (Android Studio installs both).

```bash
./gradlew :composeApp:assembleDebug     # → composeApp/build/outputs/apk/debug/
./gradlew :composeApp:assembleRelease   # → composeApp/build/outputs/apk/release/
./gradlew :composeApp:bundleRelease     # → .aab for Play Store upload
```

`assembleRelease` is signed with the debug key so the APK installs immediately.
Before shipping, see [Release signing](#release-signing).

### iOS

Requires macOS with Xcode 15+.

```bash
brew install xcodegen
cd iosApp && xcodegen generate
open iosApp.xcodeproj
```

Select a simulator or device and press ⌘R. The project's pre-build script runs
`:composeApp:embedAndSignAppleFrameworkForXcode`, so the Kotlin framework is
compiled and embedded automatically — there is no separate Gradle step.

**Known issue:** the Xcode host app does not currently link in CI, while the
shared Kotlin framework compiles fine for both targets. The fault is in the
generated project's framework wiring (`iosApp/project.yml` — most likely
`FRAMEWORK_SEARCH_PATHS` not matching where the embed task actually puts
`ComposeApp.framework`), not in the app code. Xcode reports the real linker
error immediately and interactively, so the fastest fix is to open the project
and adjust the search path or the run-script phase there.

#### Producing a signed `.ipa`

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Release -sdk iphoneos \
  -archivePath build/BookReader.xcarchive archive

xcodebuild -exportArchive -archivePath build/BookReader.xcarchive \
  -exportOptionsPlist ExportOptions.plist -exportPath build/ipa
```

You supply `ExportOptions.plist` with your team ID and distribution method; the
signing identity has to be yours.

### Release signing (Android)

Create a keystore and point the release build at it:

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias bookreader
```

Then replace `signingConfig = signingConfigs.getByName("debug")` in
`composeApp/build.gradle.kts` with a real `signingConfig` reading from
`local.properties` or environment variables. Never commit the keystore.

---

## How it works

```
composeApp/src/
├── commonMain/          shared by both platforms
│   ├── core/            pure Kotlin, no platform or UI dependencies — all tested
│   │   ├── zip/         ZIP reader + RFC 1951 DEFLATE decompressor
│   │   ├── xml/         tolerant XML/XHTML tokenizer and tree builder
│   │   ├── epub/        OPF/NCX/nav parsing, XHTML → renderable blocks
│   │   ├── text/        sentence segmentation, word/phrase boundaries
│   │   ├── dictionary/  lemmatizer, entry model, provider composition
│   │   ├── tts/         the read-aloud plan (sentence ↔ block ↔ offset mapping)
│   │   └── srs/         SM-2 spaced repetition
│   ├── data/            SQLDelight repositories, bundled dictionary seed
│   ├── platform/        expect declarations for TTS, PDF, storage, database
│   ├── reader/          ReaderViewModel — paging, playback, lookup
│   └── ui/              Compose screens
├── androidMain/         TextToSpeech, PdfRenderer + PDFBox, SAF, MainActivity
└── iosMain/             AVSpeechSynthesizer, PDFKit, NSFileManager, Compose host
```

A few decisions worth knowing about:

**Read-aloud speaks one sentence per utterance.** Android and iOS both report
progress as character ranges, but only within the string they were handed, and
Android's `onRangeStart` needs API 26 *and* a cooperating engine. Speaking
sentence by sentence makes the highlight exact on both platforms and makes
pause, resume and skip trivial. Word-level highlighting rides on top of range
events where they are available.

**DEFLATE is implemented in Kotlin rather than bound from zlib.** Android has
`java.util.zip`, but Kotlin/Native does not, and hand-written `z_stream`
cinterop would be code that can only be exercised on a device. The shared
implementation in `core/zip/Inflate.kt` is tested against streams produced by a
real compressor at every compression level.

**PDF uses two libraries on Android, one on iOS.** `PdfRenderer` draws pages but
exposes no text at all, so PDFBox extracts the text layer and word geometry that
tap-to-look-up needs. PDFKit does both jobs on iOS.

**Element lookups in the XML tree are case-insensitive.** The tokenizer folds tag
names for HTML's sake, but EPUB 2's NCX spells its elements `navMap`/`navPoint`
— a case-sensitive match would silently drop the table of contents.

---

## Dictionary data

A seed dictionary of ~170 common words ships in
`commonMain/kotlin/com/bookreader/data/SeedDictionary.kt`, so lookup works
offline on a fresh install. It is a starting point, not a complete lexicon —
comprehensive English–Persian dictionaries are licensed works and cannot be
vendored here.

To load a full dataset, format it as tab-separated lines:

```
headword ⇥ part-of-speech ⇥ IPA ⇥ english gloss ⇥ persian(|separated) ⇥ examples(|) ⇥ synonyms(|)
```

and call `SqliteDictionary.import(tsv)`. It replaces the seed and is indexed for
lookup. Parsing is deliberately lenient so third-party word lists import without
hand-cleaning.

Lookups are tried through `Lemmatizer.candidates()`, so an inflected word on the
page ("running", "children", "happiest") still finds its base entry.

To add an online source, implement `DictionaryProvider` and add it to the
`CompositeDictionary` in `AppContainer`. Offline providers are always consulted
first so a tap answers instantly.

---

## Testing

```bash
cd tools/core-verify && gradle test          # core logic, no SDKs needed
./gradlew :composeApp:testDebugUnitTest      # Android unit tests
```

`tools/core-verify` is a standalone Gradle build that compiles the `core`
sources straight out of `commonMain` and resolves only from Maven Central. It
exists so the logic most likely to be wrong can be tested on any machine with
just a JDK — including one that cannot reach Google's Maven repository.

## Requirements

- Android 8.0 (API 26) or newer — API 26 is the floor for word-level speech
  progress callbacks
- iOS 15.0 or newer
- JDK 17+
