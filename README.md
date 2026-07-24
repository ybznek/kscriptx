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
./scripts/build-native-kotlinc.sh   # newest GraalVM CE (SDKMAN / GRAALVM_HOME)
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

Typical timings (this machine): **~0.7s** cold compile with native kotlinc;
**~0.11–0.16s** cache hit without daemon; **~0.01s** cache hit with the background daemon.

Disable the daemon for a single script (including shebang):

```bash
#!/usr/bin/env -S kscriptx --no-daemon
```

Or `KSCRIPTX_DAEMON=0`. The launcher talks to the daemon via a small Rust helper (`bin/kscriptx-dclient`), built automatically when `cargo` is on `PATH`.

## Native kotlinc

Bundled in Debian packages (`/usr/lib/kscriptx/native-kotlinc`) and Linux release tarballs
(`bin/native-kotlinc/`). From a source checkout, build it yourself:

```bash
./gradlew :cli:build
./scripts/build-native-kotlinc.sh
```

Needs the **newest GraalVM CE** with `native-image`. `./scripts/build-native-kotlinc.sh`
auto-selects the newest SDKMAN install and, by default (`ENSURE_LATEST_GRAAL=1`), installs
the latest published `*-graalce` if newer. Override with `GRAALVM_HOME=…` or
`ENSURE_LATEST_GRAAL=0`. Also needs a JDK with `jmods` for the trimmed `java.base.jar`.
Build takes ~1–2 minutes and several GB RAM.

**GraalVM 25 CE build defaults** (one-shot compiler process):

| Flag | Default | Why |
|------|---------|-----|
| `--gc=epsilon` | yes (`NI_GC`) | No GC pauses; ~30–40% faster alloc on short-lived workloads vs serial |
| `-O3` | yes (`NI_OPT`) | Max AOT optimizations |
| `-march=x86-64-v3` | amd64 (`NATIVE_MARCH`) | AVX2+ codegen; use `native` locally or `compatibility` for oldest CPUs |
| analysis zip-strip | yes (`SHRINK_EMBEDDABLE`) | Drop wasm/konan/js backends + jline from analysis CP only |
| `--features=KotlincReachabilityFeature` | yes | Keep PicoContainer + headless AWT/Swing (Graal 25 reachability) |

PGO (`--pgo`) is still **not** in GraalVM CE 25 — only Oracle/EE. See `scripts/pgo-profile-kotlinc.sh`.
ProGuard/R8 shrink on embeddable is **not** used (breaks ASM/Kotlin enums).

**Cold compile reality:** a tiny `hello` is ~**200ms of real K2 work** inside `kotlinc-native`
(fork + compile). Re-entrant `compiler.exec()` in a warm JVM is also ~180–200ms — so a
persistent kotlinc worker barely helps for small scripts. Bigger levers: content-cache hits
(~10ms with the CLI daemon), skipping Coursier when jars are already in `~/.m2` /
`$KSCRIPTX_DIRECTORY/m2` (seeded from Gradle `modules-2`), and a thinner native *image*
rebuild (jansi no-extract, slimmer analysis CP; optional PGO via `./scripts/pgo-profile-kotlinc.sh`
when your GraalVM supports it). `java.base.jar` is auto-trimmed (~15MB → ~10MB) by
`scripts/trim-java-base.sh`. The PathUtil sidecar must stay the full embeddable jar
(resources like `compiler-cli-root.xml`); `scripts/shrink-embeddable-for-ni.sh` only feeds
native-image analysis.

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
| `$home/m2` | Private Maven mirror; also seeds/writes `~/.m2/repository` for Gradle `mavenLocal()` |
| `$home/coursier-cache` | Coursier download cache |
| `$home/url-cache` | Downloaded URL scripts / imports |
| `$home/idea` | Generated IDEA projects |
| `$home/native-kotlinc` | Native kotlinc install |
| `KSCRIPTX_NATIVE_KOTLINC` | Override native install dir |
| `KSCRIPTX_JAVA_OPTS` | Extra JVM flags for the launcher |
| `KSCRIPTX_DAEMON` | Set `0` to disable the persistent JVM daemon |
| `KSCRIPTX_DAEMON_IDLE_MINUTES` | Idle timeout before daemon exits (default `30`; `__ping__` does not reset) |
| `--no-daemon` / `--daemon` | Per-run override (works in shebang: `#!/usr/bin/env -S kscriptx --no-daemon`) |
| `KSCRIPTX_M2` | Override shared Maven local (default `~/.m2/repository`) |
| `KSCRIPT_COMMAND_IDEA` | IDEA launcher |
| `KSCRIPT_COMMAND_GRADLE` | Optional gradle for `--idea` |
| `$home/cds/` | AppCDS archive (auto-built; speeds warm starts) |
| `$home/daemon/` | Persistent CLI daemon socket/port |
| `$home/fast-cache/` | mtime-based script fingerprint → content hash |
| `bin/kscriptx-dclient` | Rust loopback client used by the launcher (built with `cargo`) |

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
