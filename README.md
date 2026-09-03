# Event-B Animate

A command-line tool for model-checking Event-B models using ProB.

## Features

- Exhaustive bounded model-checking of Event-B models (deadlocks and invariants),
  working at any refinement level
- Optional LTSmin sequential and symbolic backends, with partial-order reduction
  for sequential exploration
- Symbolic invariant model-checking (BMC, IC3, k-induction, t-induction) that can
  prove safety on state spaces too large to enumerate
- Coverage analysis
- Constraint-based test generation (operation coverage), saved as replayable traces
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

The LTSmin backends are optional and unavailable on Windows. Install
[LTSmin](https://ltsmin.utwente.nl/) separately and make `prob2lts-seq`,
`prob2lts-sym`, and `ltsmin-printtrace` available on `PATH`. With the Copr
repository above enabled, Fedora/RHEL users can run `sudo dnf install ltsmin`.
For a custom installation directory, pass `-p LTSMIN=/absolute/path`.

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

To delegate the state-space exploration to LTSmin, select its sequential or
symbolic backend:

```bash
eventb-animate --backend ltsmin-sequential --ltsmin-por path/to/model.bum
eventb-animate --backend ltsmin-symbolic --no-deadlock path/to/model.bum
```

The normal invariant-and-deadlock contract is preserved: because ProB's LTSmin
API accepts one of those properties at a time, the default LTSmin run performs
two full state-space passes in that order and stops at the first violation. The
sequential backend replays counterexamples through ProB, so `--save` and
`--eval` work normally. It disables ProB hash symmetry by default because
symmetry-reduced deferred-set values can prevent ProB from replaying an LTSmin
trace. An explicit `-p SYMMETRY_MODE=...` still overrides this safety default.
The symbolic backend reports a definite verdict but no replayable
counterexample; rerun a failure with `ltsmin-sequential` to obtain the trace.

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
  `hash`; default: `off` for `ltsmin-sequential`, `hash` otherwise). Explicitly
  enabling symmetry for sequential LTSmin may make counterexample replay fail
- `--backend <prob|ltsmin-sequential|ltsmin-symbolic>` - Select the
  model-checking backend (default: `prob`). LTSmin supports invariant and
  deadlock checks, `-z`, `--time-limit`, the report options, and sequential
  counterexample saving/evaluation. It does not support the ProB-only check
  modes and controls: `--assertions`, `--goal`, `--ltl`, `--symbolic`,
  `--states`, `--stop-at-full-coverage`, `--search-strategy`, or `--progress`.
  If the external commands are not on `PATH`, set their directory with
  `-p LTSMIN=/absolute/path`. LTSmin explores out of process, so ProB's event
  coverage summary is unavailable for these runs
- `--ltsmin-por` - Enable LTSmin partial-order reduction (requires
  `--backend ltsmin-sequential`; LTSmin's symbolic checker is incompatible with
  POR). Successful runs report a complete reduced exploration rather than
  claiming that every unreduced interleaving was visited
- `--states <N>` - Bound model-checking to at most `N` explored states (default:
  exhaustive)
- `--time-limit <seconds>` - Bound model-checking to the given wall-clock time
  (default: unlimited for ProB and 600 seconds for LTSmin). Like `--states`, a
  run stopped by the limit is reported as not exhaustive. The LTSmin limit is
  shared by both external passes and cleans up their complete process tree
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
  violation is found (unsupported by `ltsmin-symbolic`, whose verdict has no
  replayable trace)
