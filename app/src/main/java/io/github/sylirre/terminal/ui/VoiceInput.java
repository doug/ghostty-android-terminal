/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

package io.github.sylirre.terminal.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

import io.github.sylirre.terminal.R;

/**
 * Voice input through the platform's <em>on-device</em> speech recognizer, so
 * the input box can be dictated into with the soft keyboard hidden.
 *
 * On-device only, by design: {@link SpeechRecognizer#createOnDeviceSpeechRecognizer}
 * keeps the audio on the phone. There is no fallback to the default (network)
 * recognizer; where on-device recognition is unavailable (API &lt; 31, or no
 * on-device service) this reports an error instead. When the language model
 * is missing it asks the system to download it (API 33+).
 *
 * Owns the RECORD_AUDIO runtime request: {@link #start} asks for it the first
 * time and resumes once {@link #onPermissionResult} reports the answer. All
 * calls must be on the main thread, as SpeechRecognizer requires.
 */
final class VoiceInput {

    interface Listener {
        /** Listening started or ended (any reason). */
        void onListeningChanged(boolean listening);
        /** The best transcript so far, while the user is still speaking. */
        void onPartial(String text);
        /** The final transcript (possibly empty). */
        void onFinal(String text);
        /** A user-facing reason recognition stopped without a result. */
        void onError(String message);
    }

    static final int REQ_RECORD_AUDIO = 120;

    private final Activity activity;
    private Listener listener;
    private SpeechRecognizer recognizer;
    private boolean listening;
    private boolean startAfterPermission;

    VoiceInput(Activity activity) {
        this.activity = activity;
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isListening() {
        return listening;
    }

    /** Starts listening, asking for the microphone permission first if needed. */
    void start() {
        if (listening) return;
        if (Build.VERSION.SDK_INT < 31) {
            error(R.string.voice_error_needs_android_12);
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO},
                    REQ_RECORD_AUDIO);
            return;
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
            error(R.string.voice_error_no_on_device);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity);
            recognizer.setRecognitionListener(recognitionListener);
        }
        recognizer.startListening(recognizeIntent());
        setListening(true);
    }

    /** Stops listening early; the final transcript of what was heard still arrives. */
    void stop() {
        if (recognizer != null && listening) recognizer.stopListening();
    }

    /**
     * Abandons the current attempt with no result (e.g. the app is
     * backgrounded). A start waiting on the permission dialog survives it: that
     * dialog itself pauses the Activity.
     */
    void cancel() {
        if (recognizer != null && listening) recognizer.cancel();
        setListening(false);
    }

    /** Forwarded from the Activity's onRequestPermissionsResult. */
    void onPermissionResult(boolean granted) {
        boolean wanted = startAfterPermission;
        startAfterPermission = false;
        if (!wanted) return;
        if (granted) start();
        else error(R.string.voice_error_permission);
    }

    void destroy() {
        startAfterPermission = false;
        cancel();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private static Intent recognizeIntent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    private void setListening(boolean on) {
        if (listening == on) return;
        listening = on;
        if (listener != null) listener.onListeningChanged(on);
    }

    private void error(int messageRes) {
        if (listener != null) listener.onError(activity.getString(messageRes));
    }

    private static String best(Bundle results) {
        if (results == null) return "";
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0);
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = best(partialResults);
            if (listener != null && !text.isEmpty()) listener.onPartial(text);
        }

        @Override
        public void onResults(Bundle results) {
            setListening(false);
            if (listener != null) listener.onFinal(best(results));
        }

        @Override
        public void onError(int code) {
            setListening(false);
            switch (code) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    error(R.string.voice_error_no_match);
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    error(R.string.voice_error_permission);
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    if (Build.VERSION.SDK_INT >= 33 && recognizer != null) {
                        recognizer.triggerModelDownload(recognizeIntent());
                        error(R.string.voice_error_downloading_model);
                    } else {
                        error(R.string.voice_error_no_on_device);
                    }
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    // Recreate on the next start; a wedged instance stays wedged.
                    if (recognizer != null) {
                        recognizer.destroy();
                        recognizer = null;
                    }
                    error(R.string.voice_error_busy);
                    break;
                default:
                    if (listener != null) {
                        listener.onError(activity.getString(R.string.voice_error_generic, code));
                    }
                    break;
            }
        }
    };
}
