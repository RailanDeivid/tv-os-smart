package dev.dhruv.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Live view of the home cameras over RTSP. The dual-lens Yoosee cameras send both lenses in one
 * picture (stacked, top and bottom), so each is cut in two and shown split screen like in the Yoosee
 * app. The Tapo is shown full screen. Left/Right switch camera, Menu edits the account, Back leaves.
 * After the last camera comes the mosaic: every camera and lens at once, whatever the brand.
 */
public class CameraActivity extends Activity {
    private static final String TAG = "Camera";

    /** A camera on the home network that speaks RTSP. */
    private static final class Cam {
        final String key, name, host, path, subPath, defaultUser, topLens, bottomLens;

        /**
         * @param key     stays the same if the order changes: the saved account is stored under it
         * @param topLens null for a single-lens camera; otherwise the names of the two stacked lenses
         */
        Cam(String key, String name, String host, String path, String subPath, String defaultUser, String topLens, String bottomLens) {
            this.key = key;
            this.name = name;
            this.host = host;
            this.path = path;
            this.subPath = subPath;
            this.defaultUser = defaultUser;
            this.topLens = topLens;
            this.bottomLens = bottomLens;
        }

        boolean dual() {
            return topLens != null;
        }
    }

    private Cam[] cams = new Cam[0];
    private static final String K_INDEX = "cam_index";
    private int current;