- `--eval <formula>` - Also evaluate an Event-B expression or predicate (ASCII or
  Unicode operators) in the counterexample state and report its value next to the
  trace and in the `--json`/`--markdown` reports (repeatable). This answers "the
  goal was hit — what were the other variables?"; a predicate reports its
  `TRUE`/`FALSE` verdict. It prints nothing on a clean run (there is no
  counterexample to evaluate in — to evaluate without one, run a check that hits a
  goal, or evaluate over the state space directly with the
  [`eval`](#evaluate-formulas) subcommand). A
  formula that cannot be evaluated in the state is shown as an error but never
  changes the check's own verdict. Rejected with `--symbolic`, which reports no
  state, and with `--backend ltsmin-symbolic` for the same reason
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
  invariant safety, all requested LTSmin passes completed cleanly, all proof
  obligations are discharged (`wd`, `po`), a
  replay was perfect (`replay`), a trace was adapted to the target refinement
  level (`replay --refine`), an operation-coverage run completed (`testgen`), or
  every requested formula was evaluated (`eval`, including a `--where` query that
  matched no state)
- `1` - a definite negative verdict or an input failure: the model could not
  be loaded, an invariant or assertion was violated, a deadlock was reached (a
  state with no enabled events, including legitimate terminal states), a
  `--goal` state was found, an LTL counterexample was found, a `--symbolic` run
  or LTSmin backend found a reachable violation, a proof obligation was disproved
  (`po --disprove`), a trace replay was not perfect (`replay`), no adaptation of
  a trace to the target refinement level was found (`replay --refine`), a dead
  target operation was found under `testgen --fail-on-infeasible`, or a
  conversion failed (`convert`)
- `2` - no verdict: nothing was proven either way. The check could not
  complete (interrupted or a ProB error), a bounded LTL run hit the
  `--states` limit, a `--symbolic` run was inconclusive (the solver/provers were
  too weak, or a bound was reached), the selected LTSmin tools were unavailable
  or an LTSmin pass did not complete, a proof obligation remains undischarged
  (`wd`, `po` -- open means unproven, not disproven), a target operation was
  left uncovered under `testgen --fail-on-uncovered`, an `eval` formula could not
  be evaluated (a query with no answer), or the command line was invalid (usage
  errors, including unparseable `--goal`/`--ltl`/`--eval` formulas)

If a requested `--json`/`--junit`/`--markdown` report cannot be written, an
otherwise clean run exits 1: the report is the artifact CI asked for, so its
absence must fail the job.

### Machine-Readable Reports

Every command accepts `--json <file|->` and writes one JSON document
describing the run at the end: what ran (`command`, `model`, `machine`,
`probVersion`), what happened (`status` of `ok`/`violation`/`incomplete`/
`error`, `exitCode`, `message`, one `checks` entry per checked property), and
the evidence (a `counterexample` summary with the transition list, violating
state, and violated invariants; the `--save` trace path as `traceFile`; and,
when `--eval` or the `eval` subcommand was used, an `evaluations` array of
per-state `{formula, value}` blocks).

Format version 3 adds `completion` to every top-level `check` report. Its
`classification` is `complete`, `counterexample`, `incomplete`, or `error`;
its `phase` is the terminal phase in which execution stopped (`load`,
`constant_setup`, `initialization`, or `search`), and its stable `reason`
explains the stop precisely. Reaching a later phase means every earlier phase
completed successfully. The allowed combinations are:

- `load`: `input_failure`
- `constant_setup` or `initialization`: `infeasible`, `evaluation_error`,
  `engine_failure`, or `interrupted`
- `search`: `exhaustive`, `proof`, `property_violation`, `goal_reached`,
  `state_limit`, `time_limit`, `coverage_limit`, `partial`, `evaluation_error`,
  `engine_failure`, or `interrupted`

The classification remains a coarse normalization: `exhaustive` and `proof`
are complete; property violations and reached goals are counterexamples;
limits, partial runs, and interruptions are incomplete; infeasibility and
input, evaluation, or engine failures are errors.

Definite findings also carry a top-level `finding` with a stable `category`
and the failed `check` identity. Invariant, assertion, deadlock, goal, state
evaluation, well-definedness, and LTL findings have distinct categories;
future unrecognized checker results use the conservative `unknown` category.
The finding identity is deliberately separate from `completion.reason`.

This is deliberately separate from the existing run `status` and exit code. A
built-in check stopped by `--states`, `--time-limit`, or
`--stop-at-full-coverage` still has `status: "ok"` and exits 0 when it found no
violation, while `completion.classification: "incomplete"` records that the
search was not exhaustive.

Built-in consistency checks also emit final `searchStatistics` with
`statesDiscovered`, `statesProcessed`, and `transitions`. They exclude states
processed and transitions discovered during constant-setup and initialization
preflight; unprocessed states placed on the initial search frontier remain
included as discovered. Consequently, a run stopped by `--states N` reports
exactly `N` processed states; a run that finds a result sooner reports fewer.
The counters are present whether or not `--progress` is enabled and are omitted
when search never starts or no final counters are available. ProB's LTL and
symbolic APIs and the external LTSmin backends do not expose compatible final
counters, so those modes emit `completion` but omit `searchStatistics`.
Non-check commands omit `completion`, `finding`, and `searchStatistics`. Usage
errors write no report at all because the run never started.

The document carries `formatVersion: 3`; the key set only changes with a
version bump. The published [JSON Schema](docs/json-report-v3.schema.json) and
[examples](docs/examples/) define the complete contract. Report files are
overwritten on every run (they are per-run telemetry, so no `--force` is
needed).

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

The per-event solve is bounded only by ProB's internal solver timeout, not
by an overall wall-clock limit, and its cost grows with the width of the
state description. In practice `cbc` is best suited to small machines: on
models with large or unbounded variable domains (for example integer- or
enumeration-heavy state) a single solve can run for a long time, so treat a
long-running `cbc` on such a model as expected rather than a hang. `--events`
can narrow the check to a subset of events, though a hard invariant or
constant setup dominates every event's solve and narrowing will not help
there.

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

#### Generate Test Traces

```bash
eventb-animate testgen path/to/model.bum --out traces/
eventb-animate testgen --operations inc,reset --depth 8 path/to/model.zip --out traces/
```

Generates an operation-coverage test suite with ProB's constraint solver,
without exploring the state space: for each operation it searches for a short
feasible trace that executes it, extending prefixes breadth-first (up to
`--depth` operation steps) until the operation is enabled. Every covered
operation is written to `--out` as a ProB JSON trace named
`<machine>_<operation>.json`, each of which `replay`s perfectly against the
model. One `coverage/<operation>` entry per target appears in the
`--json`/`--junit`/`--markdown` reports.

Coverage is best-effort and model-dependent. An operation is left *uncovered*
when no feasible witness is found within the depth and solver-time bounds --
some operations need long or specific sequences, or lie beyond the constraint
solver's reach (raise `--depth`/`--timeout` to search harder). An operation is
*infeasible* when its guard is unsatisfiable under the invariant: a dead
operation that can never be covered. Both are reported and, by default,
advisory (exit 0); the `--fail-on-*` flags turn them into CI failures.

Options:
- `--out <dir>` - Directory for the generated traces, one JSON file per
  covered operation (created if missing). Omit to only report coverage
  without writing traces
- `--operations <e1,e2,...>` - Restrict test generation to these target
  operations (comma-separated or repeated; default: every operation of the
  machine)
- `--depth <N>` - Maximum number of operation steps in a generated trace,
  i.e. how far the search extends prefixes to enable an operation
  (default: 5)
- `--final-ops <e1,e2,...>` - Operations after which a trace is complete and
  is not extended further
- `--timeout <ms>` - Per-attempt solver time bound; raise it when operations
  are reported uncovered on complex models (default: 2000)
- `--fail-on-uncovered` - Exit 2 when any target operation has no witness
  within the bounds (a non-verdict; advisory otherwise)
- `--fail-on-infeasible` - Exit 1 when any target operation is a dead
  operation (advisory otherwise)
- `--force` - Overwrite existing trace files in `--out`

#### Evaluate Formulas

```bash
eventb-animate eval -e 'card(dom(files))' -e 'files = ∅' path/to/model.bum
eventb-animate eval --where 'cars = 0' -e 'peds_go' path/to/model.zip
```

Evaluates Event-B expressions and predicates (ASCII or Unicode operators) in
explored states and reports each value. Because ProB evaluation is
state-addressed, no state is special: by default the formulas are evaluated in
the initialised state (after `$setup_constants` and `$initialise_machine`), and
with `--where PRED` they are evaluated in *every* explored state satisfying the
predicate — the CLI form of "evaluate over the state space".

A predicate reports its `TRUE`/`FALSE` verdict; a predicate computing to `FALSE`
is a successful evaluation (exit 0). Only a formula that cannot be parsed (exit 2,
a usage error before any load), or that parses but cannot be evaluated in the
state — a type or well-definedness error, an uninitialised identifier, or a value
the solver leaves unknown — makes the run a non-verdict (exit 2); the remaining
formulas are still evaluated and printed. The values are also written to the
`--json`/`--markdown` reports as an `evaluations` array (see
[Machine-Readable Reports](#machine-readable-reports)).

With `--where`, the run first explores the state space (with all checks disabled,
so exploration is never cut short by a violation) and then selects the matching
states, so results cover only the *bounded* explored space: `--states`/
`--time-limit` cap the search and a match beyond the bound is missed. A `--where`
query that matches no state is a successful, empty result (exit 0). Note also
that a non-deterministic `INITIALISATION` has more than one initial state, so
"the initialised state" is one arbitrary branch.

Options:
- `-e, --expr <formula>` - Event-B expression or predicate to evaluate
  (repeatable; required)
- `--where <predicate>` - Evaluate in every explored state satisfying this
  Event-B predicate instead of only the initialised state
- `--states <N>` - Bound the `--where` exploration to at most `N` states
  (default: exhaustive; only valid with `--where`)
- `--time-limit <seconds>` - Bound the `--where` exploration to the given
  wall-clock time (default: unlimited; only valid with `--where`)

#### Convert to Classical B

```bash
eventb-animate convert output.mch path/to/model.bum
eventb-animate convert path/to/model.bum          # writes ./model.mch
```

Translates the Event-B model into a Classical B machine (`.mch`). A ProB Event-B
`.eventb` prolog package is also accepted as input.

With the output omitted, the machine is written to the current directory under a
name derived from the machine selected by `-m/--machine`, or from the model's own
base name (`M0.bum` becomes `M0.mch`, a project directory or `.zip` archive its
own name). `--force` applies to that file as it does to an explicit one.

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

The GitHub actions and GitLab wrapper verify the downloaded jar against the
`SHA256SUMS` manifest published with the release before using it, and refuse a
release that ships no manifest rather than install it unverified. The default
`latest` is resolved when the job runs, so pin `version` (GitHub) or
`EVENTB_ANIMATE_VERSION` (GitLab) when a run needs to be reproducible. Both
GitHub actions cache the jar with `actions/cache`, keyed on the resolved
release tag; a restored jar is re-verified against `SHA256SUMS` before reuse,
and `cache: 'false'` opts out.

### GitHub Actions

```yaml
- uses: eventb-rossi/eventb-animate@v6.6
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
| `markdown-report` | Write a human-readable Markdown report of the run to this path | No | — |
| `trace` | Path to JSON trace file (replay, required) | No | — |
| `args` | Extra args appended to the assembled command | No | — |
| `version` | Release version tag (e.g., `v6.6`); pin it for a reproducible run | No | `latest` |
| `java-version` | Java version to install (21+) | No | `21` |
| `cache` | Cache the release jar between runs, keyed on the resolved release tag | No | `true` |

#### Set up the CLI for later steps

Use the setup action when a repository script owns the model-checking workflow:

```yaml
- uses: actions/setup-java@v6
  with:
    distribution: 'temurin'
    java-version: '21'

- id: setup-eventb-animate
  uses: eventb-rossi/eventb-animate/setup@v6.6
  with:
    version: 'v6.6'
    java-version: ''

- run: make check-models
```

The verified jar is installed with an `eventb-animate` launcher on `PATH` for
subsequent steps. The setup action supports Linux and macOS runners.
`java-version: ''` skips `actions/setup-java` and trusts the caller to have
configured Java 21 or later. The setup action and the root action's empty
`java-version` behavior are available in every current release. Pin the action
ref to a released tag for a reproducible setup.

| Input | Description | Required | Default |
|-------|-------------|----------|---------|
| `version` | Release version tag (e.g., `v6.6`); pin it for a reproducible install | No | `latest` |
| `java-version` | Java version to install (21+); set to `''` to use an existing JDK | No | `21` |
| `cache` | Cache the release jar between runs, keyed on the resolved release tag | No | `true` |

| Output | Description |
|--------|-------------|
| `version` | Resolved release tag, including its leading `v` |
| `jar-path` | Absolute path to the verified release jar |

#### Examples

```yaml
# Model-check, bounded to 50000 states
- uses: eventb-rossi/eventb-animate@v6.6
  with:
    model-path: 'path/to/model.bum'
    states: 50000

# Replay a trace
- uses: eventb-rossi/eventb-animate@v6.6
  with:
    model-path: 'models/system.bum'
    command: 'replay'
    trace: 'tests/trace.json'

# Gate on the Rodin proof status (extra flags go through args)
- uses: eventb-rossi/eventb-animate@v6.6
  with:
    model-path: 'models/system.zip'
    command: 'po'
    args: '--allow-reviewed'

# Pin to a specific release
- uses: eventb-rossi/eventb-animate@v6.6
  with:
    model-path: 'path/to/model.bum'
    version: 'v6.6'

# Publish the verdict as a test report rendered on the PR
- uses: eventb-rossi/eventb-animate@v6.6
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
  - remote: 'https://raw.githubusercontent.com/eventb-rossi/eventb-animate/v6.6/.gitlab-ci-template.yml'

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
| `EVENTB_ANIMATE_MARKDOWN` | Write a human-readable Markdown report of the run to this path | `''` |
| `EVENTB_ANIMATE_TRACE` | Path to JSON trace file (replay, required) | `''` |
| `EVENTB_ANIMATE_ARGS` | Extra args appended to the assembled command | `''` |
| `EVENTB_ANIMATE_VERSION` | Release version tag (e.g., `v6.6`) | `latest` |

#### Examples

```yaml
include:
  - remote: 'https://raw.githubusercontent.com/eventb-rossi/eventb-animate/v6.6/.gitlab-ci-template.yml'

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
    EVENTB_ANIMATE_VERSION: 'v6.6'

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
