# Demo video tooling

Everything used to make `docs/media/demo.mp4`, `demo-preview.gif` and the screenshots. All of it is original or free: the course is built from code, the sounds are the
mod's own CC0 synth layers, the music bed is synthesised (`make_music.py`, CC0), captions use Montserrat (SIL OFL 1.1).

## Recording (deterministic, inside the game)

The mod has a hidden demo mode (client only, off unless `-Dfpvcore.demo=<plan>` is set): fixed 60 Hz simulation step, a virtual game clock (one video frame per rendered frame),
frame hold while chunks/sections are still loading, an autopilot (`demo/PathPilot.java`: it only produces stick inputs, the physics is the normal fpvcore model) that flies the
scripted course (`demo/CoursePath.java`), a camera director (`demo/DemoDirector.java`) and a frame recorder that pipes raw frames to `ffmpeg` and writes a telemetry CSV
(rpm, speed, impacts ...) used to render the audio offline.

The race course is generated in a fresh flat world by `demo/CourseBuilder.java` (meadow, forest, mountains, lake, lit tunnel, waterfall arch, dive tower, glowing gate rings,
corkscrew, loop, finish arch). It is built automatically the first time a world name is used (`-Dfpvcore.demo.world=<name>`), so the course can be recreated by anyone.

```
cd adapters/minecraft-fabric
export JAVA_HOME=...jdk-21
# plans: chase fpv osd multi wing f5 cal   (see DemoDirector.buildPlan)
ALSOFT_DRIVERS=null JAVA_TOOL_OPTIONS="-Xmx3500m -Dfpvcore.demo=chase -Dfpvcore.demo.out=/tmp/takes -Dfpvcore.demo.world=fpvdemo4" \
  gamescope --backend headless -W 1920 -H 1080 -w 1920 -h 1080 -- \
  ./gradlew runClient3 -Dfpvcore.rundir=tmp/videorun -Dfpvcore.user=Pilot -Dfpvcore.size=1920x1080
```
(`tmp/videorun/options.txt` needs `onboardAccessibility:false`.) Use any offline player name; the demo uses "Pilot".

## Editing

```
python3 tools/demo/assemble.py /tmp/takes /tmp/work docs/media
```
renders the audio of every take (`render_audio.py`, same pitch/volume tables as the in-game `MotorModel`), cuts the takes according to the list at the top of the script, adds the
captions (ffmpeg `drawtext`, needs an ffmpeg with libfreetype) and the music, and encodes `demo.mp4`.
