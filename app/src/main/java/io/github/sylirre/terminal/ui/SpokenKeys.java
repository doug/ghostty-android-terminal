/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Maps a whole spoken utterance to keys, so voice input can drive the
 * terminal and not only type into it ("escape", "control c", "next window").
 *
 * Only an utterance that is <em>entirely</em> a known phrase matches; anything
 * else is dictation and goes into the input box as text. So "escape" presses
 * Esc, while "press escape to quit" is typed. Matching is forgiving about the
 * forms a recognizer produces: case, punctuation, hyphens and "+" ("Ctrl-C",
 * "control+c."), "ctrl" for "control", and number words ("window two").
 *
 * Deliberately free of Android types so the grammar is testable on a plain
 * JVM. A match is a list of tokens the caller turns into key events:
 * <ul>
 *   <li>{@code send} — send the input box's text (if any) followed by Enter;</li>
 *   <li>{@code clear} — empty the input box;</li>
 *   <li>{@code key:<name>} — a special key: escape, tab, backspace, up, down,
 *       left, right, pageup, pagedown;</li>
 *   <li>{@code ctrl:<c>} — Ctrl plus a single character;</li>
 *   <li>{@code text:<s>} — literal text.</li>
 * </ul>
 */
final class SpokenKeys {

    private SpokenKeys() {}

    // tmux's default prefix, Ctrl-B, then the command key.
    private static final String TMUX = "ctrl:b";

    private static final String[] NUMBER_WORDS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"};

    /** Tokens for {@code utterance}, or null when it is dictation, not a command. */
    static List<String> match(String utterance) {
        if (utterance == null) return null;
        String s = normalize(utterance);
        if (s.isEmpty()) return null;

        switch (s) {
            case "enter": case "return": case "send": case "submit":
                return one("send");
            case "clear": case "scratch that": case "clear box":
                return one("clear");
            case "escape":
                return one("key:escape");
            case "tab":
                return one("key:tab");
            case "backspace": case "back space":
                return one("key:backspace");
            case "up": case "up arrow": case "arrow up":
                return one("key:up");
            case "down": case "down arrow": case "arrow down":
                return one("key:down");
            case "left": case "left arrow": case "arrow left":
                return one("key:left");
            case "right": case "right arrow": case "arrow right":
                return one("key:right");
            case "page up":
                return one("key:pageup");
            case "page down":
                return one("key:pagedown");
            case "interrupt":
                return one("ctrl:c");
            case "next window":
                return two(TMUX, "text:n");
            case "previous window": case "last window":
                return two(TMUX, "text:p");
            case "new window":
                return two(TMUX, "text:c");
            case "detach":
                return two(TMUX, "text:d");
            case "copy mode": case "scroll mode":
                return two(TMUX, "text:[");
            default:
                break;
        }

        String[] w = s.split(" ");
        // "control c" (also "ctrl c", after normalize): one letter or digit.
        if (w.length == 2 && w[0].equals("control") && isSingleKeyChar(w[1])) {
            return one("ctrl:" + w[1]);
        }
        // "option 2" / "choose two" / "number 2": a menu pick, typed as the digit.
        if (w.length == 2 && (w[0].equals("option") || w[0].equals("choose")
                || w[0].equals("number")) && isDigit(w[1])) {
            return one("text:" + w[1]);
        }
        // "window 3": tmux's select-window by index.
        if (w.length == 2 && w[0].equals("window") && isDigit(w[1])) {
            return two(TMUX, "text:" + w[1]);
        }
        return null;
    }

    /**
     * Lower-cases, turns "-"/"+"/"_" into spaces, drops other punctuation,
     * collapses whitespace, spells "ctrl" as "control", and turns number words
     * into digits.
     */
    static String normalize(String utterance) {
        String s = utterance.toLowerCase(Locale.ROOT)
                .replaceAll("[-+_]", " ")
                .replaceAll("[^a-z0-9\\[\\] ]", "")
                .trim()
                .replaceAll("\\s+", " ");
        if (s.isEmpty()) return s;
        String[] w = s.split(" ");
        for (int i = 0; i < w.length; i++) {
            if (w[i].equals("ctrl")) w[i] = "control";
            for (int d = 0; d < NUMBER_WORDS.length; d++) {
                if (w[i].equals(NUMBER_WORDS[d])) w[i] = Integer.toString(d);
            }
        }
        return String.join(" ", w);
    }

    private static boolean isSingleKeyChar(String t) {
        return t.length() == 1 && Character.isLetterOrDigit(t.charAt(0));
    }

    private static boolean isDigit(String t) {
        return t.length() == 1 && t.charAt(0) >= '0' && t.charAt(0) <= '9';
    }

    private static List<String> one(String a) {
        return Collections.singletonList(a);
    }

    private static List<String> two(String a, String b) {
        return Arrays.asList(a, b);
    }
}
