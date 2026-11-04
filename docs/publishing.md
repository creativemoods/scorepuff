# Publishing source safely

The public repository should contain source/build tooling and sanitized regression data. Generated APKs are not source releases. Current debug and release builds contain no OMDb key: users supply it in app settings. Older debug APKs built with `omdb.properties` may still contain a key and must not be published.

Before the first commit or a later public push:

```sh
git status --short
git add --dry-run .
git check-ignore -v omdb.properties local.properties .kotlin/ .tools/
# After staging your chosen files:
git diff --cached --stat
git diff --cached
```

Inspect staged changes for credentials, private paths, raw device captures and database records. Keep the Gradle wrapper JAR, Room schema JSON and sanitized fixtures. Legacy OMDb properties files remain local; no credential template is needed. Avoid a blanket ignore of JAR/JSON/XML files: those would remove required build or test inputs.

Local-only folders include `local-captures/`, `local-notes/`, `.tools/`, `.gradle/` and `.kotlin/`. Signing keys, environment files, logs, database copies and Android packages are also ignored. Add a specific ignore rule for new private configuration rather than relying on file extensions alone.

`.gitignore` does not protect tracked files or previous commits, and an audit of the current tree cannot certify future commits. If a real credential reaches Git history, remove it from the published history and revoke/replace it at its issuer; merely adding an ignore rule is insufficient. Do not force-add ignored files to bypass these exclusions.

Commits also publish their author/committer names and email addresses. Review your Git identity before the first commit; use your GitHub-provided noreply address if you want to keep your other email private. Configure it for this repository with `git config --local user.email YOUR_GITHUB_NOREPLY_ADDRESS` once you have selected the address.

## Signed Android release

Release signing reads the ignored root `keystore.properties` file. It contains `storeFile` (relative to the project root), `keyAlias`, `storePassword` and `keyPassword`. Keep all four values local; never commit signing credentials or the keystore. Without this file, release builds remain unsigned and debug/test builds still work.

With JDK 17 configured, build and verify:

