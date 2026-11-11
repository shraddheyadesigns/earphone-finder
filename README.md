# Earphone Finder (v0.2, glow)

Lost a bud under the sofa? Pick it from the list and the screen edge glows toward it: cold blue when far, rust and yellow when you're on top of it. Walk around a bit and the glow works out which way to point.

## The hunt screen

- Edge glow, ported 1:1 from the web prototype (`tools/glow-prototype.html`, your tuned values baked in)
- Calibrating: a slate glow circles the edge and Pixel Man walks next to "Walk around a bit"
- Found: the glow slows, settles on the bud's side and warms up. The arc widens as you close in
- Lost (no signal for 5 s): back to circling, Pixel Man looks around
- Distance in feet in the middle; warmer / colder / steady underneath
- Tap "details" at the bottom for the old engine screen (raw signal, logging, ring left/right)

The live shader needs Android 13+. Older phones get a simpler soft gradient glow.

## Run it

- Open this folder in Android Studio (Ladybug or newer)
- Let Gradle sync (it downloads Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21)
- Plug in your Android phone with USB debugging on, hit Run
- Engine tests: `./gradlew test` (or right-click `EngineTest` and Run)

## What's in here

- `engine/RssiSmoother.kt` turns jumpy signal into a steady number, plus warmer/colder
- `engine/DirectionSweep.kt` the body-blocking trick: turn a circle, get a heading
- `engine/Proximity.kt` dBm to "very close / same room / far", hotness 0..1, rough metres
- `bt/BtScanner.kt` BLE scan of everything around, optional classic discovery
- `sensors/Compass.kt` which way you're facing, works flat or upright
- `audio/Sounds.kt` hot/cold ticks and a loud left-only or right-only ring
- `engine/BearingEstimator.kt` walk-to-calibrate: steps + compass + signal changes give the direction
- `sensors/StepSensor.kt` one event per step (needs motion permission)
- `ui/glow/` the AGSL shader, its animator (spin, settle, spread) and the Compose layer
- `ui/HuntScreen.kt` the hunt screen, Pixel Man and the text
- `tools/sim.py` the simulation used to pick the smoothing numbers
- `tools/bearing_sim.py` the simulation for walk-to-calibrate
- `tools/glow-prototype.html` the web prototype; open it in a browser to tweak the look
- `tools/analyze_log.py` plots a recorded hunt (raw vs smoothed, plus the sweep as a polar chart)

## First experiment: what does your bud actually broadcast?

This decides everything else, and it's different per brand.

1. Open the app, list screen
2. Take one bud out of the case, keep the other in your ear
3. Look for new rows near the top with a strong number (-40 to -60)
4. Now put that bud in a drawer across the room. Does its row stay alive? Does the number drop?
5. Try again with the bud disconnected from the phone (turn the phone's Bluetooth off and on, or forget it briefly)

Write down per bud: name shown, BLE or classic, does it advertise while connected, does the address stay the same. Cheap TWS buds often only advertise when they lose the phone, which is actually the lost-under-the-sofa case.

## Test set (the "done" bar is under a minute)

- Record a log per hunt with the Record button
- Pull logs: `adb pull /sdcard/Android/data/com.shady.earphonefinder/files/logs ./logs`
- Run 10 hunts: hide the bud, a friend times you, note found-in seconds and which cue helped
- Check each log with `python tools/analyze_log.py logs/<file>.csv`
- Tune the cut-offs in `Proximity.kt` and the numbers in `RssiSmoother` from what you see

## Tuned numbers (from the sim)

- 2 s median, 1.5 s smoothing, 3 dB over 3 s for warmer/colder
- Standing still: says warmer/colder only ~3% of the time
- Walking closer: says "colder" under 1% of the time
- Direction sweep: median ~9 degrees off, 80% within ~20 degrees
- Walk-to-calibrate (bud 3 to 6 m away): locks in ~7 s of walking, median ~22 degrees off, 80% within ~40. Closer than 3 m it gets looser, which is why the arc widens

Real rooms are messier than the sim (reflections, your hand on the phone), so expect worse until tuned.

## Known limits

- A bud in a closed case, asleep, or dead usually stops broadcasting. Pitch is "fell under the sofa", not "lost for a week"
- Some buds rotate their BLE address every ~15 min. If the row vanishes and a new one appears, that's why
- If the lost bud is still connected to the phone it often goes quiet on BLE. Use Ring left/right instead
- Distance in metres is a rough guess. Trust warmer/colder more than the number

## How to test the hunt

1. Hide a bud somewhere in a room, open the app, pick it
2. Walk a few steps one way, then a few another way. Watch for the glow to settle
3. Follow the glow. Note how long it took and whether it ever pointed the wrong way
4. Tap "details" any time to see steps, direction confidence and where it thinks the bud is

If it never locks: check "steps" in details is going up. If not, motion permission is off.

## Fonts

Inter Tight and Space Mono are bundled under the SIL Open Font License (see `licenses/`).

## Next steps

- Real-world tuning from your test hunts (lock threshold, step length, arc spread)
- Haptic ticks that speed up as you get warmer
- Auto-pick the bud: match against paired devices and remember its address
