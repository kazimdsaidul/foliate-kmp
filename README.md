# foliate-kmp

[![Maven Central](https://img.shields.io/maven-central/v/io.github.asadullah012/foliate-kmp-core?color=blue&label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.asadullah012/foliate-kmp-core)
[![Build & Test](https://github.com/asadullah012/foliate-kmp/actions/workflows/build.yml/badge.svg)](https://github.com/asadullah012/foliate-kmp/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-purple.svg)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.0-blue.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)

**foliate-kmp** is a Kotlin Multiplatform (Compose Multiplatform) EPUB reader library.
It uses [foliate-js](https://github.com/johnfactotum/foliate-js) as its engine. It
gives you standards-compliant EPUB rendering with discrete pagination, CFI navigation,
annotations, search and themes on **Android** and **iOS**.

> **Stability: beta.** The public API can change in a minor release until version 1.0.
> Each change appears in [CHANGELOG.md](CHANGELOG.md).

---

## Preview

| Sample Library | Reading Experience | Typography & Themes | Table of Contents |
| :---: | :---: | :---: | :---: |
| <img src="docs/images/home_screen.png" width="200" alt="Sample Library" /> | <img src="docs/images/reading_view.png" width="200" alt="Reading Experience" /> | <img src="docs/images/appearance_sheet.png" width="200" alt="Appearance Controls" /> | <img src="docs/images/navigation_toc.png" width="200" alt="TOC Navigation" /> |

---

## Artifacts

| Artifact | Description |
| :--- | :--- |
| **`io.github.asadullah012:foliate-kmp-core`** | Headless engine, WebView platform bridges (Android `WebViewAssetLoader`, iOS `WKURLSchemeHandler`), `EpubReaderController`, data models and storage interfaces. |
| **`io.github.asadullah012:foliate-kmp-compose`** | Complete Material 3 reading screen with a top navigation bar, a bottom progress scrubber, a TOC drawer, an appearance sheet, an annotations dialog, a footnote sheet and in-book search. |

## Supported platforms

| Target | Supported | Note |
| :--- | :--- | :--- |
| Android | Yes | `minSdk` 26, `compileSdk` 37 |
| iOS device (`iosArm64`) | Yes | |
| iOS simulator, Apple silicon (`iosSimulatorArm64`) | Yes | |
| iOS simulator, Intel (`iosX64`) | No | Compose Multiplatform publishes no `iosX64` artifacts. |
| Desktop, JVM, web | No | |

---

## Installation

Add the library to your `build.gradle.kts`:

```kotlin
// Option 1: the complete reader screen (this includes the core)
commonMain.dependencies {
    implementation("io.github.asadullah012:foliate-kmp-compose:0.1.0-beta01")
}

// Option 2: the headless engine only, for your own UI
commonMain.dependencies {
    implementation("io.github.asadullah012:foliate-kmp-core:0.1.0-beta01")
}
```

### R8 and ProGuard

You need no extra configuration. `foliate-kmp-core` carries its own consumer rules
inside the AAR.

---

## Quickstart

### 1. The complete reader screen (`foliate-kmp-compose`)

```kotlin
import androidx.compose.runtime.Composable
import io.github.asadullah012.foliate.compose.ui.ReaderScreen

@Composable
fun MyBookScreen(epubFilePath: String, onBack: () -> Unit) {
    ReaderScreen(
        filePath = epubFilePath,
        bookTitle = "Pride and Prejudice",
        onNavigateBack = onBack
    )
}
```

### 2. The headless reader view (`foliate-kmp-core`)

Use this to build your own reader UI, toolbars and gesture controls:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.fillMaxSize
import io.github.asadullah012.foliate.EpubReaderController
import io.github.asadullah012.foliate.model.EpubReaderConfig
import io.github.asadullah012.foliate.model.EpubReaderTheme
import io.github.asadullah012.foliate.ui.FoliateReaderView

@Composable
fun CustomReader(bookPath: String) {
    val controller = remember { EpubReaderController() }

    FoliateReaderView(
        bookPath = bookPath,
        controller = controller,
        config = EpubReaderConfig(theme = EpubReaderTheme.SEPIA, fontSize = 20),
        modifier = Modifier.fillMaxSize()
    )
}
```

---

## Sample App

The repository includes a runnable Compose Multiplatform sample app in the `:sample` module. It bundles a public-domain copy of *Alice's Adventures in Wonderland* to demonstrate pagination, themes, CFI restoration, search, and text annotations out of the box.

### Run on Android
```bash
./gradlew :sample:installDebug
```

### Run on iOS
Open the Xcode project in Xcode and click **Run**:
```bash
open sample/iosApp/iosApp.xcodeproj
```
Or build the framework for the Apple Silicon simulator:
```bash
./gradlew :sample:compileKotlinIosSimulatorArm64
```

---

## Features

- **EPUB rendering**: the modular foliate-js engine does the work.
- **Canonical Fragment Identifiers (CFI)**: exact reading positions that you can save
  and restore.
- **Paginated and continuous flow**: discrete page turns, or continuous vertical scroll.
- **Themes**: Light, Sepia, Slate, Dark and AMOLED black.
- **Typography**: font family, font size, line height, margins and text alignment.
- **In-book search**: live substring matches with an excerpt and a jump to the CFI.
- **Annotations and highlights**: persistent highlights with a color picker and notes.
- **Footnotes**: automatic detection and a popup.
- **Text-to-speech**: read the current section aloud, one sentence at a time, with
  sentence highlighting and a rate control, using each platform's native speech engine.
- **Pluggable persistence**: implement `EpubReaderStorage` to save progress, bookmarks
  and highlights to Room, SQLite, DataStore or a remote database. A reading position
  waits 2 seconds before it reaches storage, so a scroll causes one write and not one
  write for each reported position. `ReaderScreen` writes the position that still waits
  before it closes.

---

## Security model

The reader runs inside a web view, and an EPUB publication is untrusted input. The
library therefore applies these rules:

- The web view stays on the reader origin. A link in a publication cannot move the web
  view to a remote page, because that page would then reach the JavaScript bridge. The
  guard lets through the reader origin and the `blob:`, `data:` and `about:` schemes,
  because the engine puts each section of the publication into an iframe with a `blob:`
  URL. Remote content inside a publication does not load.
- The web view has no direct access to the file system. The engine and the publication
  both arrive through an asset loader or a URL scheme handler.
- A request for an engine file that contains `..` is rejected.
- Console output and engine messages can quote the publication, so a release build does
  not write them to the device log. Turn them on for one device with
  `adb shell setprop log.tag.Foliate DEBUG`, or with the `FOLIATE_DEBUG` environment
  variable in the Xcode scheme. A warning or an error always reaches the log.

---

## License

MIT License. See [LICENSE](LICENSE).

The artifacts contain a copy of foliate-js, zip.js and fflate. See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for the full license text of each one.

## Security

To report a vulnerability privately, see [SECURITY.md](SECURITY.md).

## How to contribute

See [CONTRIBUTING.md](CONTRIBUTING.md).

