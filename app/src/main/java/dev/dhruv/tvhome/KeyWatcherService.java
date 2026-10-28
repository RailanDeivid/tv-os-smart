package dev.dhruv.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote-button shortcuts that work in every app: one button opens the cameras, another goes to the
 * launcher home. Which buttons is set in personal.properties (key_cameras, key_home). It reacts only to
 * those buttons and consumes only those; every other key passes through untouched, and no key is logged
 * or stored.
 *
 * It needs the Android accessibility service switch (Settings, Accessibility), because that is the only
 * way for an app to see keys while another app is on screen. A service like this could read every key,
 * so this one is deliberately tiny: read it before you turn it on.
 *
 * A button is written as "KEYCODE_NAME", a number, or "scan:NNN" (matched by the key's hardware scan code, which
 * does not depend on how Android names it). Several buttons can be listed, separated by commas. Put "long:" in
 * front of one to react to holding it instead of pressing it: a short press then still reaches the app on screen.
 */
public class KeyWatcherService extends AccessibilityService {
    private static final String TAG = "KeyWatch";
    private static final String LONG = "long:";

    private GoalAlerts goalAlerts;

    /** Goal cards over any app live here too, because this is the part of the app that stays alive behind other apps. */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (goalAlerts != null) goalAlerts.stop();
        if ("off".equalsIgnoreCase(getString(R.string.goal_alerts).trim())) return;
        goalAlerts = new GoalAlerts(this);
        goalAlerts.start();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopGoalAlerts();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopGoalAlerts();
        super.onDestroy();
    }

    private void stopGoalAlerts() {
        if (goalAlerts == null) return;
        goalAlerts.stop();
        goalAlerts = null;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        String cameras = getString(R.string.key_cameras).trim();
        String home = getString(R.string.key_home).trim();
        String cameraButton = matching(cameras, event);
        String homeButton = cameraButton == null ? matching(home, event) : null;
        if (cameraButton == null && homeButton == null) return false;

        boolean toCameras = cameraButton != null;
        boolean held = (toCameras ? cameraButton : homeButton).startsWith(LONG);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int repeats = event.getRepeatCount();
        if (held) {
            // A long press fires once, on the first auto-repeat. The first press goes through to the app.
            if (down && repeats == 1) open(toCameras);
            return down && repeats >= 1;
        }
        if (down && repeats == 0) open(toCameras);
        return true;   // the button did its job; the app on screen should not also act on it
    }

    private void open(boolean cameras) {
        Intent go = cameras
                ? new Intent(this, CameraActivity.class)
                : new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(go);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open: " + e);
        }
    }

    /** The entry of a comma-separated list that matches this key, or null. */
    private static String matching(String list, KeyEvent event) {
        for (String entry : list.split(",")) {
            entry = entry.trim();
            if (matches(entry, event)) return entry;
        }
        return null;
    }

    private static boolean matches(String spec, KeyEvent event) {
        if (spec.startsWith(LONG)) spec = spec.substring(LONG.length()).trim();
        if (spec.isEmpty()) return false;
        try {
            if (spec.startsWith("scan:")) return event.getScanCode() == Integer.parseInt(spec.substring(5).trim());
            int code = KeyEvent.keyCodeFromString(spec);
            return code != KeyEvent.KEYCODE_UNKNOWN && event.getKeyCode() == code;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used: this service only filters keys.
    }

    @Override
    public void onInterrupt() {
        // Not used.
    }
}
