# kscriptx

Kotlin scripting with **kscript feature parity**: Coursier for dependencies, GraalVM
**native kotlinc** for compiles, content-addressed caching, and in-process script execution.

Successor to [kscript](https://github.com/kscripting/kscript). Gradle is used only to **build kscriptx** and for optional `--idea`
projects — not on the script hot path.

Stack: **Kotlin 2.4.10** · **Gradle 9.6.1** (project build only).

## Requirements

- **JDK 17+**
- **Native kotlinc** — bundled in Debian packages and Linux release tarballs.
  From source builds, install under `~/.kscriptx/native-kotlinc` (see below).

## Install

### From GitHub Releases (recommended)

Releases are built automatically when you push a tag `v*` (e.g. `v0.1.1`), or via
**Actions → Release → Run workflow**.

**Debian / Ubuntu** (includes native kotlinc)

```bash
# amd64 or arm64 (Raspberry Pi) matching your machine
sudo apt install ./kscriptx_<ver>_amd64.deb
# or: sudo apt install ./kscriptx_<ver>_arm64.deb
kscriptx examples/hello.kts
```

**Linux (portable tarball)** — native kotlinc included under `bin/native-kotlinc/`

```bash
tar -xzf kscriptx-<ver>-linux-amd64.tar.gz   # or linux-arm64
export PATH="$PWD/kscriptx-<ver>/bin:$PATH"
```

**Windows (portable zip)**

```powershell
# unzip kscriptx-<ver>-windows-amd64.zip
# requires JDK 17+ on PATH / JAVA_HOME
# native kotlinc is not bundled for Windows yet — use WSL or build on Linux
.\bin\kscriptx.bat examples\hello.kts
```

### From source

```bash
./gradlew :cli:build
./scripts/build-native-kotlinc.sh   # GraalVM CE 21+ with native-image
```

Add `bin/` to `PATH`, or run:

```bash
./bin/kscriptx examples/hello.kts
```

Windows: `gradlew.bat :cli:build` then `bin\kscriptx.bat examples\hello.kts`.

### Local packaging

```bash
INCLUDE_NATIVE=1 ./scripts/package-dist.sh
./scripts/package-tarball.sh
./scripts/package-deb.sh           # arch-specific .deb with native (needs dpkg-deb)
./scripts/package-windows.sh
./scripts/package-native-kotlinc.sh
```

## Usage

```text
kscriptx [options] <script> [args...]
```

`<script>` may be a `*.kts` / `*.kt` file, URL, `-` (stdin), or an inline snippet.

| Flag | Meaning |
|------|---------|
| `-t` / `--text` | Text-processing mode (`lines` + kscript support API) |
| `--idea` | Generate a Gradle/IntelliJ project and open it |
| `--package` | Fat-jar + launcher next to the script |
| `--interactive` | REPL with script classpath |
| `--clear-cache` | Wipe compile + URL caches |
| `--add-bootstrap-header` | Embed self-bootstrap shebang |
| `-h` / `--help` | Help |
| `-v` / `--version` | Version |

### Annotations

```kotlin
@file:DependsOn("org.jsoup:jsoup:1.17.2")
@file:DependsOnMaven("g:a:v")
@file:Repository("https://repo.example.com/maven")
@file:Repository("id", "https://...", user="{{USER}}", password="{{PASS}}")
@file:Import("utils.kt")
@file:CompilerOptions("-opt-in=kotlin.RequiresOptIn")
@file:KotlinOptions("-J-Xmx1g")
@file:EntryPoint("pkg.MainKt")   // for .kt files
```

### Examples

```bash
kscriptx examples/hello.kts
kscriptx examples/include_demo.kts
kscriptx examples/App.kt
kscriptx --idea examples/hello.kts
kscriptx --package examples/hello.kts
echo 'println("piped")' | kscriptx -
./examples/run_now.kts
```

## Architecture

```text
CLI  →  parse annotations / resolve imports
     →  content-cache hit → run (ClassLoader or forked java)
     →  miss:
            Coursier resolve (prefer ~/.gradle/caches/modules-2)
            native kotlinc → ~/.kscriptx/cache/<hash>
            run
```

Typical timings (this machine): **~0.2–0.4s** cold compile with native kotlinc;
**~0.09–0.12s** cache hit after AppCDS warms (first hit builds `~/.kscriptx/cds/`).

## Native kotlinc

Bundled in Debian packages (`/usr/lib/kscriptx/native-kotlinc`) and Linux release tarballs
(`bin/native-kotlinc/`). From a source checkout, build it yourself:

```bash
./gradlew :cli:build
./scripts/build-native-kotlinc.sh
```

Needs **GraalVM CE 21+** with `native-image` (e.g. `sdk install java 21.0.2-graalce`) and a JDK
with `jmods`. Build takes ~1–2 minutes and several GB RAM.

Lookup order: `KSCRIPTX_NATIVE_KOTLINC` → `~/.kscriptx/native-kotlinc` → install-relative
`native-kotlinc/` next to `kscriptx.jar` → `/usr/lib/kscriptx/native-kotlinc`.

Layout:

```text
native-kotlinc/
  kotlinc-native
  kotlin-compiler-embeddable.jar
  java.base.jar
  kotlin-home/lib/...
```

Agent metadata and PathUtil substitution: `scripts/native-kotlinc/`.

## Layout / env

| Path / env | Purpose |
|------------|---------|
| `KSCRIPTX_DIRECTORY` | Override home (default `~/.kscriptx`) |
| `$home/cache` | Compiled classes by content hash |
| `$home/deps-cache` | Resolved dependency classpaths |
| `$home/m2` | Maven mirror seeded from Gradle modules-2 |
| `$home/coursier-cache` | Coursier download cache |
| `$home/url-cache` | Downloaded URL scripts / imports |
| `$home/idea` | Generated IDEA projects |
| `$home/native-kotlinc` | Native kotlinc install |
| `KSCRIPTX_NATIVE_KOTLINC` | Override native install dir |
| `KSCRIPTX_JAVA_OPTS` | Extra JVM flags for the launcher |
| `KSCRIPT_COMMAND_IDEA` | IDEA launcher |
| `KSCRIPT_COMMAND_GRADLE` | Optional gradle for `--idea` |
| `$home/cds/` | AppCDS archive (auto-built; speeds warm starts) |

Optional config file (`kscript.properties`): `scripting.preamble`, `scripting.kotlin.opts`,
`scripting.repository.url`, etc.

## License

MIT — inspired by [kscripting/kscript](https://github.com/kscripting/kscript).

## CI

GitHub Actions (`.github/workflows/ci.yml`) on every push/PR:

- Unit tests + **JaCoCo** coverage (XML/HTML artifacts)
- Micro-benchmarks (parser / hash / wrapper) and CLI timings (`scripts/bench.sh`)
- Job summary with coverage % and performance tables

Locally:

```bash
./gradlew :cli:coverage
./scripts/coverage-summary.sh cli/build/reports/jacoco/test/jacocoTestReport.xml
./gradlew :cli:build && ./scripts/bench.sh
```
