package dev.notune.transcribe;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Editor for the custom-words dictionary — a marker file in filesDir named
 * {@code custom_words}, one term per line (lines starting with {@code #} are
 * comments, blank lines ignored). The native corrector reads it on every
 * transcription (with an mtime cache) and replaces words in the transcript
 * that sound like a dictionary term but were misrecognized, preserving the
 * speaker's capitalization context.
 *
 * The file's presence and non-emptiness is the opt-in: no separate toggle.
 * Deleting all content disables correction (the corrector no-ops on an empty
 * dictionary). This follows the project's marker-file convention
 * (AGENTS.md §4.5); the native corrector reads it from the filesystem.
 */
public class CustomWordsActivity extends AppCompatActivity {

    private static final String TAG = "OfflineVoiceInput";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_words);

        View rootView = findViewById(R.id.custom_words_root);
        if (rootView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        TextInputEditText edit = findViewById(R.id.edit_words);
        edit.setText(loadWords());

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> {
            boolean saved = saveWords(edit.getText().toString());
            Toast.makeText(this, saved ? R.string.cw_saved : R.string.cw_save_error,
                    Toast.LENGTH_SHORT).show();
            // Stay on the screen when the write did not take, so the user can
            // retry instead of losing their edits to a silent failure.
            if (saved) {
                finish();
            }
        });
    }

    private String loadWords() {
        return MarkerFileHelper.readString(this, "custom_words", "");
    }

    /**
     * Writes the dictionary marker and verifies that it landed.
     *
     * <p>{@link MarkerFileHelper} has a void API and can only report a
     * best-effort write, so the value is read back and compared — the same
     * verification {@code SettingsManager} performs for its one-time API-key
     * migration. Without it this screen showed "Saved" even when the write had
     * failed, and the phonetic corrector kept using the previous word list with
     * no indication that anything was wrong.
     *
     * @return true only when the marker on disk matches what the user typed.
     */
    private boolean saveWords(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            MarkerFileHelper.delete(this, "custom_words");
            return MarkerFileHelper.readString(this, "custom_words", "").isEmpty();
        }
        MarkerFileHelper.writeString(this, "custom_words", trimmed);
        return trimmed.equals(MarkerFileHelper.readString(this, "custom_words", null));
    }
}
