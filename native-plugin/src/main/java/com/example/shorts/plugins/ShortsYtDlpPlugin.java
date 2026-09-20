package com.example.shorts.plugins;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "YtDlp")
public class ShortsYtDlpPlugin extends Plugin {
    private static final String TAG = "ShortsYtDlp";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean cancelRequested = false;

    @Override
    public void load() {
        super.load();
        try {
            invokeStaticInit();
        } catch (Throwable t) {
            Log.e(TAG, "yt-dlp init failed", t);
        }
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        try {
            findClass("YtDlp", "YoutubeDL");
            call.resolve(new JSObject().put("available", true));
        } catch (Throwable t) {
            call.resolve(new JSObject().put("available", false).put("message", t.toString()));
        }
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String url = call.getString("url", "").trim();
        final String quality = call.getString("quality", "720");
        if (url.isEmpty()) {
            call.reject("URL is empty");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            call.reject("A download is already running");
            return;
        }
        cancelRequested = false;

        executor.execute(() -> {
            try {
                invokeStaticInit();
                File dir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ShortsVideos");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create output directory");
                cleanupTempFiles(dir);

                String output = new File(dir, "%(id)s.%(ext)s").getAbsolutePath();
                Class<?> requestClass = findClass("YtDlpRequest", "YoutubeDLRequest");
                Constructor<?> ctor = requestClass.getConstructor(String.class);
                Object request = ctor.newInstance(url);

                invokeFluent(request, "setOutputTemplate", output);
                addOption(request, "--no-playlist", null);
                addOption(request, "--no-mtime", null);
                addOption(request, "--newline", null);
                addOption(request, "-f", "bestvideo[height<=" + sanitizeQuality(quality) + "][ext=mp4]+bestaudio[ext=m4a]/best[height<=" + sanitizeQuality(quality) + "][ext=mp4]/best");
                addOption(request, "--merge-output-format", "mp4");

                emitProgress(0, "بدأ yt-dlp");
                executeReflectively(request, dir, call);

                File result = findNewestVideo(dir);
                if (result == null) throw new Exception("yt-dlp finished without a video file");

                Uri publicUri = publishToMediaStore(result);

                JSObject out = new JSObject();
                out.put("success", true);
                out.put("fileName", result.getName());
                out.put("filePath", result.getAbsolutePath());
                if (publicUri != null) out.put("uri", publicUri.toString());
                out.put("engine", "youtubedl-android");
                call.resolve(out);
            } catch (Throwable t) {
                Log.e(TAG, "download failed", t);
                call.reject(messageOf(t));
            } finally {
                busy.set(false);
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelRequested = true;
        call.resolve(new JSObject().put("accepted", true));
    }

    private void executeReflectively(Object request, File dir, PluginCall call) throws Exception {
        Class<?> ytDlp = findClass("YtDlp", "YoutubeDL");

        Method async = null;
        for (Method m : ytDlp.getMethods()) {
            if (m.getName().equals("executeAsync") && m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0].isAssignableFrom(request.getClass())
                    && m.getParameterTypes()[1].isInterface()) {
                async = m;
                break;
            }
        }

        if (async != null) {
            final Object lock = new Object();
            final boolean[] callbackDone = {false};
            Class<?> callbackType = async.getParameterTypes()[1];
            Object callback = Proxy.newProxyInstance(
                    callbackType.getClassLoader(),
                    new Class[]{callbackType},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                int progress = args != null && args.length > 0 && args[0] instanceof Number
                                        ? ((Number) args[0]).intValue() : -1;
                                String line = args != null && args.length > 2 && args[2] != null
                                        ? String.valueOf(args[2]) : "";
                                emitProgress(progress, line);
                                if (progress >= 100) {
                                    synchronized (lock) {
                                        callbackDone[0] = true;
                                        lock.notifyAll();
                                    }
                                }
                            } catch (Throwable ignored) {}
                            return null;
                        }
                    });