    private SharedPreferences prefs;
    private LibVLC libVlc;
    private final List<MediaPlayer> players = new ArrayList<>();
    private TextureView snapshotView;      // the stacked picture of the dual camera on screen
    private boolean pictureShown;          // the live picture has appeared (safe to take a snapshot)
    private int snapshotCam = -1;
    private FrameLayout stage;
    private TextView status;
    private TextView label;
    private TextView setupTitle;
    private TextView setupHint;
    private LinearLayout setup;
    private EditText userField;
    private EditText passField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        cams = parseCameras(getString(R.string.cameras_config));
        if (cams.length == 0) {
            finish();   // no cameras configured
            return;
        }
        current = Math.max(0, Math.min(cams.length, prefs.getInt(K_INDEX, 0)));   // cams.length is the mosaic

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = text("", 18, 0xFFFFFFFF);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(40), dp(16), dp(40), dp(16));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusLp.bottomMargin = dp(24);
        root.addView(status, statusLp);

        label = text("", 14, 0xCCFFFFFF);
        label.setPadding(dp(40), dp(18), dp(40), dp(8));
        root.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        buildSetup(root);
        setContentView(root);
    }

    /**
     * personal.properties "cameras": key|name|host|path|default user|lenses|mosaic path, separated by semicolons.
     * "lenses" is empty for a normal camera, or "Name 1,Name 2" for a dual-lens one whose single
     * picture holds both lenses stacked (top, bottom). "mosaic path" is an optional lighter stream used in the
     * mosaic (empty: same as path). Passwords are never stored here: they are typed on the TV.
     */
    private static Cam[] parseCameras(String config) {
        List<Cam> out = new ArrayList<>();
        for (String entry : config.split(";")) {
            String[] f = entry.trim().split("\\|", -1);
            if (f.length < 4) continue;
            String user = f.length > 4 ? f[4].trim() : "";
            String[] lenses = f.length > 5 && !f[5].trim().isEmpty() ? f[5].split(",") : null;
            String sub = f.length > 6 ? f[6].trim() : "";
            out.add(new Cam(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim(), sub.isEmpty() ? f[3].trim() : sub, user,
                    lenses != null ? lenses[0].trim() : null, lenses != null && lenses.length > 1 ? lenses[1].trim() : null));
        }
        return out.toArray(new Cam[0]);
    }

    // ---- account form ---------------------------------------------------------------------------

    private void buildSetup(FrameLayout root) {
        setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setBackgroundColor(0xF2101012);
        setup.setGravity(Gravity.CENTER);
        setup.setPadding(dp(200), dp(40), dp(200), dp(40));
        setup.setVisibility(View.GONE);

        setupTitle = text("", 24, 0xFFFFFFFF);
        setup.addView(setupTitle);
        setupHint = text("", 14, 0xA6FFFFFF);
        setupHint.setPadding(0, dp(6), 0, dp(20));
        setup.addView(setupHint);

        userField = field(getString(R.string.cam_user), false);
        passField = field(getString(R.string.cam_pass), true);
        setup.addView(userField);
        setup.addView(passField);

        Button save = new Button(this);
        save.setText(R.string.cam_save);
        save.setOnClickListener(v -> {
            String u = userField.getText().toString().trim();
            String p = passField.getText().toString();
            if (u.isEmpty() || p.isEmpty()) return;
            prefs.edit().putString(userKey(current), u).putString(passKey(current), p).apply();
            setup.setVisibility(View.GONE);
            start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        setup.addView(save, lp);
        root.addView(setup, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private EditText field(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x80FFFFFF);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean isMosaic() {
        return current == cams.length;
    }

    // ---- accounts -------------------------------------------------------------------------------

    private String userKey(int i) {
        return "cam_user_" + cams[i].key;
    }

    private String passKey(int i) {
        return "cam_pass_" + cams[i].key;
    }

    /** The saved user. A Yoosee camera's RTSP user is "admin"; an e-mail there is the app login, not the RTSP user. */
    private String userFor(int i) {
        String saved = prefs.getString(userKey(i), "");
        if (cams[i].dual() && (saved.isEmpty() || saved.contains("@"))) return cams[i].defaultUser;
        return saved;
    }

    private boolean hasAccount(int i) {
        return !userFor(i).isEmpty() && !prefs.getString(passKey(i), "").isEmpty();
    }

    // ---- flow -----------------------------------------------------------------------------------

    private boolean foreground;   // on screen and in front, as opposed to being brought forward

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override
    protected void onPause() {
        foreground = false;
        super.onPause();
    }

    /**
     * The cameras shortcut sends the viewer a new intent. When it was already in front, that was the same
     * button pressed again, which edits the account of the camera on screen (what Menu does inside it).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (foreground && cams.length > 0 && !isMosaic() && setup.getVisibility() != View.VISIBLE) showSetup();
    }

    @Override
    protected void onStart() {
        super.onStart();
        showCurrent();
    }

    @Override
    protected void onStop() {
        super.onStop();
        release();
    }

    /** Plays the selected camera, or asks for its account when there is none yet. */
    private void showCurrent() {
        if (cams.length == 0) return;
        prefs.edit().putInt(K_INDEX, current).apply();
        if (isMosaic()) {
            label.setText(getString(R.string.cam_mosaic) + "  " + (current + 1) + "/" + (cams.length + 1)
                    + "     " + getString(R.string.cam_hint_mosaic));
            setup.setVisibility(View.GONE);
            start();
            return;
        }
        Cam cam = cams[current];
        label.setText(cam.name + "  " + (current + 1) + "/" + (cams.length + 1) + "     " + getString(R.string.cam_hint));
        if (hasAccount(current)) {
            setup.setVisibility(View.GONE);
            start();
        } else {
            showSetup();
        }
    }

    private void switchCamera(int step) {
        current = (current + step + cams.length + 1) % (cams.length + 1);
        showCurrent();
    }

    private void showSetup() {
        release();
        Cam cam = cams[current];
        setupTitle.setText(getString(R.string.cam_setup_title) + " · " + cam.name);
        setupHint.setText(cam.dual() ? R.string.cam_setup_hint_yoosee : R.string.cam_setup_hint_tapo);
        String saved = userFor(current);
        userField.setText(saved.isEmpty() ? cam.defaultUser : saved);
        passField.setText("");
        status.setText("");
        setup.setVisibility(View.VISIBLE);
        userField.requestFocus();
    }

    // ---- playback -------------------------------------------------------------------------------

    private void start() {
        release();
        if (isMosaic()) {
            startMosaic();
            return;
        }
        if (!hasAccount(current)) {
            showSetup();
            return;
        }
        Cam cam = cams[current];
        String user = userFor(current);
        String pass = prefs.getString(passKey(current), "");
        Uri uri = Uri.parse("rtsp://" + cam.host + ":554" + cam.path);   // the account is passed as options, not in the address
        Log.i(TAG, "opening " + cam.name + " " + uri + " userLen=" + user.length() + " passLen=" + pass.length());
        status.setText(R.string.cam_connecting);

        ArrayList<String> options = new ArrayList<>();
        options.add("--rtsp-tcp");   // video over the same connection: more reliable on home Wi-Fi
        options.add("--network-caching=250");
        libVlc = new LibVLC(this, options);

        stage.removeAllViews();
        pictureShown = false;
        snapshotView = null;
        snapshotCam = -1;
        if (cam.dual()) {
            snapshotCam = current;
            // Two tiles side by side, each showing one half of the stacked picture (top lens / bottom lens).
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int gap = dp(16);
            int tileW = (screenW - 3 * gap) / 2;
            int tileH = tileW * 9 / 16;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            stage.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Bitmap last = loadSnapshot(current);   // the last picture seen, shown while the live one connects
            addTile(row, tileW, tileH, false, cam.topLens, uri, user, pass, gap, last);
            addTile(row, tileW, tileH, true, cam.bottomLens, uri, user, pass, 0, last);
        } else {
            TextureView view = new TextureView(this);
            stage.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            play(view, uri, user, pass, 0, 0, null);
        }
    }

    /** One lens of a dual camera in the single-camera view. */
    private void addTile(LinearLayout row, int tileW, int tileH, boolean bottomHalf, String name,
                         Uri uri, String user, String pass, int rightGap, Bitmap last) {
        FrameLayout tile = makeTile(tileW, tileH, bottomHalf ? HALF_BOTTOM : HALF_TOP, name, uri, user, pass, last, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
        lp.setMarginEnd(rightGap);
        row.addView(tile, lp);
    }

    private static final int FULL = 0, HALF_TOP = 1, HALF_BOTTOM = 2;

    /**
     * A clipped box with a live picture and a name tag. FULL shows the whole video; HALF_TOP / HALF_BOTTOM
     * show one half of a stacked dual-lens video (640x720 is exactly 8:9, like tileW x 2*tileH).
     */
    private FrameLayout makeTile(int tileW, int tileH, int part, String name, Uri uri, String user, String pass,
                                 Bitmap last, boolean trackSnapshot) {
        int viewH = part == FULL ? tileH : tileH * 2;
        float shift = part == HALF_BOTTOM ? -tileH : 0;
        FrameLayout tile = new FrameLayout(this);
        tile.setClipChildren(true);
        ImageView placeholder = null;
        if (last != null && part != FULL) {
            placeholder = new ImageView(this);
            placeholder.setScaleType(ImageView.ScaleType.FIT_XY);
            placeholder.setImageBitmap(last);
            placeholder.setAlpha(0.55f);   // dimmed so it does not pass for the live picture
            tile.addView(placeholder, new FrameLayout.LayoutParams(tileW, viewH));
            placeholder.setTranslationY(shift);
        }
        TextureView view = new TextureView(this);
        tile.addView(view, new FrameLayout.LayoutParams(tileW, viewH));
        view.setTranslationY(shift);
        if (trackSnapshot && snapshotView == null) snapshotView = view;
        TextView tag = text(name, 14, 0xFFFFFFFF);
        tag.setBackgroundColor(0x99000000);
        tag.setPadding(dp(10), dp(4), dp(10), dp(4));
        tile.addView(tag, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        play(view, uri, user, pass, tileW, viewH, placeholder);
        return tile;
    }

    /** One tile of the mosaic: which camera, which part of its video, and its caption. */
    private static final class Spec {
        final int cam, part;
        final String name;

        Spec(int cam, int part, String name) {
            this.cam = cam;
            this.part = part;
            this.name = name;
        }
    }

    /** Every camera and every lens at once, in a grid, whatever the brand. Cameras without an account are skipped. */
    private void startMosaic() {
        List<Spec> specs = new ArrayList<>();
        for (int i = 0; i < cams.length; i++) {
            if (!hasAccount(i)) continue;
            Cam c = cams[i];
            if (c.dual()) {
                specs.add(new Spec(i, HALF_TOP, c.name + " · " + c.topLens));
                specs.add(new Spec(i, HALF_BOTTOM, c.name + " · " + c.bottomLens));
            } else {
                specs.add(new Spec(i, FULL, c.name));
            }
        }
        pictureShown = false;
        snapshotView = null;
        snapshotCam = -1;
        stage.removeAllViews();
        if (specs.isEmpty()) {
            status.setText(R.string.cam_mosaic_empty);
            return;
        }
        status.setText(R.string.cam_connecting);

        ArrayList<String> options = new ArrayList<>();
        options.add("--rtsp-tcp");
        options.add("--network-caching=300");
        options.add("--no-audio");   // six cameras talking at once would be noise: the mosaic is silent
        libVlc = new LibVLC(this, options);

        int n = specs.size();
        int cols = n <= 1 ? 1 : n <= 4 ? 2 : n <= 9 ? 3 : 4;
        int rows = (n + cols - 1) / cols;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int gap = dp(12);
        int top = dp(44);                                   // room for the caption line
        int availH = screenH - top - dp(16);
        int tileW = (screenW - (cols + 1) * gap) / cols;
        int tileH = tileW * 9 / 16;
        if (rows * tileH + (rows - 1) * gap > availH) {      // too tall: size by height instead
            tileH = (availH - (rows - 1) * gap) / rows;
            tileW = tileH * 16 / 9;
        }
        int gridW = cols * tileW + (cols - 1) * gap;
        int gridH = rows * tileH + (rows - 1) * gap;
        int startX = (screenW - gridW) / 2;
        int startY = top + (availH - gridH) / 2;

        for (int j = 0; j < n; j++) {
            Spec sp = specs.get(j);
            Cam c = cams[sp.cam];
            Uri uri = Uri.parse("rtsp://" + c.host + ":554" + c.subPath);
            Bitmap last = sp.part == FULL ? null : loadSnapshot(sp.cam);
            FrameLayout tile = makeTile(tileW, tileH, sp.part, sp.name, uri, userFor(sp.cam),
                    prefs.getString(passKey(sp.cam), ""), last, false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(tileW, tileH, Gravity.TOP | Gravity.START);
            lp.leftMargin = startX + (j % cols) * (tileW + gap);
            lp.topMargin = startY + (j / cols) * (tileH + gap);
            stage.addView(tile, lp);
            Log.i(TAG, "mosaic tile " + sp.name + " " + uri);
        }
    }

    private void play(TextureView view, Uri uri, String user, String pass, int w, int h, ImageView placeholder) {
        MediaPlayer mp = new MediaPlayer(libVlc);
        if (w > 0) mp.getVLCVout().setWindowSize(w, h);
        mp.getVLCVout().setVideoView(view);
        mp.getVLCVout().attachViews();
        Media media = new Media(libVlc, uri);
        media.addOption(":rtsp-user=" + user);
        media.addOption(":rtsp-pwd=" + pass);
        mp.setMedia(media);
        media.release();
        final long t0 = android.os.SystemClock.elapsedRealtime();
        mp.setEventListener(event -> runOnUiThread(() -> {
            long ms = android.os.SystemClock.elapsedRealtime() - t0;
            switch (event.type) {
                case MediaPlayer.Event.Opening:
                    Log.i(TAG, "t+" + ms + "ms opening");
                    break;
                case MediaPlayer.Event.ESAdded:
                    Log.i(TAG, "t+" + ms + "ms stream found");
                    break;
                case MediaPlayer.Event.Vout:
                    Log.i(TAG, "t+" + ms + "ms FIRST PICTURE (vout=" + event.getVoutCount() + ")");
                    if (placeholder != null) placeholder.setVisibility(View.GONE);
                    if (event.getVoutCount() > 0) pictureShown = true;
                    break;
                case MediaPlayer.Event.Playing:
                    Log.i(TAG, "t+" + ms + "ms playing");
                    status.setText("");
                    break;
                case MediaPlayer.Event.EncounteredError:
                    Log.w(TAG, "playback error");
                    status.setText(R.string.cam_error);
                    break;
                default:
                    break;
            }
        }));
        players.add(mp);
        mp.play();
    }

    private void release() {
        saveSnapshot();
        for (MediaPlayer mp : players) {
            mp.setEventListener(null);
            mp.stop();
            mp.getVLCVout().detachViews();
            mp.release();
        }
        players.clear();
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        stage.removeAllViews();
    }

    // ---- last picture of each dual camera, shown while the live one connects -------------------------

    private File snapshotFile(int camIndex) {
        return new File(getFilesDir(), "snap_" + cams[camIndex].key + ".jpg");
    }

    private Bitmap loadSnapshot(int camIndex) {
        File f = snapshotFile(camIndex);
        return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
    }

    private void saveSnapshot() {
        if (snapshotView == null || !pictureShown || snapshotCam < 0) return;
        try {
            Bitmap b = snapshotView.getBitmap();
            if (b == null) return;
            try (FileOutputStream out = new FileOutputStream(snapshotFile(snapshotCam))) {
                b.compress(Bitmap.CompressFormat.JPEG, 70, out);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not save snapshot: " + e);
        } finally {
            snapshotView = null;
            pictureShown = false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && setup.getVisibility() != View.VISIBLE) {
            if (!isMosaic()) showSetup();   // accounts are edited on each camera
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && setup.getVisibility() == View.VISIBLE && hasAccount(current)) {
            setup.setVisibility(View.GONE);
            start();
            return true;
        }
        if (setup.getVisibility() != View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                switchCamera(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                switchCamera(-1);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
