/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.github.sylirre.terminal.R;
import io.github.sylirre.terminal.term.TerminalNative;

/**
 * Special-key toolbar shown above the soft keyboard — a full-width, stacked
 * flex-grid "keyboard".
 *
 * The set, order, grouping into rows, per-key widths and secondaries are driven
 * by an {@link ExtraKeysConfig} (edited in {@link ExtraKeysActivity}); call
 * {@link #setConfig} once and {@link #reload} whenever the config may have
 * changed.
 *
 * The toolbar is a vertical stack of 1–{@link ExtraKeysConfig#MAX_ROWS} rows.
 * Each row is a horizontal {@link LinearLayout} that fills the width: every
 * keycap has {@code width=0} and {@code layout_weight = key.width} (1.0 / 1.5 /
 * 2.0), so the caps flex to fill the row and never scroll off-edge. Rows stack
 * and read as one keyboard.
 *
 * Gestures:
 * <ul>
 *   <li>tap → the primary key;</li>
 *   <li>swipe up → the cap's secondary key, if it defines one (hinted in the
 *       top-right corner by {@link KeyCapView});</li>
 *   <li>long-press → auto-repeat (KEY/TEXT) or lock (sticky CTRL/ALT);</li>
 *   <li>swipe left or right anywhere on the toolbar → swap the key rows for a
 *       text input box, and back.</li>
 * </ul>
 * The text input box is a plain {@link EditText}, so the IME's voice typing,
 * glide typing and autocorrect work in it whatever the terminal is running
 * (tmux, vim, …), unlike the rich-keyboard mirror. Send (the IME action or
 * the trailing button) writes the text to the PTY followed by Enter and
 * clears the box; an empty Send is a bare Enter. Long-pressing the send button
 * types the text without Enter. A delete button removes the box's last word
 * (long-press clears it), or sends Backspace to the terminal when it is empty.
 * CTRL and ALT are sticky: they highlight and apply to the next key or typed
 * character (via {@link TerminalView.StickyModifiers}). Everything else sends
 * immediately — non-printable keys through the VT key encoder, literal text
 * straight to the PTY.
 */
public class ExtraKeysView extends LinearLayout {

    private TerminalView terminal;
    private final TerminalView.StickyModifiers sticky = new TerminalView.StickyModifiers();
    private ExtraKeysConfig config;
    private ChromePalette palette;

    // When false the toolbar is hidden regardless of the configured keys; the
    // keys themselves are untouched, so flipping this back shows them as before.
    private boolean enabledRow = true;

    // When true, the toolbar additionally hides while the soft keyboard is down.
    private boolean hideWhenKeyboardHidden = false;
    // Tracks the last known IME visibility so we can react without a full reload.
    private boolean keyboardVisible = true;
    // Set by reload(); needed so applyVisibility() can check emptiness without
    // re-querying the config.
    private boolean hasKeys = false;

    // When true (and more than one profile exists), a leading switch column
    // lets the user cycle / choose the active layout profile.
    private boolean showSwitch = false;

    // Sticky-modifier buttons currently on screen, with the bit each toggles, so
    // updateToggles() can recolor them without knowing the layout in advance.
    private final List<ModButton> modButtons = new ArrayList<>();

    private static final int REPEAT_INTERVAL_MS = 80;

    // Upward travel (px) that turns a press into a swipe-up secondary emit.
    private final int swipeThresholdPx;

    // Vertical padding (px) inside each keycap; the toolbar's row-height knob.
    // Defaults to key_pad_v and is overridden by setKeyVerticalPaddingDp().
    private int keyPaddingV;

    // Text-input mode: the toolbar shows an EditText + send button instead of
    // the key rows. The field is built once and kept across reload() so a
    // palette or config change never drops half-typed text.
    private boolean textMode = false;
    private EditText input;
    private ImageView micButton;
    private ImageView deleteButton;
    private ImageView sendButton;

    // Voice input into the box (see VoiceInput / SpokenKeys). voiceBase is the
    // box's text when listening began: partial transcripts are shown after it,
    // and it is what's restored when the utterance turns out to be a command.
    private VoiceInput voice;
    private String voiceBase = "";
    private boolean voiceDirectSend;

