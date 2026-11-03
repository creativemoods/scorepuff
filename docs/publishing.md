# Publishing source safely

The public repository should contain source/build tooling and sanitized regression data. Generated APKs are not source releases: a configured debug APK contains its owner's OMDb key.

Before the first commit or a later public push:

```sh
git status --short
git add --dry-run .
git check-ignore -v omdb.properties local.properties .kotlin/ .tools/
# After staging your chosen files:
git diff --cached --stat
git diff --cached
```

Inspect staged changes for credentials, private paths, raw device captures and database records. Keep the blank `omdb.properties.example`, Gradle wrapper JAR, Room schema JSON and sanitized fixtures. Avoid a blanket ignore of JAR/JSON/XML files: those would remove required build or test inputs.

Local-only folders include `local-captures/`, `local-notes/`, `.tools/`, `.gradle/` and `.kotlin/`. Signing keys, environment files, logs, database copies and Android packages are also ignored. Add a specific ignore rule for new private configuration rather than relying on file extensions alone.

`.gitignore` does not protect tracked files or previous commits, and an audit of the current tree cannot certify future commits. If a real credential reaches Git history, remove it from the published history and revoke/replace it at its issuer; merely adding an ignore rule is insufficient. Do not force-add ignored files to bypass these exclusions.

Commits also publish their author/committer names and email addresses. Review your Git identity before the first commit; use your GitHub-provided noreply address if you want to keep your other email private. Configure it for this repository with `git config --local user.email YOUR_GITHUB_NOREPLY_ADDRESS` once you have selected the address.