```sh
./gradlew testDebugUnitTest lintRelease assembleRelease
# Substitute your installed Android SDK build-tools version/path:
"$ANDROID_HOME/build-tools/35.0.1/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Back up `signing/scorepuff-release.jks` and `keystore.properties` securely outside the repository before distributing a release. Future direct APK updates require the same signing key. Release and debug certificates differ, so a release APK cannot replace an installed debug build with `adb install -r`; keep existing device data intact when planning that switch.

## GitHub Actions release system

Workflow: [android-release.yml](../.github/workflows/android-release.yml). It runs checks on pull requests and pushes to `main`. Pushing a stable `vX.Y.Z` tag starts a release and publishes it automatically after all jobs succeed. No manual review step is built into publication.

1. **Build:** verify the tagged commit belongs to `main` and the tag exactly equals `v` + `versionName`; run release-tool tests, Android unit tests and release lint; build an unsigned APK. No signing secrets or repository write token are supplied to this job.
2. **Sign:** on a separate runner, reconstruct the existing ScorePuff keystore from environment secrets, sign using Android build-tools, and delete the temporary keystore. Gradle never runs in this job. Verify the APK signature against the pinned public certificate in `.github/release-certificate.sha256`, application ID, version, alignment and release contents. Produce the SHA-256 checksum and `release.json` metadata (version, commit, APK hash and certificate fingerprint).
3. **Publish:** on a third runner with no signing secrets, check the downloaded checksum and confirm the remote tag still points to the built commit. Upload all three files to a temporary draft, then publish it immediately as the latest release with generated release notes. This prevents a public release from appearing before its assets finish uploading. Only this job receives `contents: write`; it uses GitHub's built-in token, with no personal access token required.

All actions are pinned to exact commit SHAs. Build intermediates expire after one day and signed workflow artifacts after seven days; release attachments remain available independently. The unsigned job disables Gradle's configuration cache, and the signing job uses no caches.

### One-time GitHub setup

In `creativemoods/scorepuff`, create an Actions environment named **android-release** under **Settings → Environments**. Allow deployments only from tags matching `v*`. Leave required reviewers unset for immediate publication. Add these four environment secrets:

| Secret | Existing local source |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the binary `signing/scorepuff-release.jks` file |
| `ANDROID_STORE_PASSWORD` | `storePassword` in `keystore.properties` |
| `ANDROID_KEY_ALIAS` | `keyAlias` in `keystore.properties` (`scorepuff-release`) |
| `ANDROID_KEY_PASSWORD` | `keyPassword` in `keystore.properties` |

The same release key must be reused. Base64 is a transport encoding, not encryption. These values must be GitHub secrets, never repository files, Actions variables or release attachments. No OMDb key is needed in GitHub. Keep an independent backup of the original keystore and passwords.

If GitHub CLI is available and authenticated, this sends the keystore directly into its secret without printing it:

```sh
base64 -w0 signing/scorepuff-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --env android-release --repo creativemoods/scorepuff
```

Enter the other three values in the environment's secret fields. Enable Actions for the repository and allow the pinned official `actions/*` actions. The workflow requests its own job-level write permission for creating releases; organization policies must permit it. Protect `main` and restrict creation/update/deletion of `v*` tags to release maintainers using repository rulesets.

### Publish a version

Update **both** `versionName` and `versionCode` in `app/build.gradle.kts`. Each distributed Android update must use a larger versionCode. Commit and push the reviewed changes to `main`, then tag that exact commit. For the currently configured version:

```sh
git switch main
# Confirm the intended release commit and a clean working tree before tagging.
git tag -a v0.2.15 -m "ScorePuff 0.2.15"
git push origin v0.2.15
```

Pushing the tag is the publication action. The workflow does not increment versions, create commits, or move tags. It supports stable three-part versions; prereleases need an explicit workflow extension. A version/tag mismatch or a commit outside `main` fails before signing. Existing releases are never overwritten. If an upload/publication failure leaves a draft behind, inspect and delete only that incomplete draft before rerunning the workflow; do not replace an already published APK in place. Publish a new version to distribute changed code.

Release assets:

- `scorepuff.apk` — signed installable download; Gradle’s local output remains `app-release.apk`.
- `scorepuff.apk.sha256` — download integrity checksum.
- `release.json` — public version, commit, checksum and certificate metadata.

The stable download URL for the project website is:

`https://github.com/creativemoods/scorepuff/releases/latest/download/scorepuff.apk`

This system does not push commits/tags, upload secrets, or start a release merely by being added locally. Its first hosted run must be verified in GitHub after the environment is configured and the workflow is committed. Local validation covers workflow syntax and the same build/sign/verify tools, but cannot exercise GitHub permissions or environment policies.

References: [GitHub workflow security guidance](https://docs.github.com/en/actions/reference/security/secure-use), [GitHub CLI release creation](https://cli.github.com/manual/gh_release_create).

## In-app update checks

The app reads GitHub’s latest stable release plus its version-specific `release.json`. Keep publishing **all three assets together** with the existing names. The app checks the package, tag/version, Android versionCode, published size/digest and pinned certificate metadata before offering the download. A larger versionCode triggers the prompt; a display-name change alone does not.

The update button opens that release’s exact `scorepuff.apk` browser URL, rather than the moving latest-download URL. The app does not download or install APKs in the background, and it requests no package-install or storage permission. The browser downloads the file; the user opens it and Android confirms installation and validates compatibility/signing. Published metadata is checked before offering an update; APK-byte and certificate verification on the downloaded file is performed by Android, not by the update-check client.

Startup performs one asynchronous check per settings Activity ViewModel lifetime. Rotation and returning from the browser do not repeat it. Dismissing the prompt leaves the download available in settings; a later app startup can prompt again. A settings button retries failed or previous checks. Missing releases, offline access, rate limits and malformed metadata never block normal app use. Debug builds check availability but link to the release page instead of offering to install an incompatible release over a debug certificate.
