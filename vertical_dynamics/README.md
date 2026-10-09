# Vertical Dynamics Core

A small UE-independent C++17 core for the rotorcraft project's one-dimensional
vertical motion model. It has no external dependencies.

## Model

- `z`: height above the ground, in metres (m).
- `v`: vertical velocity, in metres per second (m/s); positive is upward.
- `m`: mass, in kilograms (kg), and must be finite and greater than zero.
- `g`: gravitational acceleration, in metres per second squared (m/s^2), and
  must be finite and greater than zero.
- `T`: upward thrust, in newtons (N), and must be finite and non-negative.
- `dt`: fixed step duration, in seconds (s), and must be finite and greater
  than zero.

Acceleration is `a = T / m - g`. Each step uses semi-implicit Euler, updating
velocity first (`v_next = v + a * dt`) and then height
(`z_next = z + v_next * dt`). Height is clamped to zero at the ground; a
downward velocity at contact is set to zero. There is no bounce, air drag,
attitude, or six-degree-of-freedom motion. A zero-thrust model is free fall;
hover requires `T = m * g`.

`VerticalDynamics::initialize(m, g, dt)` configures the fixed step and starts at
`z = 0`, `v = 0`, and `T = 0`. Use `set_thrust`, parameterless `step`, and
`get_state` to control and query the model. `reset` returns all dynamic state,
including thrust, to those initial values while keeping the configured
parameters. Each operation returns a `Result` with a specific `Error`; invalid
operations do not modify the model or the supplied state output.

## Build and test

From this directory, with CMake and a C++17 compiler available:

```sh
cmake -S . -B build
cmake --build build
ctest --test-dir build --output-on-failure
```
