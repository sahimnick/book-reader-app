# Book Reader

An EPUB and PDF reader for Android and iOS, built as a Kotlin Multiplatform app
with a single Compose Multiplatform UI shared by both platforms.

- **Read** EPUB (2 and 3) and PDF, with table of contents, images and saved
  reading position.
- **Listen** with the device voice, the sentence being spoken highlighted as it
  is read, and the current word highlighted within it where the engine supports
  it.
- **Look up** any word or phrase by tapping it — English definition, Persian
  meaning, IPA pronunciation and letter-by-letter spelling. Tapping "up" in
  "he gave up" answers *give up*, not *up*.
- **Study** the words you saved as flashcards, scheduled by SM-2 spaced
  repetition.
- **Read scanned PDFs** — pages with no text layer are recognised on the device,
  which makes them speakable, tappable and selectable like any other page.
- **See which words you will not know** before you meet them, from a reading
  profile built out of what you have looked up before.

With your own API key, in Settings, four more:

- **Sense in context** — what the word means *in this sentence*, rather than
  every meaning it has ever had.
- **Study material** — a cloze from the sentence you met the word in, a simpler
  second example, and a mnemonic linking the English and the Persian.
- **Ask about a passage**, and **"what happened so far"** when you come back to
  a book after a fortnight.
- **A paragraph in Persian**, where the dictionary only gives you words.

---

## Getting the app

**Android:** [download the latest APK](../../releases/latest/download/BookReader.apk).
That link never changes and always points at the newest green build. It is
signed with the debug key, so it installs on any Android 8.0+ device once
"install from unknown sources" is enabled. (The same APK is also on each
[Actions](../../actions) run as the `android-apk` artifact, but artifacts expire
after 90 days and need a GitHub login.)

There is no `.ipa`, and CI cannot make one: signing an iOS app requires an Apple
Developer certificate that only the developer holds. CI compiles the iOS
framework and builds the app unsigned; to get something installable, build it
yourself on a Mac — see [iOS](#ios).

### What is actually verified

| Layer | State |
|---|---|
| Platform-independent core | **202 tests passing** in CI on every push |
| Android APK | **Compiles and packages** in CI (debug + release) |
| Android on a device | **14 instrumented tests** on a CI emulator: schema, migrations, repositories, look-up, and the read-aloud highlight |
| iOS shared framework | **Compiles** in CI for device and simulator targets |
| iOS Xcode host app | **Builds** unsigned for the simulator in CI |
| Read-aloud audio | **Not verified** — CI emulators have no voice data |
| Anything AI | **Not verified** — no test supplies a key or calls the API |
| iOS at runtime | **Not verified — nothing has run on an iOS device** |

The last rows matter. Audio is driven through a fake speech engine in tests, so
what is proven is that the highlight advances correctly given the callbacks a
real engine emits — not that any sound comes out. The AI features are built
from tested prompts and parsers, but no request has ever been sent.

The tested core is the EPUB container parser, the ZIP reader, the DEFLATE
decompressor, the XML/XHTML tokenizer, sentence segmentation, word
tokenization, English lemmatization, phrasal-verb detection, the read-aloud
plan, OCR layout reconstruction, the assistant's prompts and reply parsing, the
vocabulary difficulty model, and the SM-2 scheduler — including an end-to-end
parse of a real EPUB archive. Run it with nothing but a JDK:

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

**Xcode 16 or newer is required.** Current XcodeGen emits project format
`objectVersion 77`, and older Xcode rejects it outright with "the project
cannot be opened because it is in a future Xcode project file format (77)".
XcodeGen ignores the `objectVersion` option that would pin an older format, so
use a matching Xcode — or generate the project once on a machine that has one
and commit the result.

**`libsqlite3` must be linked.** SQLDelight's native driver reaches system
SQLite through SQLiter's cinterop bindings, which declare `_sqlite3_*` without
pulling in the library, so the app fails to link with a wall of undefined
symbols. `-lsqlite3` is set both on the Kotlin framework and on the app target
in `iosApp/project.yml`; keep it if you regenerate the project by hand.

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
│   │   ├── pdf/         OCR layout: words → lines → paragraphs → text offsets
│   │   ├── ai/          assistant prompts, reply parsing, recap windowing
│   │   ├── vocab/       word difficulty, reading profile, book fit
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

**System-bar insets are handled once, in the theme.** The app targets API 35,
where Android 15 enforces edge-to-edge and ignores any request to opt out, so
the window genuinely extends under both system bars. Padding in
`BookReaderTheme` consumes the insets, which means every `Scaffold`, app bar and
navigation bar inside sees them as already handled and does not pad twice.

**The reading profile carries no frequency corpus.** Usable English frequency
lists are licensed works, and a truncated one would misjudge exactly the
long-tail words that matter. Difficulty is estimated from a word's own surface —
length, syllables, and the Latinate and Greek affixes that mark the academic
layer of English — calibrated against a list of words no reader of English
novels looks up. It is a heuristic, and the app presents it as one. What it
never gets wrong is the part that matters: a word the reader has already looked
up is always marked, because they said so.

**Everything AI is optional and inert without a key.** No key ships in the app —
anything compiled into an APK can be extracted by whoever downloads it, and it
would be the developer's key paying for every user's look-ups. Readers supply
their own in Settings; it is stored in the app's private database and sent only
to the model provider. Every assistant call returns nothing on any failure, so a
model that is unreachable or slow leaves the page alone.

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
cd tools/core-verify && gradle test                  # core logic, no SDKs needed
./gradlew :composeApp:connectedDebugAndroidTest      # on a device or emulator
```

`tools/core-verify` is a standalone Gradle build that compiles the `core`
sources straight out of `commonMain` and resolves only from Maven Central. It
exists so the logic most likely to be wrong can be tested on any machine with
just a JDK — including one that cannot reach Google's Maven repository. It
compiles the real sources rather than a copy, so it cannot drift from them.

The instrumented tests are the only place the app's wiring actually runs: the
SQLDelight schema and its migrations against real SQLite, the seeded dictionary,
the repositories, and the read-aloud highlight driven by a fake speech engine.
CI fails the build if the suite runs fewer tests than expected, because a test
task with no tests still exits 0.

## Requirements

- Android 8.0 (API 26) or newer — API 26 is the floor for word-level speech
  progress callbacks
- iOS 15.0 or newer
- JDK 17+
