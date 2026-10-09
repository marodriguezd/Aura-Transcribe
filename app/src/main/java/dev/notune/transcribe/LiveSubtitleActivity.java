package dev.notune.transcribe;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live-subtitle start screen (fork addition). The user picks the translation
 * target here — "Auto (original language)" keeps the spoken language, a fixed
 * tag arms the on-device translator at the next session — and then starts the
 * captioning flow (overlay permission → screen-capture consent → service).
 * Keeping the selector on this screen leaves MainActivity free of
 * subtitle-specific settings.
 */
public class LiveSubtitleActivity extends AppCompatActivity {
    private static final String TAG = "LiveSubtitleActivity";
    private MediaProjectionManager mProjectionManager;
    private boolean mWaitingForOverlayPermission = false;
    private boolean mProjectionStarted = false;

    private final ActivityResultLauncher<Intent> projectionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    Toast.makeText(this, getString(R.string.subs_screen_capture_denied), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }

                Intent serviceIntent = new Intent(this, LiveSubtitleService.class);
                serviceIntent.setAction(LiveSubtitleService.ACTION_START);
                serviceIntent.putExtra("code", result.getResultCode());
                serviceIntent.putExtra("data", result.getData());

                startForegroundService(serviceIntent);
                finish();
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_subtitle);

        View rootView = findViewById(R.id.live_subtitle_root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        setupSubtitleTranslationSpinner(findViewById(R.id.spinner_subtitle_translation));
        findViewById(R.id.btn_live_subs_start).setOnClickListener(v -> beginSubtitleFlow());
    }

    /** Entry of the permission flow: overlay permission first, then projection. */
    private void beginSubtitleFlow() {
        if (Settings.canDrawOverlays(this)) {
            startProjection();
        } else {
            mWaitingForOverlayPermission = true;
            openOverlaySettings();
        }
    }

    private void openOverlaySettings() {
        Toast.makeText(this, getString(R.string.subs_grant_overlay_permission), Toast.LENGTH_LONG).show();

        try {
            // Use the specific app overlay settings page
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open overlay settings", e);
            // Fallback: open app settings
            try {
                Intent appSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                appSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appSettings);
                Toast.makeText(this, getString(R.string.subs_enable_overlay_app_settings), Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Log.e(TAG, "Failed to open app settings", e2);
                Toast.makeText(this, getString(R.string.subs_enable_overlay_settings), Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Returning from the overlay-permission settings page. If it was
        // granted, continue straight into the screen-capture consent; if not,
        // stay on the start screen so the user can retry or adjust the
        // translation target (unlike the old transient flow, this screen is
        // the configuration point for a session).
        if (mWaitingForOverlayPermission) {
            mWaitingForOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                startProjection();
            } else {
                Toast.makeText(this, getString(R.string.subs_overlay_required), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startProjection() {
        if (mProjectionStarted) {
            return;
        }
        mProjectionStarted = true;
        projectionLauncher.launch(mProjectionManager.createScreenCaptureIntent());
    }

    /**
     * Live-subtitle translation target (fork addition). "Auto (original
     * language)" keeps the spoken language (the product decision for this
     * feature); a fixed target translates finalized subtitles on-device.
     * Display names come from {@link Locale#getDisplayName()}, so they follow
     * the device language without needing translations — same pattern as the
     * Models screen's language dropdown.
     */
    private void setupSubtitleTranslationSpinner(Spinner spinner) {
        List<String> tags = new ArrayList<>(SubtitleTranslationTargets.TAGS);
        List<String> labels = new ArrayList<>(tags.size());
        for (String tag : tags) {
            labels.add(SubtitleTranslationTargets.AUTO.equals(tag)
                    ? getString(R.string.subtitle_translation_auto)
                    : Locale.forLanguageTag(tag).getDisplayName() + " (" + tag + ")");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String stored = SubtitlePrefs.getTranslationTarget(this);
        spinner.setSelection(Math.max(0, tags.indexOf(stored)), false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String tag = tags.get(position);
                if (tag.equals(SubtitlePrefs.getTranslationTarget(LiveSubtitleActivity.this))) return;
                SubtitlePrefs.setTranslationTarget(LiveSubtitleActivity.this, tag);
                Snackbar.make(findViewById(android.R.id.content),
                        getString(R.string.subtitle_translation_saved), Snackbar.LENGTH_LONG).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }
}
