package com.infinifolder;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debug-only, framework-only reporter. Never starts the browser or its libraries. */
public final class StartupReportActivity extends Activity {
    private static final int SAVE_REPORT = 1;
    private static final int SAVE_NATIVE_TRACE = 2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "StartupReport");
        thread.setDaemon(true);
        return thread;
    });
    private TextView details;
    private Button refresh;
    private Button save;
    private Button copy;
    private Button nativeTrace;
    private String report = "";
    private volatile ApplicationExitInfo nativeExit;
    private ApplicationExitInfo pendingNativeExit;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            } else {
                view.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("InfiniFolder startup report");
        title.setTextSize(22);
        root.addView(title);
        details = new TextView(this);
        details.setTextIsSelectable(true);
        details.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        refresh = button(root, "Refresh report", v -> load());
        copy = button(root, "Copy report", v -> {
            boolean clipped = report.length() > 120 * 1024;
            String clipboard = clipped ? report.substring(0, 120 * 1024) +
                "\n[Clipboard limit reached. Use Save report for the complete report.]" : report;
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText("InfiniFolder startup report", clipboard));
            Toast.makeText(this, clipped ? "Beginning copied; Save report exports the full report" : "Report copied", Toast.LENGTH_LONG).show();
        });
        save = button(root, "Save report…", v -> chooseOutput(SAVE_REPORT, "text/plain", "infinifolder-startup-report.log"));
        nativeTrace = button(root, "Save native crash trace…", v -> {
            pendingNativeExit = nativeExit;
            chooseOutput(SAVE_NATIVE_TRACE, "application/octet-stream", "infinifolder-native-crash.pb");
        });
        setContentView(root);
        root.requestApplyInsets();
        load();
    }

    private Button button(LinearLayout root, String title, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(action);
        root.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private void load() {
        refresh.setEnabled(false);
        copy.setEnabled(false);
        save.setEnabled(false);
        nativeTrace.setEnabled(false);
        details.setText("Reading Android exit history and saved diagnostics…");
        worker.execute(() -> {
            StringBuilder text = new StringBuilder("Captured: " + new Date() + "\nAndroid SDK: " + Build.VERSION.SDK_INT + "\n");
            try {
                text.append("APK updated: ").append(new Date(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime)).append('\n');
                text.append("\n--- Android process exit history (newest first) ---\n");
                nativeExit = null;
                if (Build.VERSION.SDK_INT >= 30) readExits(text);
                else text.append("Android exit history requires Android 11 or later.\n");
            } catch (Exception error) {
                text.append("Cannot read Android exit history: ").append(error).append('\n');
            }
            text.append("\n--- Saved Java startup error ---\n");
            try {
                String error = getSharedPreferences("startup_recovery", MODE_PRIVATE).getString("pending_error", null);
                text.append(error == null ? "No saved startup exception.\n" : error.substring(0, Math.min(error.length(), 64 * 1024)));
            } catch (Exception error) { text.append("Cannot read saved startup error: ").append(error).append('\n'); }
            appendLog(text, "Saved startup stall", new File(getFilesDir(), "diagnostics/ui-stall-pending.log"), 128 * 1024);
            appendLog(text, "Saved session log (tail)", new File(getFilesDir(), "diagnostics/session.log"), 256 * 1024);
            final String value = text.toString();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                report = value;
                details.setText(value);
                refresh.setEnabled(true);
                copy.setEnabled(true);
                save.setEnabled(true);
                nativeTrace.setEnabled(nativeExit != null);
            });
        });
    }

    @android.annotation.TargetApi(30)
    private void readExits(StringBuilder text) throws Exception {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(getPackageName(), 0, 24);
        if (exits.isEmpty()) text.append("Android has no retained exit records for this package.\n");
        int traces = 0;
        for (ApplicationExitInfo exit : exits) {
            text.append(new Date(exit.getTimestamp())).append(" process=").append(exit.getProcessName())
                .append(" pid=").append(exit.getPid()).append(" reason=").append(reason(exit.getReason()))
                .append(" (").append(exit.getReason()).append(") status=").append(exit.getStatus())
                .append(" pssKiB=").append(exit.getPss()).append(" rssKiB=").append(exit.getRss())
                .append("\nDescription: ").append(exit.getDescription()).append('\n');
            if (exit.getProcessName() != null && exit.getProcessName().endsWith(":startup_report")) continue;
            if (Build.VERSION.SDK_INT >= 31 && exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE && nativeExit == null) nativeExit = exit;
            // Native traces are protobuf, not UTF-8. Offer an exact binary export.
            if (exit.getReason() == ApplicationExitInfo.REASON_ANR && traces < 2) {
                traces++;
                try (InputStream input = exit.getTraceInputStream()) {
                    if (input != null) {
                        byte[] bytes = readBounded(input, 256 * 1024 + 1);
                        text.append("ANR trace:\n").append(new String(bytes, 0, Math.min(bytes.length, 256 * 1024), StandardCharsets.UTF_8)).append('\n');
                        if (bytes.length > 256 * 1024) text.append("[ANR trace clipped at 256 KiB]\n");
                    }
                    else text.append("Android did not retain an ANR trace.\n");
                } catch (Exception error) { text.append("Cannot read ANR trace: ").append(error).append('\n'); }
            }
        }
    }

    private static String reason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "Native crash";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "Initialization failure";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "Low memory";
            case ApplicationExitInfo.REASON_SIGNALED: return "Signal";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "User requested / force stop";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "User stopped";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "Process exited itself";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "Dependency died";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Excessive resource use";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "Permission changed";
            case ApplicationExitInfo.REASON_OTHER: return "Other";
            default: return "Unknown reason";
        }
    }

    private static void appendLog(StringBuilder text, String title, File file, int limit) {
        text.append("\n--- ").append(title).append(" ---\n");
        if (!file.isFile()) { text.append("No saved file.\n"); return; }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            text.append("Modified: ").append(new Date(file.lastModified())).append('\n');
            long length = input.length();
            input.seek(Math.max(0, length - limit));
            byte[] bytes = new byte[(int) Math.min(length, limit)];
            input.readFully(bytes);
            text.append(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception error) { text.append("Cannot read saved file: ").append(error).append('\n'); }
    }

    private static byte[] readBounded(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (output.size() < limit) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count < 0) break;
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void chooseOutput(int request, String mime, String name) {
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime).putExtra(Intent.EXTRA_TITLE, name), request);
        } catch (Exception error) { Toast.makeText(this, "Cannot open save picker: " + error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final byte[] text = report.getBytes(StandardCharsets.UTF_8);
        final ApplicationExitInfo selected = pendingNativeExit;
        final android.net.Uri uri = data.getData();
        worker.execute(() -> {
            String message;
            try {
                byte[] bytes = text;
                if (request == SAVE_NATIVE_TRACE) {
                    if (Build.VERSION.SDK_INT < 31 || selected == null) throw new java.io.IOException("No retained native trace is available");
                    try (InputStream input = selected.getTraceInputStream()) {
                        if (input == null) throw new java.io.IOException("Android no longer retains this native trace");
                        bytes = readBounded(input, 4 * 1024 * 1024 + 1);
                        if (bytes.length > 4 * 1024 * 1024) throw new java.io.IOException("Native trace exceeds the 4 MiB export limit");
                    }
                } else if (request != SAVE_REPORT) return;
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("The selected destination cannot be opened");
                    output.write(bytes);
                    output.flush();
                }
                message = "Report saved";
            } catch (Exception error) { message = "Cannot save report: " + error.getMessage(); }
            final String status = message;
            runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this, status, Toast.LENGTH_LONG).show(); });
        });
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
