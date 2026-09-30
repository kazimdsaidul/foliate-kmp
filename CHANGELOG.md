# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- GitHub YAML issue forms (`bug_report.yml`, `feature_request.yml`) and pull request template.
- `CODE_OF_CONDUCT.md` adhering to Contributor Covenant v2.1.
- Status badges in `README.md` for Maven Central, CI build status, License, Kotlin, and Compose Multiplatform.
- Text-to-speech read-aloud for the current section: play/pause/stop, sentence-level
  highlighting, and a rate control, using each platform's native speech engine
  (`EpubReaderController.startTts()`/`pauseTts()`/`resumeTts()`/`stopTts()`/`setTtsRate()`,
  `ReaderTtsBar`). Each sentence is queued as one native utterance so speech keeps its
  natural prosody, rather than one utterance per word. Playback covers the current
  spine section only; it stops at the section boundary rather than continuing
  automatically into the next chapter.

### Fixed

### Changed
- **Breaking (beta):** `ReaderTopBar` gained a required `onReadAloudClick: () -> Unit`
  parameter for the new "Read Aloud" button. A caller invoking `ReaderTopBar` directly
  needs to pass it; `ReaderScreen`, the typical entry point, already does.

## [0.1.0-beta01] - 2026-09-21

### Fixed

- **Android: the AAR carried no engine files.** The Android KMP library plugin keeps
  resource processing off by default, so the published AAR contained no assets. The
  foliate-js engine never reached a consumer application, and the reader failed with a
  404. The build now sets `androidResources { enable = true }`.
- **iOS: the engine files were never found.** The scheme handler searched a fixed list
  of bundle paths. None of them matched the real path, and one was a leftover from
  another application. Both platforms now ask the Compose resource reader for the
  path, so the path stays correct after a change of coordinates or packaging.
- **iOS: the navigation delegate was freed at once.** `WKWebView.navigationDelegate`
  is a weak property, and nothing held a strong reference. Load failures were silent.
- **iOS: `evaluateJavaScript` ran on the calling thread.** WKWebView accepts calls on
  the main thread only. Every call now goes to the main queue.
- **Publication: the release build produced unsigned artifacts.** The signing test
  looked for `signing.keyId`, but the release workflow supplies `signingInMemoryKey`.
  Maven Central rejects an unsigned upload.
- **Publication: every POM listed the license and the developer twice.** The
  maven-publish plugin already reads the `POM_*` keys from `gradle.properties`, and an
  explicit `pom {}` block added a second copy of each entry.
- **The controller queue had no limit.** Commands that arrived before the reader was
  ready collected without a bound. The queue now holds at most 64 commands.
- **Reading progress reached storage on every position report.** In scrolled mode the
  engine reports a new position many times each second, and each report caused one
  write. A write now waits 2 seconds, and a burst of reports causes one write of the
  last value. The screen writes the value that still waits before it closes.
- **The configuration went to the engine twice.** `EpubReader` pushed each of the seven
  settings, and the platform view pushed the whole configuration again. One effect now
  does the work.
- **A configuration effect used a `StateFlow` object as its key.** The identity of that
  object never changes, so the effect never ran again when the reader became ready.
- **A new web view kept the old ready state.** The controller now clears that state when
  a web view goes away, so the next web view gets the configuration again.
- **Two highlights made in the same millisecond shared one id.** The id was the clock
  value. It is now a random UUID.
- **A new controller did not reach the web view.** The `remember` and `DisposableEffect`
  keys of both platform views left out the controller.

### Security

- The Android web view now blocks navigation away from the reader origin. A link in an
  untrusted publication could move the web view to a remote page, and that page then
  reached the JavaScript bridge. iOS gets the same guard through
  `decidePolicyForNavigationAction`. The guard allows the `blob:`, `data:` and
  `about:` schemes, because the engine loads each section of the publication into an
  iframe with a `blob:` URL. Remote content inside a publication no longer loads.
- The Android web view no longer sets `allowFileAccess` or `allowContentAccess`. The
  engine and the publication both arrive through the asset loader.
- Both platforms now answer with `Access-Control-Allow-Origin: <reader origin>` in
  place of `*`.
- Both platforms reject an engine path that contains `..`.
- Console output and engine messages hold text from an untrusted publication. A release
  build no longer writes them to the device log. Turn them on for one device with
  `adb shell setprop log.tag.Foliate DEBUG`, or with the `FOLIATE_DEBUG` environment
  variable in the Xcode scheme. A warning or an error always reaches the log.

### Changed

- **The Maven group is now `io.github.asadullah012`**, and the Kotlin packages moved
  from `io.github.galib.foliate` to `io.github.asadullah012.foliate`. The old group
  named a GitHub account that the author does not own, so Maven Central could not
  verify the namespace.
- The engine files exist once, in `commonMain/composeResources`. The duplicate copy in
  `androidMain/assets` is gone. This removes 240 KB from every Android application.
- Both modules turn on `explicitApi()` and Kotlin ABI validation. `./gradlew check`
  now fails on an unplanned change of the public API.
- `foliate-kmp-core` ships consumer R8 rules, so an application that uses R8 needs no
  extra configuration.
- The Compose resource class has a pinned package, `io.github.asadullah012.foliate.resources`.
  The default name derives from the Maven coordinates and would change with them.
- `material-icons-extended` is declared in the version catalog. The Compose
  Multiplatform accessor for it is deprecated and pinned to 1.7.3.
- iOS uses `androidx.compose.ui.viewinterop.UIKitView`. The old
  `androidx.compose.ui.interop.UIKitView` is deprecated.

### Added

- `THIRD-PARTY-NOTICES.md`, which carries the license text of foliate-js, zip.js and
  fflate. The file also ships inside the artifacts, because the MIT license needs the
  notice to travel with every copy.
- `CONTRIBUTING.md`.
- The build workflow publishes to the local Maven repository and checks that the AAR
  holds the engine files and the consumer R8 rules, and that each POM lists one
  license and one developer. These checks fail on the first two defects above.
- The release workflow stops with a clear message when the signing key is empty.
- Tests for the bound of the command queue, the MIME type table, the debounce of the
  progress write, and the uniqueness of an annotation id.

