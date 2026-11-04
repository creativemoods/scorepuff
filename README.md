# ScorePuff

![ScorePuff](docs/brand/logo-horizontal.png)

**Ratings that pop.** Compact rating badges for Netflix and Prime Video on Android.

Website: [creativemoods.pt/scorepuff](https://creativemoods.pt/scorepuff)

Android package: `pt.creativemoods.scorepuff`

ScorePuff reads supported accessibility layouts and places a non-touchable badge beside a title or in the upper-right corner of its artwork. This is a proof of concept: streaming-app layouts and metadata coverage vary.

See the [brand assets and guidelines](docs/brand/README.md) for the popcorn mascot, purple-and-gold palette and reusable artwork.

## Badges and matching

- **Number:** average /10 of available IMDb and Rotten Tomatoes scores (RT divided by 10). Missing scores are excluded; zero remains a valid score.
- **White spinner:** lookup queued or in progress.
- **Amber circled ?:** multiple possible matches.
- **No badge:** unsupported layout, no match/scores, provider failure, or insufficient space.

OMDb supplies metadata and scores. Matching requires a unique exact normalized title and available movie/show type, plus the displayed year on supported details pages. Bounded movie namesakes can be distinguished by runtime only when all candidate durations are known and exactly one agrees within two minutes. Large or incomplete result sets remain unresolved.

## Build

Requirements: JDK 17, Android SDK platform 35 and platform tools. Minimum device version: Android 10 / API 29. Android Studio can configure the SDK and generate the ignored `local.properties` file.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On a headless development machine, configure `JAVA_HOME` and `ANDROID_HOME` for your own installations. No developer-specific paths are required in tracked files.

Neither debug nor release builds include an OMDb key. Enter your own activated key in the app settings after installation. No key is required to build, run unit tests or use offline previews. Legacy `omdb.properties` files are ignored and no longer read by the build.

## Use

1. Open ScorePuff and enter your activated **OMDb API key** in settings, then tap **Save key**. You can replace or remove it there without rebuilding; changes apply immediately.
2. Read its disclosure and enable its accessibility service in Android settings. Sideloaded apps may require **Allow restricted settings** first.
3. Open Netflix or Prime Video in portrait and browse supported artwork/details pages. First-time lookups require network access.
4. To confirm a list association, open supported details with the year visible and choose Share → the system's additional sharing options → ScorePuff. Prime uses **Share → More**. Check the title/year/type, IMDb ID and score before saving.
5. Use **Manual match** to preview a specific IMDb ID, save a choice or remove a saved match. **Show ratings** pauses lookups and overlays; disabling the accessibility service removes it.

Sharing is optional: dated details-page matches are saved automatically. Share supplies a catalog ID for reuse on yearless Netflix cards. Captured Prime cards do not expose a GTI, so their confirmation explicitly also saves a title-based choice for cards with the same name/type; namesakes can share that choice. Netflix and Prime mappings are separate. Conflicting manual Prime choices must be removed before replacing them.

A unique title/year/type may have different catalog durations. The Share preview discloses this for confirmation and omits the differing runtime from saved identity evidence. Duration remains strict when needed to disambiguate namesakes.

## App updates

Installations that predate the updater need one manual APK update first.

On startup, ScorePuff checks the latest stable GitHub release and compares its Android `versionCode` with the installed app. A newer signed release prompts **Download update** or **Later**. The download opens the exact version’s `scorepuff.apk` in your browser; open the downloaded APK and confirm installation in Android. Nothing downloads automatically. Updates preserve local settings and associations when installed over a release signed with the same key.

Use **App updates → Check for updates** in settings to retry manually. Offline or unavailable GitHub checks do not block the app. Development builds show available releases in settings but do not offer an incompatible in-place installation.

## Local data and privacy

The API key is stored in app-private preferences, encrypted with an Android Keystore key. The entry field is masked and never displays the saved key. Removing it pauses provider lookups while keeping cached data and associations.

Mappings, scores, negative results and retry state stay in Room on the device. There is no central mapping database or upload of associations. Scores normally refresh after 24 hours; automatic positive mappings after 30 days. Manual choices retain priority until removed.

Update checks contact GitHub for public release information and metadata; they send no OMDb key, streaming titles or saved associations. The browser handles APK downloads, and Android checks the update’s package/signature during installation.

Only recognized title strings, available type/year, resolved IMDb IDs and the API key go to OMDb over HTTPS. Netflix/Prime IDs, screen coordinates and accessibility trees are not sent. Backups are disabled. The app requests no video/audio capture, storage or `SYSTEM_ALERT_WINDOW` permission. The accessibility overlay is non-touchable and hides on other apps, lock screens, foreign focused windows and landscape/playback guards.

Optional title diagnostics stay in memory. Default logs report error types rather than raw requests or provider responses. Do not add an HTTP logger: OMDb keys appear in request query parameters.

## Coverage and offline development

See [screen coverage](docs/screen-coverage.md) for supported components and remaining limits. English layouts are the verified starting point; changes to streaming apps may require parser updates.

The debug app's **Rating playground** uses the real parser/layout/Canvas renderer with sample scores, including loading and ambiguity icons. `tools/run-playground.sh` builds and opens it on an Android emulator; set `PLAYGROUND_AVD` or `PLAYGROUND_SERIAL` if needed. A lightweight browser mock is available at `tools/preview.html`.

```sh
adb shell dumpsys activity service pt.creativemoods.scorepuff/.accessibility.ScorePuffAccessibilityService
adb logcat -s NRService:V NROverlay:V NRSettings:V AndroidRuntime:E
```

## Repository contents

Commit the Android source and resources, unit tests and sanitized fixtures, exported Room schemas, Gradle build files/wrapper, development tools and documentation. Room schemas contain structure, not device records. The wrapper JAR is required build tooling.

Keep API/signing credentials, SDK/JDK downloads, caches, generated packages, logs, database copies, raw UI dumps and personal notes local. `.gitignore` covers these, including legacy OMDb properties files. Browse/watch-list fixtures use synthetic titles, catalog IDs and progress. Focused matching regression cases use public film/catalog metadata, not account identifiers.

Before committing, review `git status --short`, `git add --dry-run .` and your staged diff. Ignore rules do not remove files already tracked or secrets in existing history. See [publishing checks and automated APK releases](docs/publishing.md).

Changing the Android package creates a separate application sandbox; it does not automatically transfer an older installation's data or accessibility authorization.

## Attribution

Ratings supplied by [OMDb](https://www.omdbapi.com/), under its stated [CC BY-NC 4.0 license](https://creativecommons.org/licenses/by-nc/4.0/) and [terms](https://www.omdbapi.com/legal.htm). This is a personal, noncommercial prototype, unaffiliated with Netflix, Amazon, IMDb or Rotten Tomatoes.

## License

ScorePuff's source code is licensed under the
Apache License, Version 2.0.

The ScorePuff name, logo, and associated brand assets
are not licensed under Apache 2.0. All rights to
these brand assets are reserved by their respective owner.

Movie ratings and metadata are provided by third-party
services and are subject to their respective terms.
