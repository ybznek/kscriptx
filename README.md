# kscriptx

Kotlin scripting with **kscript feature parity**: Coursier for dependencies, GraalVM
**native kotlinc** for compiles, content-addressed caching, and a compile daemon
with client-side script JVM launch.

Successor to [kscript](https://github.com/kscripting/kscript). Gradle is used only to **build kscriptx** and for optional `--idea`
projects — not on the script hot path.

Stack: **Kotlin 2.4.10** · **Gradle 9.6.1** (project build only).

## Requirements

- **JDK 17+**
- **Native kotlinc** — bundled in Debian packages and Linux release tarballs.
  From source builds, install under `~/.kscriptx/native-kotlinc` (see below).

## Install

### SDKMAN (recommended)

Once the `kscriptx` candidate is live on SDKMAN:

```bash
sdk install kscriptx
sdk upgrade kscriptx          # later updates
kscriptx examples/hello.kts
```

Vendor onboarding / release automation: [docs/sdkman.md](docs/sdkman.md).

### From GitHub Releases

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

**SDKMAN / portable JVM zip** (JDK 17+; no bundled native kotlinc)

```bash
unzip kscriptx-<ver>-bin.zip
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
./scripts/package-sdkman.sh        # universal JVM zip for SDKMAN (kscriptx-<ver>-bin.zip)
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
| `--jar` / `--standalone-jar` | Standalone fat/uber jar only (`java -jar`; no launcher) |
| `--package` | Same fat jar **plus** a shell/cmd launcher next to the script |
| `--native` | GraalVM native-image **executable** next to the script (keeps fat jar) |
| `--native-shared` | GraalVM **shared library** (`lib<stem>.so` / `.dylib` / `.dll`) + helper |
| `--native-runner` | Shared lib + thin **C runner** (`<stem>-runner`); implies `--native-shared` |
| `--native-config-dir <dir>` | Reachability metadata for `native-image` |
| `--native-image-arg <arg>` | Extra `native-image` flag (repeatable) |
| `--graalvm-home <path>` | GraalVM home for native builds (else `GRAALVM_HOME` / PATH / SDKMAN) |
| `--proguard` | Shrink/optimize the fat jar with ProGuard |
| `--proguard-home <path>` | ProGuard home for `--proguard` (else `PROGUARD_HOME` / PATH) |
| `--proguard-jar <path>` | Explicit `proguard.jar` (else `PROGUARD_JAR`) |
| `--compile-beside` | Mirror compile artifacts next to the script (`<stem>.kscriptx/`) |
| `--interactive` | REPL with script classpath |
| `--clear-cache` | Wipe compile + URL caches |
| `--add-bootstrap-header` | Embed self-bootstrap shebang |
| `-h` / `--help` | Help |
| `-v` / `--version` | Version |

`--jar` and `--package` both produce a **standalone fat jar** (deps + `Main-Class`,
runnable with `java -jar <stem>.jar`). Prefer `--jar` when you only want the jar;
use `--package` when you also want the convenience launcher.

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
kscriptx examples/multi/main.kts          # multi-file @file:Import
kscriptx examples/App.kt
kscriptx examples/ktor-hello.kts          # DependsOn: Ktor Netty server
kscriptx examples/ffi-libc.kts            # Panama FFM → glibc (Linux, JDK 22+)
kscriptx examples/gtk4-hello.kts          # Panama FFM → GTK4 window (Linux)
# headless GTK check: xvfb-run -a kscriptx examples/gtk4-hello.kts --self-test
kscriptx --idea examples/hello.kts
kscriptx --jar examples/hello.kts              # standalone fat jar only
kscriptx --standalone-jar examples/hello.kts   # alias for --jar
kscriptx --package examples/hello.kts          # fat jar + launcher
kscriptx --compile-beside --jar examples/hello.kts
kscriptx --proguard --jar examples/hello.kts   # ProGuard, jar only (needs ProGuard)
kscriptx --proguard --proguard-home "$PROGUARD_HOME" examples/hello.kts
kscriptx --proguard --native examples/hello.kts  # ProGuard then native-image
kscriptx --native examples/hello.kts           # needs GraalVM native-image
kscriptx --package --native examples/hello.kts # jar + .native + smart launcher
kscriptx --native --graalvm-home "$GRAALVM_HOME" examples/hello.kts
kscriptx --native-shared examples/hello.kts    # shared library + JVM/C helper
kscriptx --native-shared --native-runner examples/hello.kts
kscriptx --native --native-config-dir=./ni-config examples/hello.kts
kscriptx --native --native-image-arg=-O2 examples/hello.kts
echo 'println("piped")' | kscriptx -
./examples/run_now.kts
```

### Standalone fat jar (`--jar` / `--standalone-jar`)

Builds an uber jar next to the script:

```text
examples/hello.jar          # java -jar examples/hello.jar
```

With `--compile-beside`, the same jar is also copied into the beside tree:

```text
examples/hello.kscriptx/hello.jar
```

`--package` writes that same fat jar **and** a launcher (`examples/hello` /
`examples/hello.cmd`). `--proguard` / `--native` always build the fat jar first.

**Pipeline:** compile → fat jar → optional ProGuard → optional `--native-shared` →
optional `--native` → launcher (`--package`, bare `--proguard` without `--jar`,
or smart wrapper when `--package --native`).

### Packaging mode matrix

| Flags | Artifacts |
|-------|-----------|
| `--jar` | `<stem>.jar` |
| `--package` | `<stem>.jar` + JVM launcher `<stem>` |
| `--native` | `<stem>.jar` + native executable `<stem>` |
| `--package --native` | `<stem>.jar` + `<stem>.native` + smart `<stem>` (prefer native, else `java -jar`) |
| `--native-shared` | `lib<stem>.{so,dylib}` / `<stem>.dll` + header + `<stem>-shared` helper + loader jar |
| `--native-runner` | above + `<stem>-runner` (C, `dlopen`/link + isolate lifecycle) |

### Compile beside the script

With `--compile-beside`, kscriptx still uses the global content cache under
`$KSCRIPTX_DIRECTORY` (default `~/.kscriptx`), and **also** mirrors the compile
output next to the script file:

```text
examples/hello.kts
examples/hello.kscriptx/
  classes/      # .class files
  classpath     # dependency classpath
  entry         # main class (e.g. ScriptKt)
  ok
  hello.jar     # when also packaging (--jar / --package / --proguard / --native)
```

Requires a file script (not stdin / bare inline). Default behavior is unchanged
when the flag is omitted.

### ProGuard packaging (`--proguard`)

`--proguard` builds the usual fat jar, then runs ProGuard with a curated
Kotlin-safe config and replaces the jar in place. Pair with `--jar` for
jar-only output, or omit `--jar` to also write the `--package`-style launcher.
Resolution order for ProGuard:

1. `--proguard-jar` / `--proguard-jar=<path>`
2. `--proguard-home` / `--proguard-home=<path>` (`lib/proguard.jar` or `proguard.jar`)
3. `PROGUARD_JAR`
4. `PROGUARD_HOME`
5. `proguard` / `proguard.sh` on `PATH` (jar beside the script / parent `lib/`)

If ProGuard cannot be found, kscriptx **fails with an error** (same style as
`--native`). Safe defaults keep the main entry, Kotlin metadata / annotations /
Signature attributes, `META-INF/services`, and coroutine field names; they avoid
`-overloadaggressively`, package repacking, and class merging.

**Pipeline when combining flags:** compile → fat jar → ProGuard (if `--proguard`) →
native-image (if `--native`) → launcher when `--package` or bare `--proguard`
without `--jar`.

**Limits:** Aggressive dependency graphs or heavy reflection may still need
extra keep rules (not configurable yet). Real ProGuard must be installed
separately; CI tests do not download it.

### Native packaging (`--native` / `--native-shared`)

`--native` builds the usual fat jar, then runs GraalVM `native-image` and writes a
native **executable**. Alone it uses the basename `<stem>` (same as `--package`).
Combined with `--package`, the native binary is `<stem>.native` and `<stem>` is a
**smart launcher** that prefers the native binary, else `java -jar <stem>.jar`.

`--native-shared` builds a GraalVM **shared library** with a generated
`@CEntryPoint` bridge (`kscriptx_create_isolate` / `kscriptx_run` /
`kscriptx_tear_down_isolate`) so scripts need no annotations. Add
`--native-runner` to compile a thin C runner (`cc`) that creates an isolate,
invokes the script, and tears down. The `<stem>-shared` helper prefers the C
runner when present, else attempts the JVM loader jar.

**Reachability / extra flags (Phase 3):**

- `--native-config-dir <dir>` → `-H:ConfigurationFileDirectories=<dir>`
- `--native-image-arg <arg>` → appended to every `native-image` invocation (repeatable)

Resolution order for GraalVM:

1. `--graalvm-home` / `--graalvm-home=<path>`
2. `GRAALVM_HOME`
3. Newest SDKMAN `*-graalce` / `*-graal` with `bin/native-image`
4. `native-image` on `PATH`

If `native-image` cannot be found, kscriptx **fails with an error** (it does not
silently fall back to the JVM launcher). `--native-runner` also needs a C
compiler (`cc` / `clang` / `gcc`) on `PATH`.

**Limits:** AOT constraints apply (reflection / resources may need reachability
metadata — use `--native-config-dir`). Large dependency sets make analysis slow
and binaries large. On Windows, GraalVM `native-image` typically needs MSVC.
Prefer Linux/macOS or WSL. The HotSpot loader jar can `System.load` the shared
lib for tooling; full isolate argv marshalling is supported via the C runner.

## Architecture

```text
CLI  →  parse annotations / resolve imports
     →  content-cache hit → run (ClassLoader or forked java)
     →  miss:
            Coursier resolve (prefer ~/.gradle/caches/modules-2)
            native kotlinc → ~/.kscriptx/cache/<hash>
            run
```

Typical timings — see **Benchmark results** below (regenerated by `./scripts/bench-compare.sh`).

Disable the daemon for a single script (including shebang):

```bash
#!/usr/bin/env -S kscriptx --no-daemon
```

Or `KSCRIPTX_DAEMON=0`. The launcher talks to the daemon via a small Rust helper
(`bin/kscriptx-dclient`): **Unix domain socket** on Linux/macOS (`$home/daemon/sock`),
TCP loopback fallback on Windows or if UDS bind fails.

The daemon **only compiles** (and keeps caches warm). The **script JVM is started by
the original client** (`kscriptx-dclient` `exec`s `java`, or the JVM CLI forks it).
Killing that client/script PID therefore kills the script process — not a child
hidden inside the daemon. Concurrent compiles are allowed (no single-flight run lock).

The daemon **server stays on the JVM** (hot classloaders, FastCache, Coursier).
Only the thin client is Rust. See “Daemon / Rust FFI notes” under Architecture
details if you are considering moving more of the server off-heap.

Scripts that call `exitProcess` / `System.exit` terminate **only their own script JVM**
(when started via the daemon path). Use `--no-daemon` if you need a fully isolated
one-shot CLI without a background compile daemon.

Each daemon request receives the client’s **current working directory and full environment**
(same as a new shell would). Compile/cache logic uses that overlay for `@Repository`
`{{VAR}}` expansion and FastCache invalidation when those vars change.

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
| `$home/daemon/` | Persistent CLI daemon (`sock` UDS and/or `port` TCP, `pid`) |
| `$home/fast-cache/` | mtime-based script fingerprint → content hash |
| `bin/kscriptx-dclient` | Rust local-socket client used by the launcher (built with `cargo`) |
| `KSCRIPTX_DAEMON_TRANSPORT` | Force `unix` or `tcp` for the daemon endpoint |

Optional config file (`kscript.properties`): `scripting.preamble`, `scripting.kotlin.opts`,
`scripting.repository.url`, etc.

### Daemon / Rust FFI notes

Keep the **daemon worker on the JVM**. The warm-path wins (in-memory FastCache,
`URLClassLoader` reuse, Coursier resolve state) are JVM objects. A Rust rewrite of the
server would still need a JVM (embed via JNI/`jni-rs`, or a Rust supervisor that only
owns lifecycle/IPC).

| Piece | Language today | Move to Rust + FFI? |
|---|---|---|
| Compile / FastCache / Coursier | JVM (daemon) | **No** — warm compile state lives here |
| Script JVM launch | Client (`dclient` exec / CLI fork) | Already outside the daemon |
| Annotation parse / resolve orchestration | JVM | **No** — talks to Coursier + Kotlin APIs |
| IPC accept loop + framing | JVM (+ Rust client) | Optional only; UDS already covers the important IPC win |
| Process spawn / idle / port-file supervisor | JVM `ProcessBuilder` | **Maybe** thin Rust sidecar later — not required |
| Hasher / zip helpers | JVM | Only if profiling shows a hotspot; FFI marshaling often eats the gain |

**Async I/O:** not used for the daemon protocol. Local CLI traffic is low-QPS; blocking
sockets + concurrent compile workers match the workload.

## Benchmarks

Compare classic **kscript** vs **kscriptx** across example scripts. Phases: **cold** (1st run),
**after small change** (2nd run / recompile), **warm** (cache hit, new JVM each run), and
**warm via daemon** (kscriptx only: thin client → already-running background JVM).

```bash
./gradlew :cli:build
# classic kscript needs KOTLIN_HOME (e.g. Kotlin 1.9.x) and often a JDK ≤21
KSCRIPT_JAVA_HOME="${KSCRIPT_JAVA_HOME:-$HOME/.sdkman/candidates/java/21.0.2-graalce}" \
  ./scripts/bench-compare.sh --kscript "$(command -v kscript)"
# all cases (incl. ffi / ktor classpath / coroutines / …):
./scripts/bench-compare.sh --cases all
# list catalog:
./scripts/bench-compare.sh --list-cases
# kscriptx-only:
./scripts/bench-compare.sh --skip-kscript --cases default --warm-runs 3
```

Outputs under `build/reports/perf-compare/` (`compare.json`, `compare.md`), plus the Cursor
canvas and the **Benchmark results** section below
(`scripts/update-readme-bench.py` / `scripts/gen-bench-canvas.py`).

Internal CI micro-bench (kscriptx-only): `./scripts/bench.sh`.

## Benchmark results

<!-- BENCH-COMPARE:START -->

_Generated `2026-07-25T07:48:33Z` · all times in **milliseconds (ms)** · warm columns = median of **5** samples; cold & after-change = single run._

| Phase | Meaning |
|---|---|
| **Cold (1st run)** | Empty tool cache — first resolve + compile + run (very cold). |
| **After small change** | Second run after a tiny script edit — must recompile; dependency jars may already be warm. |
| **Warm (new process)** | Unchanged script, cache hit. Each sample starts a **fresh JVM process**, then exits. |
| **Warm (via daemon)** | Same cache-hit script, but kscriptx talks to an **already-running background JVM** (started once; stays hot). No JVM startup per run. Classic kscript has no equivalent. |

Ratio columns show **how many times faster** the right-hand tool is (e.g. `4.0×` means about 4× faster). Formula is in the column header.

### Cold (1st run, empty cache) — ms

| Case | kscript (ms) | kscriptx (ms) | kscript ÷ kscriptx |
|---|---:|---:|---:|
| `hello-nodeps` | 2990 | 400 | 7.5× |
| `hello` | 3581 | 1118 | 3.2× |
| `include` | 3373 | 418 | 8.1× |
| `multi` | 3747 | 508 | 7.4× |
| `cpu` | 3266 | 466 | 7.0× |
| `chatty` | 3153 | 404 | 7.8× |
| `many-deps` | 3726 | 1257 | 3.0× |
| `coroutines` | 4268 | 1048 | 4.1× |
| `ktor-cp` | 2716 | 1229 | 2.2× |
| `large` | 4034 | 490 | 8.2× |
| `kt-file` | 2930 | 429 | 6.8× |

### After small change (2nd run, recompile) — ms

| Case | kscript (ms) | kscriptx (ms) | kscript ÷ kscriptx |
|---|---:|---:|---:|
| `hello-nodeps` | 2963 | 227 | 13.0× |
| `hello` | 3520 | 307 | 11.5× |
| `include` | 3132 | 265 | 11.8× |
| `multi` | 3954 | 323 | 12.2× |
| `cpu` | 3380 | 255 | 13.3× |
| `chatty` | 1891 | 230 | 8.2× |
| `many-deps` | 3625 | 384 | 9.4× |
| `coroutines` | 3694 | 283 | 13.0× |
| `ktor-cp` | 3672 | 317 | 11.6× |
| `large` | 4119 | 317 | 13.0× |
| `kt-file` | 1146 | 265 | 4.3× |

### Warm cache hit (unchanged script) — ms

| Case | kscript (ms) | kscriptx new process (ms) | kscriptx via daemon (ms) | kscript ÷ kscriptx | kscriptx ÷ daemon |
|---|---:|---:|---:|---:|---:|
| `hello-nodeps` | 384 | 90 | 7 | 4.3× | 12.0× |
| `hello` | 445 | 133 | 8 | 3.4× | 15.9× |
| `include` | 394 | 94 | 8 | 4.2× | 11.8× |
| `multi` | 431 | 111 | 8 | 3.9× | 13.7× |
| `cpu` | 387 | 95 | 8 | 4.1× | 12.5× |
| `chatty` | 403 | 93 | 8 | 4.3× | 11.2× |
| `many-deps` | 570 | 183 | 10 | 3.1× | 19.0× |
| `coroutines` | 450 | 121 | 9 | 3.7× | 12.9× |
| `ktor-cp` | 489 | 141 | 9 | 3.5× | 16.4× |
| `large` | 431 | 107 | 8 | 4.0× | 13.0× |
| `kt-file` | 420 | 99 | 8 | 4.2× | 12.0× |

Host: `Linux-6.18.33.2-microsoft-standard-WSL2-x86_64-with-glibc2.39` · `openjdk version "25.0.2" 2026-01-20`  
Tools: kscript `Version   : 4.2.3` · kscriptx `kscriptx 0.1.3` · native kotlinc=yes

- **kscriptx new process** — `kscriptx --no-daemon`: start JVM, run script, exit (every sample).  
- **kscriptx via daemon** — background `kscriptx` JVM already running; each sample is a client request over a local socket (default mode). Classic kscript has no daemon column.  
- **kscript ÷ kscriptx** — e.g. `4.0×` means kscriptx (new process) finished in ~¼ the time of kscript.  
- **kscriptx ÷ daemon** — e.g. `12×` means the daemon path was ~12× faster than starting a new kscriptx JVM for the same warm script.  
Re-run: `./scripts/bench-compare.sh`.

<!-- BENCH-COMPARE:END -->

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