    // Content height (px) the key rows last had, so the input row can take the
    // same height and the swap never resizes the terminal (no SIGWINCH).
    private int keysContentHeight = 0;

    // Horizontal-swipe tracking for the keys <-> input toggle.
    private float swipeDownX, swipeDownY;
    private boolean swipeHandled;

    private static final class ModButton {
        final KeyCapView view;
        final int modifier;
        ModButton(KeyCapView view, int modifier) {
            this.view = view;
            this.modifier = modifier;
        }
    }

    public ExtraKeysView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        palette = ChromePalette.from(context, android.graphics.Color.BLACK);
        setBackground(palette.barSurface(false));
        // Half the cap gap: each cap carries key_gap/2 margins on every side,
        // so this makes the outer gutters equal the 4dp inner ones.
        int pad = Chrome.dp(context, R.dimen.key_gap) / 2;
        setPadding(pad, pad, pad, pad);
        swipeThresholdPx = Math.max(ViewConfiguration.get(context).getScaledTouchSlop() * 2,
                Chrome.dp(context, R.dimen.space_5));
        keyPaddingV = Chrome.dp(context, R.dimen.key_pad_v);
        sticky.onChanged = this::updateToggles;
    }

    /** Re-derives the toolbar's colors from a new chrome palette. */
    public void applyPalette(ChromePalette p) {
        palette = p;
        setBackground(palette.barSurface(false));
        reload();
    }

    /** Must be called before the toolbar is used. */
    public void attachTerminal(TerminalView view) {
        terminal = view;
        view.setStickyModifiers(sticky);
    }

    /** Binds the config and builds the keys; call once after construction. */
    public void setConfig(ExtraKeysConfig config) {
        this.config = config;
        reload();
    }

    /**
     * Shows or hides the whole toolbar without touching the configured keys.
     * Rebuilds so the change applies immediately.
     */
    public void setRowEnabled(boolean enabled) {
        enabledRow = enabled;
        reload();
    }

    /**
     * When {@code hide} is true, the toolbar additionally hides while the soft
     * keyboard is not visible (as reported by {@link #setKeyboardVisible}).
     */
    public void setHideWhenKeyboardHidden(boolean hide) {
        hideWhenKeyboardHidden = hide;
        applyVisibility();
    }

    /**
     * Shows or hides the leading profile-switch column (only ever visible when
     * more than one profile exists). Rebuilds so the change applies immediately.
     */
    public void setShowSwitch(boolean show) {
        if (showSwitch == show) return;
        showSwitch = show;
        reload();
    }

    /**
     * Sets the vertical padding inside each keycap — the toolbar's row-height
     * knob. Rows stay wrap-content, so a larger value grows the row and tap
     * target while the autosized label never clips. Rebuilds on change.
     */
    public void setKeyVerticalPaddingDp(int dp) {
        int px = Math.round(dp * getResources().getDisplayMetrics().density);
        if (keyPaddingV == px) return;
        keyPaddingV = px;
        reload();
    }

    /**
     * Called by the activity whenever the IME appears or disappears so the
     * toolbar can react when {@link #setHideWhenKeyboardHidden} is on.
     */
    public void setKeyboardVisible(boolean visible) {
        if (keyboardVisible == visible) return;
        keyboardVisible = visible;
        applyVisibility();
    }

    /** Rebuilds the key rows from the current config (call after edits). */
    public void reload() {
        removeAllViews();
        modButtons.clear();
        if (config == null) return;
        List<List<ExtraKey>> rows = config.enabledRows(getContext());
        hasKeys = !rows.isEmpty();

        if (textMode && hasKeys) {
            setOrientation(VERTICAL);
            addView(buildInputRow(),
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            applyVisibility();
            return;
        }

        // With the switch column the toolbar becomes [switch | stacked rows];
        // without it the rows stack directly in this vertical container.
        LinearLayout rowsContainer;
        if (showSwitch && hasKeys && config.profileCount() > 1) {
            setOrientation(HORIZONTAL);
            LayoutParams swLp = new LayoutParams(
                    Chrome.dp(getContext(), R.dimen.key_switch_width), LayoutParams.MATCH_PARENT);
            int m = Chrome.dp(getContext(), R.dimen.key_gap) / 2;
            swLp.setMargins(m, m, m, m);
            addView(buildSwitchColumn(), swLp);
            rowsContainer = new LinearLayout(getContext());
            rowsContainer.setOrientation(VERTICAL);
            addView(rowsContainer, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        } else {
            setOrientation(VERTICAL);
            rowsContainer = this;
        }

        for (List<ExtraKey> row : rows) {
            LinearLayout rowView = new LinearLayout(getContext());
            rowView.setOrientation(HORIZONTAL);
            for (ExtraKey key : row) addKey(key, rowView);
            // Once the row has real widths, pin every cap in it to one label
            // size (the narrowest cap's fitted size) — per-cap autosizing
            // otherwise mixes label sizes within a row.
            rowView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) KeyCaps.uniformize((LinearLayout) v);
            });
            rowsContainer.addView(rowView,
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        applyVisibility();
        updateToggles();
    }

    // --- Text input box ---

    /** Swaps between the key rows and the text input box. */
    private void setTextMode(boolean on) {
        if (textMode == on) return;
        if (on && getHeight() > 0) {
            // Measured while the key rows are still what's laid out.
            keysContentHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        }
        if (!on && voice != null) voice.cancel();
        textMode = on;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        reload();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        View target = on ? input : terminal;
        if (target == null) return;
        target.requestFocus();
        // Only raise the keyboard if it was already up: with it down (voice
        // input, or simply put away) the swap shouldn't bring it back. Tapping
        // the field or the terminal raises it as usual.
        if (imm != null && keyboardVisible) imm.showSoftInput(target, 0);
    }

    /** Hooks up voice input; until this is called the mic button does nothing. */
    public void setVoiceInput(VoiceInput v) {
        voice = v;
        v.setListener(voiceListener);
    }

    /**
     * Mic button: start listening (hiding the keyboard, which voice input is
     * meant to do without), or stop early if already listening. With
     * {@code directSend} the transcript is sent with Enter as soon as it's
     * final instead of waiting in the box.
     */
    private void toggleVoice(boolean directSend) {
        if (voice == null || input == null) return;
        if (voice.isListening()) {
            voice.stop();
            return;
        }
        voiceDirectSend = directSend;
        voiceBase = input.getText().toString();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        voice.start();
    }

    private static String appendSpoken(String base, String spoken) {
        if (base.isEmpty()) return spoken;
        return base.endsWith(" ") ? base + spoken : base + " " + spoken;
    }

    private void setInputText(String text) {
        input.setText(text);
        input.setSelection(text.length());
    }

    private final VoiceInput.Listener voiceListener = new VoiceInput.Listener() {
        @Override
        public void onListeningChanged(boolean listening) {
            if (micButton == null) return;
            micButton.setBackground(listening ? keyBgActive() : keyBg());
            micButton.setImageTintList(ColorStateList.valueOf(
                    listening ? palette.onAccent : palette.textSecondary));
            input.setHint(listening ? R.string.voice_listening_hint
                    : R.string.extra_keys_text_input_hint);
        }

        @Override
        public void onPartial(String text) {
            if (input != null) setInputText(appendSpoken(voiceBase, text));
        }

        @Override
        public void onFinal(String text) {
            if (input == null) return;
            List<String> command = SpokenKeys.match(text);
            if (command != null) {
                setInputText(voiceBase);
                runSpoken(command);
                return;
            }
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                setInputText(voiceBase);
                return;
            }
            setInputText(appendSpoken(voiceBase, trimmed));
            if (voiceDirectSend) sendInput(true);
        }

        @Override
        public void onError(String message) {
            if (input != null) setInputText(voiceBase);
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    };

    /** Performs a spoken command's tokens (see {@link SpokenKeys}). */
    private void runSpoken(List<String> tokens) {
        if (terminal == null) return;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        for (String t : tokens) {
            if (t.equals("send")) {
                sendInput(true);
            } else if (t.equals("clear")) {
                setInputText("");
            } else if (t.startsWith("key:")) {
                int code = spokenKeyCode(t.substring(4));
                if (code != KeyEvent.KEYCODE_UNKNOWN) terminal.dispatchKey(code);
            } else if (t.startsWith("ctrl:")) {
                terminal.dispatchText(t.substring(5), TerminalNative.MOD_CTRL);
            } else if (t.startsWith("text:")) {
                terminal.dispatchText(t.substring(5));
            }
        }
    }

    private static int spokenKeyCode(String name) {
        switch (name) {
            case "escape": return KeyEvent.KEYCODE_ESCAPE;
            case "tab": return KeyEvent.KEYCODE_TAB;
            case "backspace": return KeyEvent.KEYCODE_DEL;
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "pageup": return KeyEvent.KEYCODE_PAGE_UP;
            case "pagedown": return KeyEvent.KEYCODE_PAGE_DOWN;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    /**
     * The input row: a wrapping text field and a send button, styled like the
     * keycaps and pinned to at least the key rows' height. The views are built
     * once and re-parented on every reload so their text survives it.
     */
    private View buildInputRow() {
        Context ctx = getContext();
        int m = Chrome.dp(ctx, R.dimen.key_gap) / 2;
        float r = keyRadius();
        if (input == null) {
            input = new EditText(ctx);
            // A plain one-line text type, so the IME shows Send rather than a
            // newline key — then let it wrap instead of scrolling sideways,
            // which keeps a horizontal drag free to mean "switch back".
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
            input.setHorizontallyScrolling(false);
            input.setMaxLines(4);
            input.setImeOptions(EditorInfo.IME_ACTION_SEND
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            input.setHint(R.string.extra_keys_text_input_hint);
            input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            input.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Chrome.dimen(ctx, R.dimen.text_action));
            input.setPaddingRelative(Chrome.dp(ctx, R.dimen.space_3),
                    Chrome.dp(ctx, R.dimen.space_1),
                    Chrome.dp(ctx, R.dimen.space_3),
                    Chrome.dp(ctx, R.dimen.space_1));
            input.setOnEditorActionListener((v, actionId, event) -> {
                // IME Send, or Enter on a hardware keyboard (which arrives as
                // IME_NULL with a down and an up event: act on the down only).
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) sendInput(true);
                return true;
            });

            micButton = new ImageView(ctx);
            micButton.setImageResource(R.drawable.ic_glyph_mic);
            micButton.setScaleType(ImageView.ScaleType.CENTER);
            micButton.setContentDescription(ctx.getString(R.string.extra_keys_voice));
            micButton.setClickable(true);
            micButton.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                toggleVoice(false);
            });
            // Long-press: dictate and send straight away, with Enter.
            micButton.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                toggleVoice(true);
                return true;
            });

            deleteButton = new ImageView(ctx);
            deleteButton.setImageResource(R.drawable.ic_glyph_backspace);
            deleteButton.setScaleType(ImageView.ScaleType.CENTER);
            deleteButton.setContentDescription(
                    ctx.getString(R.string.extra_keys_text_input_delete));
            deleteButton.setClickable(true);
            deleteButton.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                deleteWord();
            });
            // Long-press: clear the whole box.
            deleteButton.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                input.setText("");
                return true;
            });

            sendButton = new ImageView(ctx);
            sendButton.setImageResource(R.drawable.ic_glyph_enter);
            sendButton.setScaleType(ImageView.ScaleType.CENTER);
            sendButton.setContentDescription(ctx.getString(R.string.extra_keys_text_input_send));
            sendButton.setClickable(true);
            sendButton.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                sendInput(true);
            });
            // Long-press: type the text without Enter, to finish it by hand.
            sendButton.setOnLongClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                sendInput(false);
                return true;
            });
        }
        for (View v : new View[] {input, micButton, deleteButton, sendButton}) {
            if (v.getParent() != null) ((LinearLayout) v.getParent()).removeView(v);
        }
        boolean listening = voice != null && voice.isListening();
        micButton.setImageTintList(ColorStateList.valueOf(
                listening ? palette.onAccent : palette.textSecondary));
        micButton.setBackground(listening ? keyBgActive() : keyBg());
        input.setTextColor(palette.textPrimary);
        input.setHintTextColor(palette.textSecondary);
        input.setBackground(palette.rounded(palette.surface2, r, palette.border));
        deleteButton.setImageTintList(ColorStateList.valueOf(palette.textSecondary));
        deleteButton.setBackground(keyBg());
        sendButton.setImageTintList(ColorStateList.valueOf(palette.accent));
        sendButton.setBackground(keyBg());

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setMinimumHeight(keysContentHeight);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        fieldLp.setMargins(m, m, m, m);
        row.addView(input, fieldLp);
        for (View b : new View[] {micButton, deleteButton, sendButton}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Chrome.dp(ctx, R.dimen.icon_button), LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins(m, m, m, m);
            row.addView(b, lp);
        }
        return row;
    }

    /**
     * Writes the box's text to the terminal, followed by Enter when
     * {@code withEnter}, then clears the box (focus stays, so the next line
     * can be dictated straight away). The text goes raw, exactly as if typed;
     * Enter goes through the key encoder. An empty box with Enter is a bare
     * Enter; an empty box without it does nothing.
     */
    private void sendInput(boolean withEnter) {
        if (input == null || terminal == null) return;
        String text = input.getText().toString();
        if (!text.isEmpty()) terminal.dispatchText(text);
        if (withEnter) terminal.dispatchKey(KeyEvent.KEYCODE_ENTER);
        input.setText("");
    }

    /**
     * Deletes the selection, or else the word before the cursor along with the
     * spaces after it, so a misheard ending can be dropped and re-dictated.
     * With the box empty it sends Backspace to the terminal instead.
     */
    private void deleteWord() {
        if (input == null || terminal == null) return;
        Editable e = input.getText();
        if (e.length() == 0) {
            terminal.dispatchKey(KeyEvent.KEYCODE_DEL);
            return;
        }
        int start = input.getSelectionStart();
        int end = input.getSelectionEnd();
        if (start < 0 || end < 0) start = end = e.length();
        if (start > end) { int t = start; start = end; end = t; }
        if (start == end) {
            while (start > 0 && Character.isWhitespace(e.charAt(start - 1))) start--;
            while (start > 0 && !Character.isWhitespace(e.charAt(start - 1))) start--;
        }
        e.delete(start, end);
    }

    // A sideways drag anywhere on the toolbar toggles the input box. It is
    // watched in dispatchTouchEvent rather than onInterceptTouchEvent because
    // the EditText calls requestDisallowInterceptTouchEvent on a drag, which
    // would hide the gesture from an intercept. Once it qualifies, the child
    // under the finger gets CANCEL (a keycap drops its repeat/long-press
    // without emitting) and the rest of the gesture is swallowed. Mostly
    // vertical drags are left alone: swipe-up is the caps' secondary gesture.

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean wasSwipe = swipeHandled && ev.getActionMasked() != MotionEvent.ACTION_DOWN;
        if (wasSwipe) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                swipeHandled = false;
            }
            return true;
        }
        if (trackSwipe(ev)) {
            MotionEvent cancel = MotionEvent.obtain(ev);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        super.dispatchTouchEvent(ev);
        return true; // keep receiving a gesture that began on a gutter
    }

    /** True once the gesture in progress has become a toggle swipe. */
    private boolean trackSwipe(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getX();
                swipeDownY = ev.getY();
                swipeHandled = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (swipeHandled) return true;
                float dx = Math.abs(ev.getX() - swipeDownX);
                float dy = Math.abs(ev.getY() - swipeDownY);
                if (dx > swipeThresholdPx * 2 && dx > dy * 2) {
                    swipeHandled = true;
                    // Posted: the toggle rebuilds the children, which must not
                    // happen while this very event is being dispatched to them.
                    post(() -> setTextMode(!textMode));
                    return true;
                }
                return false;
            default:
                return swipeHandled;
        }
    }

    /** The leading profile-switch cap: tap cycles the active profile, long-press chooses. */
    private View buildSwitchColumn() {
        ImageView v = new ImageView(getContext());
        v.setImageResource(R.drawable.ic_glyph_layers);
        v.setImageTintList(ColorStateList.valueOf(palette.accent));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Chrome.dp(getContext(), R.dimen.key_pad_h);
        // Vertical padding follows the row-height setting like the caps do.
        v.setPadding(pad, keyPaddingV, pad, keyPaddingV);
        v.setBackground(keyBg());
        v.setContentDescription(getContext().getString(R.string.extra_keys_switch_chooser_title));
        v.setClickable(true);
        v.setOnClickListener(x -> {
            int n = config.profileCount();
            if (n > 1) {
                config.setActiveIndex((config.activeIndex() + 1) % n);
                reload();
            }
        });
        v.setOnLongClickListener(x -> { showProfileChooser(); return true; });
        return v;
    }

    /** A single-choice dialog of profile names; picking one switches to it. */
    private void showProfileChooser() {
        List<ExtraKeysConfig.Profile> profiles = config.profiles();
        int n = profiles.size();
        if (n <= 1) return;
        CharSequence[] names = new CharSequence[n];
        for (int i = 0; i < n; i++) names[i] = profiles.get(i).name;
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.extra_keys_switch_chooser_title)
                .setSingleChoiceItems(names, config.activeIndex(), (d, which) -> {
                    config.setActiveIndex(which);
                    reload();
                    d.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void applyVisibility() {
        // The input box counts as an input surface of its own: it stays up with
        // the keyboard down, which is how voice input is used.
        boolean show = enabledRow && hasKeys
                && (!hideWhenKeyboardHidden || keyboardVisible || textMode);
        setVisibility(show ? VISIBLE : GONE);
    }

    private void addKey(ExtraKey key, LinearLayout row) {
        CharSequence secondaryHint = null;
        if (key.hasSecondary()) {
            ExtraKey sec = ExtraKeysConfig.resolve(getContext(), key.secondaryId);
            if (sec != null) secondaryHint = sec.label;
        }
        KeyCapView view = KeyCaps.make(getContext(), palette, key.label,
                secondaryHint, keyPaddingV);
        switch (key.kind) {
            case MODIFIER:
                modButtons.add(new ModButton(view, key.modifier));
                view.setOnClickListener(v -> {
                    setModifier(key.modifier, !modifierActive(key.modifier), false);
                    updateToggles();
                });
                view.setOnLongClickListener(v -> {
                    if (modifierActive(key.modifier)) {
                        setModifier(key.modifier, false, false);
                    } else {
                        setModifier(key.modifier, true, true);
                    }
                    updateToggles();
                    return true;
                });
                break;
            case KEY:
                wireRepeat(view,
                        () -> { if (terminal != null) terminal.dispatchKey(key.keyCode, key.mods); },
                        secondaryEmitter(key));
                break;
            case TEXT:
                wireRepeat(view,
                        () -> { if (terminal != null) terminal.dispatchText(key.text, key.mods); },
                        secondaryEmitter(key));
                break;
        }
        // Caps flex to fill the row: width 0 + weight = the key's width multiplier.
        // MATCH_PARENT height so every cap stretches to the row's tallest cap;
        // otherwise a label autosized to a smaller text size measures shorter and
        // top-aligns, leaving its keycap ragged against its neighbours.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, key.width);
        int m = Chrome.dp(getContext(), R.dimen.key_gap) / 2;
        lp.setMargins(m, m, m, m);
        row.addView(view, lp);
    }

    /** A runnable that emits {@code key}'s secondary, or null when it has none. */
    private Runnable secondaryEmitter(ExtraKey key) {
        if (!key.hasSecondary()) return null;
        ExtraKey sec = ExtraKeysConfig.resolve(getContext(), key.secondaryId);
        if (sec == null) return null;
        return () -> {
            if (terminal == null) return;
            if (sec.kind == ExtraKey.Kind.KEY) terminal.dispatchKey(sec.keyCode, sec.mods);
            else if (sec.kind == ExtraKey.Kind.TEXT) terminal.dispatchText(sec.text, sec.mods);
        };
    }

    /**
     * Wires tap/long-press-repeat and (when {@code emitSecondary != null}) a
     * swipe-up that emits the secondary instead of the primary. The three
     * gestures are disjoint: an upward drag past {@link #swipeThresholdPx}
     * consumes the touch so neither the tap nor the repeat fires.
     */
    private void wireRepeat(KeyCapView view, Runnable emit, Runnable emitSecondary) {
        final long longPressMs = ViewConfiguration.getLongPressTimeout();
        final boolean[] longPressed = {false};
        final boolean[] consumed = {false};   // swipe-up already emitted the secondary
        final float[] downY = {0f};
        final Runnable[] longPressRun = {null};
        final Runnable[] repeatRun = {null};
        // The touch listener below consumes every event, so onTouchEvent — and
        // with it performClick() — never runs for a tap. That leaves a screen
        // reader's "activate", and a D-pad/Enter press, with nothing to call:
        // this is that path, and it cannot double-emit with the tap above.
        view.setOnClickListener(v -> emit.run());
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    // The keyboard-style tick every soft keyboard gives on
                    // press-down (system haptic settings still gate it).
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    longPressed[0] = false;
                    consumed[0] = false;
                    downY[0] = event.getY();
                    longPressRun[0] = () -> {
                        longPressed[0] = true;
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        emit.run();
                        repeatRun[0] = new Runnable() {
                            @Override
                            public void run() {
                                emit.run();
                                view.postDelayed(this, REPEAT_INTERVAL_MS);
                            }
                        };
                        view.postDelayed(repeatRun[0], REPEAT_INTERVAL_MS);
                    };
                    view.postDelayed(longPressRun[0], longPressMs);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!consumed[0] && !longPressed[0] && emitSecondary != null
                            && (downY[0] - event.getY()) > swipeThresholdPx) {
                        consumed[0] = true;
                        view.setPressed(false);
                        cancelRepeat(view, longPressRun, repeatRun);
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        emitSecondary.run();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    cancelRepeat(view, longPressRun, repeatRun);
                    if (!longPressed[0] && !consumed[0]) emit.run();
                    longPressed[0] = false;
                    consumed[0] = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    cancelRepeat(view, longPressRun, repeatRun);
                    longPressed[0] = false;
                    consumed[0] = false;
                    break;
            }
            return true;
        });
    }

    private static void cancelRepeat(KeyCapView view, Runnable[] longPressRun, Runnable[] repeatRun) {
        if (longPressRun[0] != null) view.removeCallbacks(longPressRun[0]);
        if (repeatRun[0] != null) {
            view.removeCallbacks(repeatRun[0]);
            repeatRun[0] = null;
        }
    }

    private void setModifier(int modifier, boolean active, boolean locked) {
        if (modifier == TerminalNative.MOD_CTRL) { sticky.ctrl = active; sticky.ctrlLocked = locked; }
        else if (modifier == TerminalNative.MOD_ALT) { sticky.alt = active; sticky.altLocked = locked; }
    }

    private boolean modifierActive(int modifier) {
        if (modifier == TerminalNative.MOD_CTRL) return sticky.ctrl;
        if (modifier == TerminalNative.MOD_ALT) return sticky.alt;
        return false;
    }

    private boolean modifierLocked(int modifier) {
        if (modifier == TerminalNative.MOD_CTRL) return sticky.ctrlLocked;
        if (modifier == TerminalNative.MOD_ALT) return sticky.altLocked;
        return false;
    }

    private void updateToggles() {
        for (ModButton b : modButtons) {
            boolean active = modifierActive(b.modifier);
            boolean locked = modifierLocked(b.modifier);
            b.view.setBackground(!active ? keyBg() : locked ? keyBgLocked() : keyBgActive());
        }
    }

    // Rounded keycaps drawn from the chrome palette: an immediate pressed-fill
    // swap with the app-wide accent ripple layered on top. The idle cap
    // carries a hairline border; the accent (active) and deep-accent (locked)
    // states drop it so the fill reads as the whole key.
    private Drawable keyBg() {
        return palette.pressRipple(palette.surface2, palette.surface4,
                keyRadius(), palette.border);
    }

    private Drawable keyBgActive() {
        return palette.pressRipple(palette.accent, palette.accentPressed,
                keyRadius(), 0);
    }

    private Drawable keyBgLocked() {
        return palette.pressRipple(palette.accentDeep, palette.accent,
                keyRadius(), 0);
    }

    private float keyRadius() {
        return Chrome.dimen(getContext(), R.dimen.key_radius);
    }
}
