# Audio

All sounds of the Minecraft mod are synthesized from scratch by `tools/audio/synth_drone.py` (numpy + soundfile). No recording, sample or
third-party audio is read or bundled; the output is released under CC0. Parameters are frozen constants chosen for the desired timbre.
Run `python3 tools/audio/synth_drone.py` to regenerate `assets/fpvcore/sounds/*.ogg` and `sounds.json` (mono, 44.1 kHz, Ogg Vorbis).

## Motor sound

A quad motor sound is the sum of three component layers, each synthesized by `render_motor`:

* **Tone**: four motors, each a fundamental (blade-pass frequency) plus a harmonic series, slightly detuned from each other (relative offsets of a few
  percent) so they beat. Each motor has slow periodic phase jitter and slow amplitude wobble.
* **Whine**: a high carrier per motor (commutation / electrical noise) with a few sidebands.
* **Prop-wash noise**: noise shaped to a (Hz, dB) envelope, with slow turbulence modulation.

The three layers (low "idle" hum, mid "motor", high "prop") differ in fundamental, harmonic content, whine and noise envelope.

### RPM ladder

Minecraft can play a sound with pitch 0.5 to 2.0, which is too narrow for a drone that spans idle to full throttle. Instead the mod ships six
pre-rendered snapshots, `motor_1` .. `motor_6`, of the whole drone sound at six anchor values of a sound parameter `T` (0 = idle, about 0.6 at full
throttle). `tools/audio/motor_ladder.py` defines closed-form pitch and volume curves for the three components as functions of `T` (cubic pitch
curves, a bell-shaped volume for the idle hum, linear and quadratic volumes for the others), renders each snapshot with the components at their
pitch for that anchor, and writes `MotorCurves.java`: for every `T` in steps of 0.01, the playback pitch (clamped to 0.7 .. 1.4) and volume of every
snapshot. Neighbouring snapshots are cross-faded with an equal-power law and the overall level follows a loudness curve (quiet at idle).

At run time (`MotorModel`) the rpm fraction `n` (motor rpm divided by the preset's full-throttle rpm) becomes `T = T_full(preset) * n^1.72`, with
instant rise and smoothed fall, and all six layers play simultaneously with the tabulated pitch and volume.

## Wind, flip and one-shots

* `wind_low`, `wind_high`: seamless loops of noise shaped to a spectral envelope with slow gusts; the mod crossfades them with the square of the airspeed
  and raises their pitch from 0.8 to 1.9.
* `flip`: a rotation whoosh loop, played while the drone rotates fast.
* `crash_1..3`, `crash_big_1..2`: impact one-shots built from a low pitch-dropping thud, damped inharmonic modes (plastic or carbon "clack"), a frame ring,
  decaying secondary bounces and, for the big crash, scattered debris clicks and a noise tail.
* `prop_strike_1..2`: a short propeller-on-obstacle buzz: a saw tone with falling pitch, chopped noise scrape, and a clack.

All loops are periodic by construction (every partial has an integer number of cycles per loop and noise is shaped circularly), so they loop without
a click. A resource pack that defines the same `fpvcore:*` sound events overrides the bundled sounds.
