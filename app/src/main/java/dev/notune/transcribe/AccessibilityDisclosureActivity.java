package dev.notune.transcribe;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/**
 * Prominent, in-context disclosure for the optional Accessibility service.
 *
 * <h3>Why this screen exists</h3>
 * The floating-dictation bubble can type the transcript straight into the field
 * the user was editing, which requires Android's Accessibility access — a
 * powerful capability. Google Play requires apps that request it to show a
 * <em>prominent disclosure</em> describing what is accessed, why, that it is
 * optional, and that consent is explicit, and it must not be buried in a
 * settings list, a privacy policy or the store listing. This Activity is that
 * disclosure: it is a separate screen, it is shown at the exact moment the user
 * asks for the feature, and it is the only path to the system settings.
 *
 * <h3>What this screen does NOT do</h3>
 * It never enables the service on the user's behalf and never toggles any system
 * setting: pressing Continue only opens Android's own Accessibility settings,
 * where the user grants or withholds access themselves. Declining simply returns
 * to the app. Dictation keeps working either way — without the service the
 * transcript is copied to the clipboard instead.
 */
public class AccessibilityDisclosureActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_disclosure);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.a11y_disclosure_root), (v, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(
                            WindowInsetsCompat.Type.systemBars()
                                    | WindowInsetsCompat.Type.displayCutout());
                    v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                    return windowInsets;
                });

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        MaterialButton continueButton = findViewById(R.id.btn_disclosure_continue);
        if (continueButton != null) {
            continueButton.setOnClickListener(v -> openAccessibilitySettings());
        }

        MaterialButton declineButton = findViewById(R.id.btn_disclosure_decline);
        if (declineButton != null) {
            declineButton.setOnClickListener(v -> finish());
        }
    }

    /**
     * Opens Android's Accessibility settings so the user can enable the service
     * themselves. If the settings screen is unavailable on this build, falls back
     * to the app's own settings page rather than crashing.
     */
    private void openAccessibilitySettings() {
        boolean opened = false;
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            opened = true;
        } catch (android.content.ActivityNotFoundException e) {
            // Nothing to do here — handled below.
        }
        if (!opened) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(this, R.string.a11y_disclosure_no_settings,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
