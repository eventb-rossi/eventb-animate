# Event-B Animate

A command-line tool for model-checking Event-B models using ProB.

## Features

- Exhaustive bounded model-checking of Event-B models (deadlocks and invariants),
  working at any refinement level
- Symbolic invariant model-checking (BMC, IC3, k-induction, t-induction) that can
  prove safety on state spaces too large to enumerate
- Coverage analysis
- Counterexample trace saving and replay in JSON format
- Model visualization export (machine hierarchy, events, properties, invariants)
- Conversion of Event-B models to Classical B machines

## Installation

Install the `eventb-animate` command from a package manager (these need a Java 21+ runtime):

```sh
# Homebrew (macOS / Linux)
brew tap eventb-rossi/tap
brew install eventb-animate

# Scoop (Windows)
scoop bucket add eventb https://github.com/eventb-rossi/scoop-eventb
scoop install eventb/eventb-animate

# APT (Ubuntu / Debian)
curl -fsSL https://eventb-rossi.github.io/apt/KEY.gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/eventb.gpg
. /etc/os-release
echo "deb [signed-by=/etc/apt/keyrings/eventb.gpg] https://eventb-rossi.github.io/apt ${VERSION_CODENAME} main" \
  | sudo tee /etc/apt/sources.list.d/eventb.list
sudo apt update
sudo apt install eventb-animate

# Copr (Fedora / RHEL)
sudo dnf copr enable @eventb-rossi/eventb-copr
sudo dnf install eventb-animate

# Gentoo
eselect repository enable eventb-rossi
emaint sync -r eventb-rossi
emerge sci-mathematics/eventb-animate
```

