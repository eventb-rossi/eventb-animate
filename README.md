# Event-B Animate

A command-line tool for animating Event-B models using the ProB model checker.

## Features

- Random animation of Event-B models
- Invariant checking during animation
- Coverage analysis
- Trace saving and replay in JSON format
- Model visualization export (machine hierarchy, events, properties, invariants)
- Conversion of Event-B models to Classical B machines

## Requirements

- Java 21 or later

## Building

```bash
./gradlew build
```

The build produces a self-contained jar at `build/libs/eventb-animate-<version>.jar`.
The `eventb-animate` command used below stands for `java -jar` on that file (or a
wrapper script around it).

## Usage

### Basic Animation

```bash
eventb-animate path/to/model.bum
```

The model path may also be a `.zip` archive or a Rodin project directory; the
most refined machine is auto-selected unless `-m/--machine` says otherwise.

### Options

- `-s, --steps <n>` - Number of random animation steps (default: 5)
- `-z, --size <n>` - Default size for ProB sets (default: 4)
- `-i, --invariants` - Check invariants during animation
- `-m, --machine <name>` - Machine to animate (default: auto-select most refined)
- `--perf` - Print ProB performance information
- `--save <file.json>` - Save animation trace to JSON file
- `--debug` - Enable debug logging
- `-h, --help` - Show help (also available on every subcommand)
- `-V, --version` - Print the release version

### Exit Codes

`eventb-animate` exits non-zero on failure, so CI jobs fail automatically:

- `0` - success
- `1` - the model could not be loaded, the animation hit a deadlock (a state
  with no enabled events, including legitimate terminal states), an invariant
  was violated (with `-i/--invariants`), a trace replay was not perfect
  (`replay`), or a conversion or its post-check failed (`convert`)

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
- `-b, --bmodel <file>` - Dump prolog model to .eventb file
- `--force` - Overwrite existing output files

#### Convert to Classical B

```bash
eventb-animate convert output.mch path/to/model.bum
```

Translates the Event-B model into a Classical B machine (`.mch`). A `.eventb`
prolog package (as produced by `info --bmodel`) is also accepted as input.

Options:
- `--check <mode>` - Optional post-conversion validation (default: `none`):
  - `init` - load the converted machine with ProB and initialise it
  - `mc:N` - model-check the converted machine (deadlocks and invariants),
    exploring up to `N` states
- `--force` - Overwrite existing output files

## CI Integration

Use `eventb-animate` in your CI pipelines without building from source. The
job fails when the run exits non-zero (see [Exit Codes](#exit-codes)) — note
that an animation reaching a deadlocked or terminal state counts as a failure;
pick `steps` low enough for models that legitimately terminate.

### GitHub Actions

```yaml
- uses: eventb-rossi/eventb-animate@v5.0
  with:
    model-path: 'path/to/model.bum'
```

#### Inputs

| Input | Description | Required | Default |
|-------|-------------|----------|---------|
| `model-path` | Path to model `.bum`, `.zip`, or directory | Yes | — |
| `command` | Subcommand: `animate` or `replay` | No | `animate` |
| `steps` | Number of random animation steps (animate) | No | — |
| `size` | Default size for ProB sets (animate) | No | — |
| `invariants` | Check invariants during animation (animate) | No | `false` |
| `save` | Save animation trace to JSON file (animate) | No | — |
| `trace` | Path to JSON trace file (replay, required) | No | — |
| `args` | Extra args appended to the assembled command | No | — |
| `version` | Release version tag (e.g., `v5.0`) | No | `latest` |
| `java-version` | Java version to use (must be 21 or later) | No | `21` |

#### Examples

```yaml
# Check invariants with 20 steps
- uses: eventb-rossi/eventb-animate@v5.0
  with:
    model-path: 'path/to/model.bum'
    steps: 20
    invariants: true

# Replay a trace
- uses: eventb-rossi/eventb-animate@v5.0
  with:
    model-path: 'models/system.bum'
    command: 'replay'
    trace: 'tests/trace.json'

# Pin to a specific release
- uses: eventb-rossi/eventb-animate@v5.0
  with:
    model-path: 'path/to/model.bum'
    version: 'v5.0'
```

### GitLab CI

Include the reusable template and extend the `.eventb-animate` hidden job:

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/eventb-rossi/eventb-animate/v5.0/.gitlab-ci-template.yml'

animate-model:
  extends: .eventb-animate
  variables:
    EVENTB_ANIMATE_MODEL_PATH: 'path/to/model.bum'
```

#### Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `EVENTB_ANIMATE_MODEL_PATH` | Path to model `.bum`, `.zip`, or directory (required) | `''` |
| `EVENTB_ANIMATE_COMMAND` | Subcommand: `animate` or `replay` | `animate` |
| `EVENTB_ANIMATE_STEPS` | Number of random animation steps (animate) | `''` |
| `EVENTB_ANIMATE_SIZE` | Default size for ProB sets (animate) | `''` |
| `EVENTB_ANIMATE_INVARIANTS` | Check invariants during animation (animate) | `false` |
| `EVENTB_ANIMATE_SAVE` | Save animation trace to JSON file (animate) | `''` |
| `EVENTB_ANIMATE_TRACE` | Path to JSON trace file (replay, required) | `''` |
| `EVENTB_ANIMATE_ARGS` | Extra args appended to the assembled command | `''` |
| `EVENTB_ANIMATE_VERSION` | Release version tag (e.g., `v5.0`) | `latest` |

#### Examples

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/eventb-rossi/eventb-animate/v5.0/.gitlab-ci-template.yml'

# Check invariants with 20 steps
animate-check:
  extends: .eventb-animate
  variables:
    EVENTB_ANIMATE_MODEL_PATH: 'path/to/model.bum'
    EVENTB_ANIMATE_STEPS: '20'
    EVENTB_ANIMATE_INVARIANTS: 'true'

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
    EVENTB_ANIMATE_VERSION: 'v5.0'
```

## License

See LICENSE file for details.