            async.invoke(null, request, callback);
            long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30);
            synchronized (lock) {
                while (!callbackDone[0] && System.currentTimeMillis() < deadline) {
                    File newest = findNewestVideo(dir);
                    if (newest != null && newest.length() > 1024 && isStable(newest)) break;
                    lock.wait(500L);
                }
            }
            Thread.sleep(700L);
            return;
        }

        for (Method m : ytDlp.getMethods()) {
            if (m.getName().equals("execute") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isAssignableFrom(request.getClass())) {
                m.invoke(null, request);
                return;
            }
        }
        throw new NoSuchMethodException("No supported yt-dlp execute method found");
    }

    private boolean isStable(File file) {
        long a = file.length();
        try { Thread.sleep(350L); } catch (InterruptedException ignored) {}
        long b = file.length();
        return a > 0 && a == b;
    }

    private void invokeStaticInit() throws Exception {
        Class<?> cls = findClass("YtDlp", "YoutubeDL");
        
        // التحقق إن كان الكلاس يعتمد نمط Singleton مثل yausername
        try {
            Method getInstance = cls.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            Method init = cls.getMethod("init", Context.class);
            init.invoke(instance, getContext().getApplicationContext());
            return;
        } catch (NoSuchMethodException ignored) {}

        // أو استدعاء ثابت
        for (Method m : cls.getMethods()) {
            if (m.getName().equals("init") && m.getParameterTypes().length == 1) {
                m.invoke(null, getContext().getApplicationContext());
                return;
            }
        }
    }

    private Class<?> findClass(String... simpleNames) throws ClassNotFoundException {
        String[] packages = new String[]{
                "com.yausername.youtubedl_android.",
                "dev.ffmpegkit.ytdlp.",
                "dev.ffmpegkit_maintained.ytdlp."
        };
        for (String p : packages) {
            for (String n : simpleNames) {
                try { return Class.forName(p + n); } catch (ClassNotFoundException ignored) {}
            }
        }
        throw new ClassNotFoundException("yt-dlp Android classes not found");
    }

    private void invokeFluent(Object request, String name, String value) throws Exception {
        for (Method m : request.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
                m.invoke(request, value);
                return;
            }
        }
    }

    private void addOption(Object request, String key, String value) throws Exception {
        for (Method m : request.getClass().getMethods()) {
            if (m.getName().equals("addOption") && m.getParameterTypes().length == 2) {
                if (value == null) m.invoke(request, key, "");
                else m.invoke(request, key, value);
                return;
            }
        }
    }

    private int sanitizeQuality(String q) {
        try {
            int n = Integer.parseInt(q.replaceAll("[^0-9]", ""));
            return Math.max(144, Math.min(2160, n));
        } catch (Exception e) { return 720; }
    }

    private void cleanupTempFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().endsWith(".part") || f.getName().endsWith(".ytdl")) f.delete();
        }
    }

    private File findNewestVideo(File dir) {
        File[] files = dir.listFiles((d, name) -> {
            String n = name.toLowerCase(Locale.US);
            return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov");
        });
        if (files == null || files.length == 0) return null;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files[0];
    }

    private Uri publishToMediaStore(File source) {
        try {
            ContentResolver resolver = getContext().getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShortsVideos");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (FileInputStream in = new FileInputStream(source);
                 java.io.OutputStream out = resolver.openOutputStream(uri)) {
                byte[] buf = new byte[1024 * 1024];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(uri, done, null, null);
            }
            return uri;
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore publish failed", t);
            return null;
        }
    }

    private void emitProgress(int progress, String line) {
        JSObject data = new JSObject();
        data.put("progress", progress);
        data.put("line", line == null ? "" : line);
        notifyListeners("progress", data);
    }

    private String messageOf(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null && (x.getMessage() == null || x.getMessage().isEmpty())) x = x.getCause();
        return x.getMessage() == null ? x.toString() : x.getMessage();
    }
}
