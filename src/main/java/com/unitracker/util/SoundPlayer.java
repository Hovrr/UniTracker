package com.unitracker.util;

import javafx.scene.media.AudioClip;

import java.net.URL;
import java.util.EnumMap;
import java.util.Map;

/**
 * Short UI sound effects.
 *
 * <p>WHY AudioClip AND NOT javax.sound.sampled: the Pomodoro chime originally
 * used javax.sound.sampled because javafx.media is not a declared dependency in
 * pom.xml. It turns out javafx-web's POM already pulls javafx-media in
 * transitively, so AudioClip costs no new dependency and replaces ~20 lines of
 * stream/line/listener plumbing with one call. NOTE: javafx.web does not
 * <em>require</em> javafx.media as a module, so javafx.media must be listed
 * explicitly in the --add-modules flags or this class fails at runtime.
 *
 * <p>DESIGN - CLIPS ARE CACHED AND PRELOADED: an AudioClip holds its samples in
 * memory, so constructing one per click would re-decode the same wav hundreds of
 * times a session. Each file is loaded at most once, on first use, and reused.
 *
 * <p>DESIGN - MISSING FILES ARE NORMAL, NOT ERRORS: the wav assets are supplied
 * by the user and may legitimately not be there. A missing file logs one line
 * the first time and is then permanently skipped, so a silent install behaves
 * exactly like a working one minus the sound. Sound is feedback, never a
 * precondition for the action that triggered it.
 */
public final class SoundPlayer {

    /** The four effects the dashboard triggers. Paths are relative to
     *  src/main/resources/com/unitracker/. */
    public enum Sfx {
        CLICK("sounds/general_click.wav"),
        LOG_SESSION("sounds/log_session.wav"),
        TIMER_FINISH("sounds/timer_finish.wav"),
        LEVEL_UP("sounds/level_up_confetti.wav");

        private final String path;

        Sfx(String path) {
            this.path = path;
        }
    }

    /** Loaded clips. A key present with a null value = "we already looked and
     *  it is not there", which is what stops the log line repeating forever. */
    private static final Map<Sfx, AudioClip> CACHE = new EnumMap<>(Sfx.class);

    private static boolean muted = false;

    private SoundPlayer() {
        // Static utility - not instantiable.
    }

    /** Global off switch, persisted by the caller via app_settings. */
    public static void setMuted(boolean value) {
        muted = value;
    }

    public static boolean isMuted() {
        return muted;
    }

    /**
     * Fires the effect and returns immediately. Never throws: audio problems
     * (no sound card, device held by another app, absent or malformed file)
     * must not interrupt logging a session or levelling up.
     */
    public static void play(Sfx sfx) {
        if (muted) return;
        try {
            // Not computeIfAbsent: it discards null mappings, so a missing file
            // would be looked up and logged again on every single play.
            if (!CACHE.containsKey(sfx)) CACHE.put(sfx, load(sfx));
            AudioClip clip = CACHE.get(sfx);
            if (clip != null) clip.play();
        } catch (Exception e) {
            // Includes MediaException, which is unchecked.
            System.err.println("[Sound] Could not play " + sfx.path + ": " + e.getMessage());
        }
    }

    private static AudioClip load(Sfx sfx) {
        URL url = SoundPlayer.class.getResource("/com/unitracker/" + sfx.path);
        if (url == null) {
            System.out.println("[Sound] Not found, silent: " + sfx.path);
            return null;
        }
        try {
            return new AudioClip(url.toExternalForm());
        } catch (Exception e) {
            System.err.println("[Sound] Could not load " + sfx.path + " (must be real PCM .wav): "
                    + e.getMessage());
            return null;
        }
    }
}
