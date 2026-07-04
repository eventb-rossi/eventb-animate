# Event-B Test Models

This directory contains Event-B models used for testing the animate program.

## Source

All models except `counter/` are from the repository:
https://github.com/17451k/eventb-models

## Models

### Counter (`counter/`)
- Hand-written fixture (no Rodin proof files) with a deliberately
  non-inductive invariant: `inc` is guarded by `x < 10` but the invariant
  requires `x < 5`, so the constraint-based check flags `inc` while `reset`
  preserves the invariant
- Models: M0.bum

### Gate (`gate/`)
- Hand-written fixture (no Rodin proof files) with one deterministic finding
  per constraint-based analysis: `step` is guarded by `y < 2` while the
  invariant allows `y ≤ 2`, so the state `y = 2` satisfies the invariant and
  deadlocks; `never` is guarded by `y > 5` and therefore infeasible; and
  `inv3` (`y ≤ 5`) is implied by `inv2` (`y ≤ 2`)
- Models: M0.bum

### Binary Search (`binary-search/`)
- Implementation of the binary search algorithm
- Models: M0.bum, M1.bum, M2.bum, M3.bum (refinement chain)

### Cars on Bridge (`cars-on-bridge/`)
- A model for controlling cars on a bridge
- Models: M0.bum, M1.bum, M2.bum, M3.bum (refinement chain)

### File System (`file-system/`)
- A Unix file system model
- Models: M0.bum

### Traffic Light (`traffic-light/`)
- A traffic light controller system
- Models: M0.bum, M1.bum, M2.bum (refinement chain)

## License

These models are provided under the MIT License as specified in the source repository.

## Usage in Tests

The models are automatically discovered and tested by:
- `ModelAnimationTest.java` - Tests loading, animation, and invariant checking
- `AnimateCliTest.java` - Tests the CLI tool with various models