For Windows machines without a JVM, each [release](https://github.com/eventb-rossi/eventb-animate/releases) also ships self-contained `x64` artifacts that bundle their own Java runtime: the `.msi` installer adds an *Event-B Animate* Start Menu group (but not `PATH`), and the `.zip` is portable (unzip and run `eventb-animate.exe`; add its folder to `PATH` to call it from anywhere).

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

To check invariant safety symbolically instead of by enumerating states, pass
`--symbolic` with an algorithm — IC3 and k-induction can *prove* safety on state
spaces too large to explore exhaustively:

```bash
eventb-animate --symbolic ic3 path/to/model.bum
```

This reports only a verdict (no counterexample trace); see the `--symbolic`
option below for the trade-offs.

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
- `--assertions` - Also check assertions (theorems) for violations
- `--no-deadlock` - Do not check for deadlocks
- `--no-invariant` - Do not check for invariant violations (disabling every
  check is rejected)
- `--goal <predicate>` - Also search for a reachable state satisfying the
  Event-B predicate (ASCII or Unicode operators); a hit is reported as a
  violation (exit 1) with the trace to the state. Combine with `--no-deadlock
  --no-invariant` for a pure reachability search
- `--ltl <formula>` - Check an LTL formula instead of running the consistency
  check (ProB LTL syntax; wrap Event-B predicates in `{...}`, e.g.
  `G not({cars_go = TRUE & peds_go = TRUE})`). A counterexample exits 1 and
  composes with `--save`. Unlike the consistency check, a run stopped by the
  `--states` limit proves nothing about temporal properties and exits 2. Only
  `--states` bounds an LTL run; the consistency-check flags (`--goal`,
  `--assertions`, `--no-deadlock`, `--no-invariant`, `--time-limit`,
  `--stop-at-full-coverage`, `--search-strategy`) are rejected
- `--ltl-file <file.ltl>` - Read the LTL formula to check from a file
- `--symbolic <bmc|ic3|kinduction|tinduction>` - Verify invariant safety with
  ProB's symbolic model checker (BMC, IC3, k-induction, t-induction) instead of
  the explicit consistency check. It reasons over the transition relation with a
  SAT/SMT backend, so IC3 and k-induction can *prove* safety on state spaces too
  large to enumerate. It reports only a verdict: a reachable violation exits 1
  (with no trace — rerun the default check to save one), a proven-safe machine
  exits 0, and an inconclusive run exits 2. The induction modes prove safety
  where BMC only finds bugs, but any mode may be inconclusive (exit 2) on Event-B
  set-theoretic invariants when the solver is too weak. Because it produces no
  trace and no incremental progress and checks invariants only with no bound,
  `--save`, `--progress`, `--ltl` and the consistency-check flags (`--goal`,
  `--assertions`, `--no-deadlock`, `--no-invariant`, `--states`, `--time-limit`,
  `--stop-at-full-coverage`, `--search-strategy`) are rejected
- `--search-strategy <mixed|bf|df>` - State-space exploration order: mixed
  breadth/depth (default), breadth-first, or depth-first (`df` can reach deep
  violations sooner)
- `--progress` - Print model-checking progress (states processed/found,
  transitions) to stderr about once per second; useful to keep long CI runs
  from looking stuck. ProB reports no intermediate progress for LTL checks,
  so with `--ltl` only a final line is printed
- `-m, --machine [<project>/]<name>` - Machine to model-check (default:
  auto-select most refined); add a `<project>/` prefix to pick a machine in a
  specific project of a multi-project archive
- `--perf` - Print ProB performance information
- `--save <file.json>` - Save the counterexample trace to a JSON file when a
  violation is found
- `--json <file|->` - Write a machine-readable JSON report of the run (works
  with every command; see [Machine-Readable Reports](#machine-readable-reports)).
  `-` writes the report to stdout and moves all other output to stderr, so
  `eventb-animate --json - model.bum | jq .status` just works
- `--junit <report.xml>` - Write a JUnit XML report, one testcase per checked
  property, for CI systems that ingest the format natively (see
  [Machine-Readable Reports](#machine-readable-reports))
- `--markdown <report.md>`, `--md` - Write a human-readable Markdown report of
  the run (counterexample trace, violating state, violated invariants), for a
  person to read in a merge-request view or as a browsable CI artifact (see
  [Machine-Readable Reports](#machine-readable-reports))
- `--debug` - Enable debug logging
- `-h, --help` - Show help (also available on every subcommand)
- `-V, --version` - Print the release version

### Exit Codes

`eventb-animate` exits non-zero on failure, so CI jobs fail automatically:

- `0` - success: the requested check found nothing (the full state space was
  explored, or a `--states`/`--time-limit`/`--stop-at-full-coverage` bound was
  reached without a violation), the LTL formula holds, a `--symbolic` run proved
  invariant safety, all proof obligations are discharged (`wd`, `po`), a
  replay was perfect (`replay`), or a trace was adapted to the target refinement
  level (`replay --refine`)
- `1` - a definite negative verdict or an input failure: the model could not
  be loaded, an invariant or assertion was violated, a deadlock was reached (a
  state with no enabled events, including legitimate terminal states), a
  `--goal` state was found, an LTL counterexample was found, a `--symbolic` run
  found a reachable invariant violation, a proof obligation was disproved
  (`po --disprove`), a trace replay was not perfect (`replay`), no adaptation of
  a trace to the target refinement level was found (`replay --refine`), or a
  conversion failed (`convert`)
- `2` - no verdict: nothing was proven either way. The check could not
  complete (interrupted or a ProB error), a bounded LTL run hit the
  `--states` limit, a `--symbolic` run was inconclusive (the solver/provers were
  too weak, or a bound was reached), a proof obligation remains undischarged
  (`wd`, `po` -- open means unproven, not disproven), or the command line was
  invalid (usage errors, including unparseable `--goal`/`--ltl` formulas)

If a requested `--json`/`--junit`/`--markdown` report cannot be written, an
otherwise clean run exits 1: the report is the artifact CI asked for, so its
absence must fail the job.

### Machine-Readable Reports

Every command accepts `--json <file|->` and writes one JSON document
describing the run at the end: what ran (`command`, `model`, `machine`,
`probVersion`), what happened (`status` of `ok`/`violation`/`incomplete`/
`error`, `exitCode`, `message`, one `checks` entry per checked property), and
the evidence (a `counterexample` summary with the transition list, violating
state, and violated invariants; the `--save` trace path as `traceFile`). The
document carries `formatVersion: 1`; the key set only changes with a version
bump. Report files are overwritten on every run (they are per-run telemetry,
so no `--force` is needed), and usage errors write no report at all -- the run
never started.

```bash
eventb-animate --json report.json --save trace.json model.bum
eventb-animate --json - model.bum 2>/dev/null | jq .status
```

With `--json -` stdout carries exactly the report; everything the run would
normally print moves to stderr.

`--junit <report.xml>` writes the same findings as JUnit XML for CI systems
that render the format natively (GitLab `artifacts:reports:junit`, the GitHub
JUnit actions, Jenkins). Each checked property is one `<testcase>` named after
the check (`invariant`, `deadlock`, `assertions`, `goal`, `ltl`,
`well-definedness`, `replay`, `convert`, the qualified obligation names for
`po`, `invariant/<event>`/`feasibility`/`redundant-invariants` for `cbc`,
...) with the machine as its classname. A violation marks the fired
property `<failure>` -- with the counterexample as the failure body -- and the
other properties `<skipped>`, since the search stops at the first violation
and proves nothing about them; a run without a verdict (exit 2) marks the
properties as `<error>`. The `wd` command reports one aggregate testcase (ProB
returns only discharged/total counts, not per-obligation names), while `po`
reports one testcase per obligation, open ones as `<failure>`. Both report
options combine freely (but not to the same file), and only `--json` can
write to stdout.

`--markdown <report.md>` writes the same findings as a human-readable Markdown
document: a title with the verdict, a metadata list, a checks table, and, when a
violation is found, the counterexample trace, violating state, and violated
invariants. Every value ProB produces is rendered as inline code or a fenced
block (with the fence widened past any backtick run), so an identifier or state
dump can never break the layout. It is meant for a person to read in a
merge-request view or as a browsable CI artifact -- keep gating on
`--json`/`--junit`, whose shapes are stable, rather than parsing the Markdown.
Like the other report options it overwrites without `--force`, must not share a
file with them, and does not support `-`.

### Commands

#### Replay a Trace

```bash
eventb-animate replay -t path/to/trace.json path/to/model.bum
```

Replays the trace against the selected machine (the most refined by default, or
`-m/--machine`) and reports the replay status; a perfect replay exits 0.

With `--refine` the trace is instead *adapted* to the `-m` refinement level
before being reproduced -- migrating a trace saved from an abstract machine onto
a concrete one, renaming events and inserting skip-refining steps as the
refinement requires:

```bash
eventb-animate replay --refine -m M2 -t trace_M1.json model.zip --save trace_M2.json
```

- `--refine` - adapt the source trace to the target refinement level instead of
  replaying it verbatim. The target is the `-m` machine; the source may be from
  any more abstract level (multi-level jumps like M0->M3 need only the source
  trace and the target model). Exit 0 means an adaptation was found; exit 1 means
  the target could not reproduce the trace within the search bounds -- a useful
  regression signal
- `--save <trace.json>` - write the adapted trace (requires `--refine`; overwrites)
- `--refine-breadth <N>` - max alternatives explored per step (default 10)
- `--refine-depth <N>` - max steps the search descends between two matched
  transitions (default 5)

A non-zero exit reported as "No adaptation found within the configured search
bounds" means exactly that: the concrete machine could not reproduce the abstract
trace with these bounds. Raising `--refine-breadth`/`--refine-depth` may help;
witness-heavy data refinements whose concrete parameters cannot be inferred from
the abstract trace may have no adaptation at any bound.

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
- `--prefs` - List all ProB preferences with their current values, defaults,
  and descriptions (the names accepted by `-p/--pref`)

#### Check Well-Definedness

```bash
eventb-animate wd path/to/model.bum
```

Checks the model's well-definedness proof obligations with ProB's WD prover
and prints `X discharged / Y total`. Exits 2 when any obligation remains
undischarged (a possible WD problem, such as a partial-function application
outside its domain or a division by zero -- unproven, though not shown wrong).

#### Check Proof Obligations

```bash
eventb-animate po path/to/model.bum
eventb-animate po --allow-reviewed --filter 'M1/*' path/to/model.zip
```

Gates on the proof status recorded by Rodin: reads the `.bpo`/`.bps` proof
files next to the model (ProB is not started) and reports every proof
obligation of the refinement chain -- all machines and contexts -- under its
qualified name `<component>/<obligation>`. The run passes only when every
obligation is discharged; open obligations exit 2 (unproven, not disproven).
An obligation Rodin flagged as _broken_ -- its stored proof is stale because
the model changed after it was proved -- counts as open whatever confidence it
records, so a stale export cannot pass the gate until the proofs are replayed
in Rodin. A model exported without its proof files is rejected, so an empty
proof database cannot pass as "nothing to prove". Unlike `wd`, which asks ProB
to prove well-definedness from scratch, `po` reports what the Rodin provers
already established.

Options:
- `--allow-reviewed` - Accept obligations marked as reviewed in Rodin
  (manually inspected but not proven)
- `--filter <glob>` - Check only obligations whose qualified name matches
  (repeatable; `*` also matches across `/`, so `M1/*` selects one component
  and `*/INV` all invariant-preservation obligations). A filter matching
  nothing fails the gate
- `-v, --verbose` - List every obligation with its status, not only the
  failing ones
- `--disprove` - Run ProB's constraint solver on each open obligation,
  looking for a counterexample to its sequent (this starts ProB). A found
  counterexample is a definite failure (exit 1); an obligation the solver
  proves passes the gate with a note; a timeout or solver failure keeps it
  open (exit 2)
- `--disprove-timeout <ms>` - Per-obligation solver time limit for
  `--disprove` (default: 1000)

#### Constraint-Based Invariant Check

```bash
eventb-animate cbc path/to/model.bum
eventb-animate cbc --events inc,reset --save trace.json path/to/model.zip
```

Proves per-event invariant preservation with ProB's constraint solver,
without exploring the state space: for each event it searches for an
invariant-satisfying state -- reachable or not -- from which one step
violates the invariant. A hit is a violation (exit 1) with a two-step
counterexample trace; no hit is a preservation proof for every checked
event, with two caveats printed alongside: initialisation is not checked
(the [model checker](#model-checking) covers it), and a solver timeout can
mask a violation. The check ignores the Rodin proof status shipped with the
model (`PROOF_INFO=false`), so it proves preservation from scratch rather
than trusting what `po` gates on.

Options:
- `--events <e1,e2,...>` - Restrict the check to these events
  (comma-separated or repeated; default: every event of the machine)
- `--deadlock` - Also search for a deadlocking state that satisfies the
  invariant. The state need not be reachable, so a hit warns that it may
  never occur in a run -- but proves the guards do not cover the invariant
- `--where <predicate>` - Restrict the `--deadlock` search to states also
  satisfying this Event-B predicate
- `--no-invariant` - Skip the invariant preservation check (e.g. to search
  only for deadlocks)
- `--feasibility` - Also report events whose guard can never be satisfied
  under the invariant (dead events)
- `--redundant-invariants` - Also report invariants implied by the
  remaining ones
- `--strict` - Turn the advisory `--feasibility`/`--redundant-invariants`
  findings into failures (exit 1); by default they are printed but keep
  exit 0
- `--save <trace.json>` - Save the counterexample trace. The trace starts
  in the found state, which need not be reachable, so it may not `replay`
  against the model

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
| `command` | Subcommand: `check` (model-check), `replay`, `wd`, `po`, or `cbc` | No | `check` |
| `size` | Default size for ProB sets (check, wd, cbc, po --disprove) | No | — |
| `states` | Bound model-checking to N states; omit for exhaustive (check) | No | — |
| `save` | Save the counterexample trace to JSON when a violation is found (check, cbc) | No | — |
| `json-report` | Write a machine-readable JSON report of the run to this path | No | — |
| `junit-report` | Write a JUnit XML report to this path (one testcase per checked property) | No | — |
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

# Gate on the Rodin proof status (extra flags go through args)
- uses: eventb-rossi/eventb-animate@v5.1
  with:
    model-path: 'models/system.zip'
    command: 'po'
    args: '--allow-reviewed'

# Pin to a specific release
- uses: eventb-rossi/eventb-animate@v5.1
  with:
    model-path: 'path/to/model.bum'
    version: 'v5.1'

# Publish the verdict as a test report rendered on the PR
- uses: eventb-rossi/eventb-animate@v5.1
  with:
    model-path: 'path/to/model.bum'
    junit-report: 'report.xml'
- uses: dorny/test-reporter@v1
  if: ${{ !cancelled() }}
  with:
    name: 'Model check'
    path: 'report.xml'
    reporter: 'java-junit'
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
| `EVENTB_ANIMATE_COMMAND` | Subcommand: `check` (model-check), `replay`, `wd`, `po`, or `cbc` | `check` |
| `EVENTB_ANIMATE_SIZE` | Default size for ProB sets (check, wd, cbc, po --disprove) | `''` |
| `EVENTB_ANIMATE_STATES` | Bound model-checking to N states; omit for exhaustive (check) | `''` |
| `EVENTB_ANIMATE_SAVE` | Save the counterexample trace to JSON when a violation is found (check, cbc) | `''` |
| `EVENTB_ANIMATE_JSON` | Write a machine-readable JSON report of the run to this path | `''` |
| `EVENTB_ANIMATE_JUNIT` | Write a JUnit XML report to this path (one testcase per checked property) | `''` |
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

# Surface the verdict in the merge-request widget: which property failed,
# with the counterexample attached, straight from the JUnit report
model-check-report:
  extends: .eventb-animate
  variables:
    EVENTB_ANIMATE_MODEL_PATH: 'path/to/model.bum'
    EVENTB_ANIMATE_JUNIT: 'report.xml'
  artifacts:
    when: always
    reports:
      junit: report.xml
```
