# Event-B Animate

A command-line tool for model-checking Event-B models using ProB.

## Features

- Exhaustive bounded model-checking of Event-B models (deadlocks and invariants),
  working at any refinement level
- Coverage analysis
- Counterexample trace saving and replay in JSON format
- Model visualization export (machine hierarchy, events, properties, invariants)
- Conversion of Event-B models to Classical B machines

## Requirements

- Java 21 or later

## Installation

### Homebrew (macOS / Linux)

```bash
brew tap eventb-rossi/tap
brew install eventb-animate
```

### Scoop (Windows)

```powershell
scoop bucket add eventb https://github.com/eventb-rossi/scoop-eventb
scoop install eventb/eventb-animate
```

### Windows (standalone, no Java needed)

These bundle their own Java runtime, so the *Java 21 or later* requirement above
does **not** apply to them. Download from the latest release:

- **Installer** —
  [`eventb-animate.msi`](https://github.com/eventb-rossi/eventb-animate/releases/latest/download/eventb-animate.msi):
  double-click to install (Program Files + an *Event-B Animate* Start Menu group).
  The installer does **not** add the tool to `PATH` — for that, use Scoop above or
  the portable ZIP below.
- **Portable** —
  [`eventb-animate-win-x64.zip`](https://github.com/eventb-rossi/eventb-animate/releases/latest/download/eventb-animate-win-x64.zip):
  extract anywhere and run `eventb-animate.exe`; add its folder to `PATH` to call
  `eventb-animate` from any directory.

### APT (Ubuntu / Debian)

```bash
curl -fsSL https://eventb-rossi.github.io/apt/KEY.gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/eventb.gpg
. /etc/os-release
echo "deb [signed-by=/etc/apt/keyrings/eventb.gpg] https://eventb-rossi.github.io/apt ${VERSION_CODENAME} main" \
  | sudo tee /etc/apt/sources.list.d/eventb.list
sudo apt update
sudo apt install eventb-animate
```

### Copr (Fedora / RHEL)

```bash
sudo dnf copr enable @eventb-rossi/eventb-copr
sudo dnf install eventb-animate
```

### Gentoo

```bash
eselect repository enable eventb-rossi
emaint sync -r eventb-rossi
emerge sci-mathematics/eventb-animate
```

## Building

```bash
./gradlew build
```

The build produces a self-contained jar at `build/libs/eventb-animate-<version>.jar`.
The `eventb-animate` command used below stands for `java -jar` on that file (or a
wrapper script around it).

## Usage

### Model Checking

```bash
eventb-animate path/to/model.bum
```

This exhaustively model-checks the machine for invariant violations and
deadlocks, exploring the full state space for the current set sizes (`-z`). Use
`--states N` to stop after `N` states instead. The check works at every
refinement level: ProB loads the native Event-B state space, so variables that
were data-refined away in later machines are still initialised from their
witnesses/gluing.

The model path may also be a `.zip` archive or a Rodin project directory; the
most refined machine is auto-selected unless `-m/--machine` says otherwise.

A Rodin archive exported via Eclipse's *Archive File* wizard may bundle several
projects, each under its own top-level directory. When the archive holds more
than one project, name the machine you want with its project prefix, for example
`-m MyProject/M2`. Use `-m MyProject/` (project prefix only) to auto-select the
most refined machine within that project. A bare `-m M2` still works when the
machine name is unique across all projects.

### Options

- `-z, --size <n>` - Default size for ProB sets (default: 4)
- `-p, --pref <KEY=VALUE>` - Set a ProB preference (repeatable). User values
  override the built-in defaults, including `DEFAULT_SETSIZE` from `-z`; for
  example `-p SYMMETRY_MODE=off` (symmetry modes: `off`, `flood`, `nauty`,
  `hash`; default: `hash`)
- `--states <N>` - Bound model-checking to at most `N` explored states (default:
  exhaustive)
- `--time-limit <seconds>` - Bound model-checking to the given wall-clock time
  (default: unlimited). Like `--states`, a run stopped by the limit is reported
  as not exhaustive
- `--stop-at-full-coverage` - Stop model-checking once every event has been
  covered (a bounded check, reported as not exhaustive)
- `-m, --machine [<project>/]<name>` - Machine to model-check (default:
  auto-select most refined); add a `<project>/` prefix to pick a machine in a
  specific project of a multi-project archive
- `--perf` - Print ProB performance information
- `--save <file.json>` - Save the counterexample trace to a JSON file when a
  violation is found
- `--debug` - Enable debug logging
- `-h, --help` - Show help (also available on every subcommand)
- `-V, --version` - Print the release version

### Exit Codes

`eventb-animate` exits non-zero on failure, so CI jobs fail automatically:

- `0` - success: no invariant violation or deadlock was found (either the full
  state space was explored, or the `--states` limit was reached without a
  violation)
- `1` - the model could not be loaded, an invariant was violated, a deadlock was
  reached (a state with no enabled events, including legitimate terminal states),
  a trace replay was not perfect (`replay`), or a conversion failed (`convert`)
- `2` - model-checking could not complete (for example it was interrupted), so
  nothing was proven -- distinct from a real violation

### Commands

#### Replay a Trace

```bash
eventb-animate replay -t path/to/trace.json path/to/model.bum
```

#### Model Information

```bash
eventb-animate info path/to/model.bum
```

Export options:
- `--machine-graph <file>` - Save machine hierarchy graph (.dot or .svg)
- `--event-graph <file>` - Save events hierarchy graph (.dot or .svg)
- `--property-graph <file>` - Save properties graph (.dot or .svg)
- `--invariant-graph <file>` - Save invariant graph (.dot or .svg)
- `--force` - Overwrite existing output files

#### Convert to Classical B

```bash
eventb-animate convert output.mch path/to/model.bum
```

Translates the Event-B model into a Classical B machine (`.mch`). A ProB Event-B
`.eventb` prolog package is also accepted as input.

To model-check a model, run `eventb-animate <model>` directly (see [Model
Checking](#model-checking)); that checks the native Event-B state space and works
at every refinement level, unlike checking a flattened `.mch`.

Options:
- `--force` - Overwrite existing output files

## CI Integration

Use `eventb-animate` in your CI pipelines without building from source. The
job fails when the run exits non-zero (see [Exit Codes](#exit-codes)) — note
that model-checking reports a deadlock (a state with no enabled events, including
a legitimate terminal state) as a failure.

### GitHub Actions

```yaml
- uses: eventb-rossi/eventb-animate@v5.1
  with:
    model-path: 'path/to/model.bum'
```

#### Inputs

| Input | Description | Required | Default |
|-------|-------------|----------|---------|
| `model-path` | Path to model `.bum`, `.zip`, or directory | Yes | — |
| `command` | Subcommand: `check` (model-check) or `replay` | No | `check` |
| `size` | Default size for ProB sets (check) | No | — |
| `states` | Bound model-checking to N states; omit for exhaustive (check) | No | — |
| `save` | Save the counterexample trace to JSON when a violation is found (check) | No | — |
| `trace` | Path to JSON trace file (replay, required) | No | — |
| `args` | Extra args appended to the assembled command | No | — |
| `version` | Release version tag (e.g., `v5.1`) | No | `latest` |
| `java-version` | Java version to use (must be 21 or later) | No | `21` |

#### Examples

```yaml
# Model-check, bounded to 50000 states
- uses: eventb-rossi/eventb-animate@v5.1
  with:
    model-path: 'path/to/model.bum'
    states: 50000

# Replay a trace
- uses: eventb-rossi/eventb-animate@v5.1
  with:
    model-path: 'models/system.bum'
    command: 'replay'
    trace: 'tests/trace.json'

# Pin to a specific release
- uses: eventb-rossi/eventb-animate@v5.1
  with:
    model-path: 'path/to/model.bum'
    version: 'v5.1'
```

### GitLab CI

Include the reusable template and extend the `.eventb-animate` hidden job:

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/eventb-rossi/eventb-animate/v5.1/.gitlab-ci-template.yml'

animate-model:
  extends: .eventb-animate
  variables:
    EVENTB_ANIMATE_MODEL_PATH: 'path/to/model.bum'
```

#### Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `EVENTB_ANIMATE_MODEL_PATH` | Path to model `.bum`, `.zip`, or directory (required) | `''` |
| `EVENTB_ANIMATE_COMMAND` | Subcommand: `check` (model-check) or `replay` | `check` |
| `EVENTB_ANIMATE_SIZE` | Default size for ProB sets (check) | `''` |
| `EVENTB_ANIMATE_STATES` | Bound model-checking to N states; omit for exhaustive (check) | `''` |
| `EVENTB_ANIMATE_SAVE` | Save the counterexample trace to JSON when a violation is found (check) | `''` |
| `EVENTB_ANIMATE_TRACE` | Path to JSON trace file (replay, required) | `''` |
| `EVENTB_ANIMATE_ARGS` | Extra args appended to the assembled command | `''` |
| `EVENTB_ANIMATE_VERSION` | Release version tag (e.g., `v5.1`) | `latest` |

#### Examples

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/eventb-rossi/eventb-animate/v5.1/.gitlab-ci-template.yml'

# Model-check, bounded to 50000 states
model-check:
  extends: .eventb-animate
  variables:
    EVENTB_ANIMATE_MODEL_PATH: 'path/to/model.bum'
    EVENTB_ANIMATE_STATES: '50000'

# Replay a trace
animate-replay:
  extends: .eventb-animate
  variables:
    EVENTB_ANIMATE_MODEL_PATH: 'models/system.bum'
    EVENTB_ANIMATE_COMMAND: 'replay'
    EVENTB_ANIMATE_TRACE: 'tests/trace.json'

# Pin to a specific release
animate-pinned:
  extends: .eventb-animate
  variables:
    EVENTB_ANIMATE_MODEL_PATH: 'path/to/model.bum'
    EVENTB_ANIMATE_VERSION: 'v5.1'
```

## License

See LICENSE file for details.
