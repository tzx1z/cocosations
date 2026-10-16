package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.common.io.Files;
import eu.siacs.conversations.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Лог приложения за время звонка: logcat своего процесса пишется в файл с ротацией, после звонка
 * части склеиваются в один файл рядом с отчетом. Приложению доступны только собственные записи.
 */
public class CallLogCapture {

    private static final String DIRECTORY = "cocosations_call_logs";
    // logcat переименовывает file в file.1, file.1 в file.2 и так далее: при долгом звонке
    // сохраняются последние ROTATE_COUNT + 1 частей, то есть конец звонка
    private static final int ROTATE_KILOBYTES = 1024;
    private static final int ROTATE_COUNT = 4;
    private static final long STALE_MILLIS = TimeUnit.DAYS.toMillis(1);

    private final File file;
    private final Process process;

    CallLogCapture(final File file, final Process process) {
        this.file = file;
        this.process = process;
    }

    @Nullable
    public static CallLogCapture start(final Context context) {
        final File directory = new File(context.getCacheDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(Config.LOGTAG, "unable to create " + directory);
            return null;
        }
        dropStale(directory);
        final File file = new File(directory, UUID.randomUUID() + ".log");
        final List<String> command = new ArrayList<>();
        command.add("logcat");
        command.add("-v");
        command.add("threadtime");
        // без -T logcat сначала выводит весь накопленный буфер
        command.add("-T");
        command.add(
                new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
                        .format(new Date(System.currentTimeMillis())));
        command.add("-f");
        command.add(file.getAbsolutePath());
        command.add("-r");
        command.add(String.valueOf(ROTATE_KILOBYTES));
        command.add("-n");
        command.add(String.valueOf(ROTATE_COUNT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            command.add("--pid=" + android.os.Process.myPid());
        }
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return new CallLogCapture(file, process);
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "unable to start logcat", e);
            return null;
        }
    }

    /** Останавливает logcat и склеивает части в target; без target части удаляются. */
    public void finish(@Nullable final File target) {
        process.destroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        final List<File> parts = new ArrayList<>();
        for (int i = ROTATE_COUNT; i > 0; i--) {
            parts.add(new File(file.getPath() + "." + i));
        }
        parts.add(file);
        if (target != null) {
            try (final OutputStream output = new FileOutputStream(target)) {
                for (final File part : parts) {
                    if (part.isFile()) {
                        Files.copy(part, output);
                    }
                }
            } catch (final IOException e) {
                Log.w(Config.LOGTAG, "unable to write call log", e);
                if (target.exists() && !target.delete()) {
                    Log.w(Config.LOGTAG, "unable to delete " + target);
                }
            }
        }
        for (final File part : parts) {
            if (part.exists() && !part.delete()) {
                Log.w(Config.LOGTAG, "unable to delete " + part);
            }
        }
    }

    // части звонков, прерванных остановкой процесса, никто уже не склеит
    private static void dropStale(final File directory) {
        final File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        final long threshold = System.currentTimeMillis() - STALE_MILLIS;
        for (final File file : files) {
            if (file.lastModified() < threshold && !file.delete()) {
                Log.w(Config.LOGTAG, "unable to delete " + file);
            }
        }
    }
}
