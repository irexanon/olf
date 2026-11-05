package com.launcher.positionfixer;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Standalone pure-Java LSPosed module for Oplus Launcher (com.android.launcher) v15.8.x
 * on OxygenOS 15 / OnePlus.
 *
 * Goal:
 *   When Ice Box freezes an app with DISABLED_USER (setApplicationEnabledSetting(..., 3, ...))
 *   the launcher may drop or move the icon. After unfreeze (enabled = 1) this module restores
 *   the previous home-screen / folder position.
 *
 * Scope: ONLY com.android.launcher process.
 * Does NOT touch Ice Box, system_server, SystemUI, or any other package.
 * Does NOT clear launcher data, does NOT delete databases, does NOT force-stop launcher.
 */
public final class LauncherPositionHook implements IXposedHookLoadPackage {

    private static final String TAG = "LauncherPositionFixer";
    private static final String LAUNCHER_PKG = "com.android.launcher";

    // Known model classes on Oplus Launcher 15.8.x
    private static final String MODEL = "com.android.launcher3.model.LauncherModel";
    private static final String OP_MODEL = "com.android.launcher3.OplusLauncherModel";

    // Favorites provider URIs observed on this build
    private static final Uri[] FAVORITES_URIS = new Uri[]{
            Uri.parse("content://com.android.launcher.settings/favorites"),
            Uri.parse("content://com.android.launcher.OplusFavoritesProvider/favorites"),
            Uri.parse("content://com.android.launcher.settings")
    };

    private static final String PREFS = "lpf_snapshots";
    private static final String KEY_SNAPSHOTS = "snapshots_v2";

    // Columns we care about
    private static final String[] PROJECTION = new String[]{
            "_id", "intent", "title", "itemType",
            "container", "screen", "cellX", "cellY",
            "spanX", "spanY", "rank", "profileId",
            "iconType", "iconPackage", "iconResource",
            "restored", "options"
    };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LPF-IO");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final AtomicBoolean ATTACHED = new AtomicBoolean(false);

    // Simple debounce: ignore rapid repeated restore requests for the same packages
    private static volatile long lastRestoreMs = 0L;
    private static final long DEBOUNCE_MS = 800L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!LAUNCHER_PKG.equals(lpparam.packageName)) return;
        if (lpparam.processName != null && !LAUNCHER_PKG.equals(lpparam.processName)) return;
        if (!ATTACHED.compareAndSet(false, true)) return;

        try {
            hookModel(lpparam, MODEL);
            hookModel(lpparam, OP_MODEL);
            Log.i(TAG, "Attached to Oplus Launcher process");
        } catch (Throwable t) {
            ATTACHED.set(false);
            XposedBridge.log(TAG + " attach failed: " + Log.getStackTraceString(t));
        }
    }

    private static void hookModel(XC_LoadPackage.LoadPackageParam lp, String className) {
        Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(className, lp.classLoader);
        } catch (Throwable ignored) {
            return; // class may not exist on every build
        }

        // Unavailable → snapshot
        safeHook(clazz, "onPackagesUnavailable",
                new Object[]{String[].class, UserHandle.class, boolean.class},
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        final String[] pkgs = (String[]) param.args[0];
                        if (pkgs == null || pkgs.length == 0) return;
                        final Context ctx = currentApplication();
                        if (ctx == null) return;
                        IO.execute(() -> snapshot(ctx, pkgs));
                    }
                });

        // Available → restore (after launcher finishes its own work)
        safeHook(clazz, "onPackagesAvailable",
                new Object[]{String[].class, UserHandle.class, boolean.class},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        final String[] pkgs = (String[]) param.args[0];
                        if (pkgs == null || pkgs.length == 0) return;
                        final Context ctx = currentApplication();
                        if (ctx == null) return;
                        IO.execute(() -> {
                            // Give Oplus model time to finish its package bind / DB update
                            sleepQuiet(400);
                            restore(ctx, pkgs);
                        });
                    }
                });

        // Some Oplus paths use onPackageChanged / onPackageAdded after unfreeze
        safeHook(clazz, "onPackageChanged",
                new Object[]{String.class, UserHandle.class},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String pkg = (String) param.args[0];
                        if (pkg == null || pkg.isEmpty()) return;
                        final Context ctx = currentApplication();
                        if (ctx == null) return;
                        IO.execute(() -> {
                            sleepQuiet(350);
                            restore(ctx, new String[]{pkg});
                        });
                    }
                });

        safeHook(clazz, "onPackageAdded",
                new Object[]{String.class, UserHandle.class},
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String pkg = (String) param.args[0];
                        if (pkg == null || pkg.isEmpty()) return;
                        final Context ctx = currentApplication();
                        if (ctx == null) return;
                        IO.execute(() -> {
                            sleepQuiet(350);
                            restore(ctx, new String[]{pkg});
                        });
                    }
                });
    }

    private static void safeHook(Class<?> clazz, String method, Object[] paramTypes, XC_MethodHook hook) {
        try {
            XposedHelpers.findAndHookMethod(clazz, method, concat(paramTypes, hook));
        } catch (Throwable t) {
            // Method may not exist on this exact build – fail closed
            Log.w(TAG, "Hook skipped " + clazz.getName() + "#" + method + ": " + t.getMessage());
        }
    }

    private static Object[] concat(Object[] types, XC_MethodHook hook) {
        Object[] all = new Object[types.length + 1];
        System.arraycopy(types, 0, all, 0, types.length);
        all[types.length] = hook;
        return all;
    }

    // ------------------------------------------------------------------
    // Snapshot
    // ------------------------------------------------------------------

    private static void snapshot(Context context, String[] packages) {
        Set<String> targets = toSet(packages);
        if (targets.isEmpty()) return;

        try {
            ContentResolver cr = context.getContentResolver();
            Uri uri = resolveFavoritesUri(cr);
            if (uri == null) {
                Log.w(TAG, "No working favorites URI for snapshot");
                return;
            }

            List<JSONObject> rows = new ArrayList<>();
            try (Cursor c = cr.query(uri, PROJECTION, null, null, null)) {
                if (c == null) return;
                while (c.moveToNext()) {
                    String intentText = getString(c, "intent");
                    String pkg = packageFromIntent(intentText);
                    if (!targets.contains(pkg)) continue;

                    // Only home-screen / folder items (ignore pure all-apps entries)
                    int container = getInt(c, "container", Integer.MIN_VALUE);
                    // -100 = desktop, positive = folder id, other negative values are special
                    if (container < -100) continue;

                    JSONObject row = new JSONObject();
                    row.put("pkg", pkg);
                    row.put("intent", intentText);
                    row.put("title", getString(c, "title"));
                    row.put("itemType", getInt(c, "itemType", 0));
                    row.put("container", container);
                    row.put("screen", getInt(c, "screen", 0));
                    row.put("cellX", getInt(c, "cellX", 0));
                    row.put("cellY", getInt(c, "cellY", 0));
                    row.put("spanX", getInt(c, "spanX", 1));
                    row.put("spanY", getInt(c, "spanY", 1));
                    row.put("rank", getInt(c, "rank", 0));
                    row.put("profileId", getInt(c, "profileId", 0));
                    row.put("iconType", getInt(c, "iconType", 0));
                    putIfPresent(row, c, "iconPackage");
                    putIfPresent(row, c, "iconResource");
                    row.put("restored", getInt(c, "restored", 0));
                    row.put("options", getInt(c, "options", 0));
                    row.put("ts", System.currentTimeMillis());
                    rows.add(row);
                }
            }

            if (rows.isEmpty()) {
                Log.i(TAG, "snapshot: no matching favorites for " + targets);
                return;
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray existing = readArray(prefs);
            // Remove older entries for the same packages so we keep the latest positions
            JSONArray kept = new JSONArray();
            for (int i = 0; i < existing.length(); i++) {
                JSONObject o = existing.optJSONObject(i);
                if (o == null) continue;
                if (!targets.contains(o.optString("pkg"))) {
                    kept.put(o);
                }
            }
            for (JSONObject r : rows) kept.put(r);

            prefs.edit().putString(KEY_SNAPSHOTS, kept.toString()).apply();
            Log.i(TAG, "snapshot: saved " + rows.size() + " row(s) for " + targets);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " snapshot error: " + Log.getStackTraceString(t));
        }
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    private static void restore(Context context, String[] packages) {
        long now = System.currentTimeMillis();
        if (now - lastRestoreMs < DEBOUNCE_MS) return;
        lastRestoreMs = now;

        Set<String> targets = toSet(packages);
        if (targets.isEmpty()) return;

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray all = readArray(prefs);
            if (all.length() == 0) return;

            List<JSONObject> toRestore = new ArrayList<>();
            JSONArray remaining = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null) continue;
                if (targets.contains(o.optString("pkg"))) {
                    toRestore.add(o);
                } else {
                    remaining.put(o);
                }
            }
            if (toRestore.isEmpty()) return;

            ContentResolver cr = context.getContentResolver();
            Uri uri = resolveFavoritesUri(cr);
            if (uri == null) {
                Log.w(TAG, "No working favorites URI for restore");
                return;
            }

            // Current state of favorites
            List<JSONObject> current = queryAll(cr, uri);

            int updated = 0;
            int recreated = 0;

            for (JSONObject snap : toRestore) {
                String intent = snap.optString("intent");
                int profileId = snap.optInt("profileId", 0);

                JSONObject existing = findByIntentAndProfile(current, intent, profileId);

                if (existing != null) {
                    // Already present – only fix position if it drifted
                    if (positionDiffers(existing, snap)) {
                        ContentValues values = positionValues(snap);
                        Uri itemUri = Uri.withAppendedPath(uri, String.valueOf(existing.optLong("_id", -1)));
                        int n = cr.update(itemUri, values, null, null);
                        if (n > 0) {
                            updated++;
                            Log.i(TAG, "restored position for " + snap.optString("pkg")
                                    + " → screen=" + snap.optInt("screen")
                                    + " cell=(" + snap.optInt("cellX") + "," + snap.optInt("cellY") + ")"
                                    + " container=" + snap.optInt("container"));
                        }
                    }
                } else {
                    // Missing – recreate only if we have a valid snapshot
                    ContentValues values = fullValues(snap);
                    Uri inserted = cr.insert(uri, values);
                    if (inserted != null) {
                        recreated++;
                        Log.i(TAG, "recreated missing item for " + snap.optString("pkg"));
                    }
                }
            }

            // Persist remaining snapshots (ones we did not consume)
            prefs.edit().putString(KEY_SNAPSHOTS, remaining.toString()).apply();

            Log.i(TAG, "restore finished for " + targets
                    + " → updated=" + updated + " recreated=" + recreated);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " restore error: " + Log.getStackTraceString(t));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Context currentApplication() {
        try {
            Class<?> helper = XposedHelpers.findClass("android.app.AndroidAppHelper", null);
            Object app = XposedHelpers.callStaticMethod(helper, "currentApplication");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) { }

        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) { }
        return null;
    }

    private static Uri resolveFavoritesUri(ContentResolver cr) {
        for (Uri candidate : FAVORITES_URIS) {
            try (Cursor c = cr.query(candidate, new String[]{"_id"}, null, null, null)) {
                if (c != null) return candidate;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static List<JSONObject> queryAll(ContentResolver cr, Uri uri) {
        List<JSONObject> list = new ArrayList<>();
        try (Cursor c = cr.query(uri, PROJECTION, null, null, null)) {
            if (c == null) return list;
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                try {
                    row.put("_id", getLong(c, "_id", -1));
                    row.put("intent", getString(c, "intent"));
                    row.put("title", getString(c, "title"));
                    row.put("itemType", getInt(c, "itemType", 0));
                    row.put("container", getInt(c, "container", 0));
                    row.put("screen", getInt(c, "screen", 0));
                    row.put("cellX", getInt(c, "cellX", 0));
                    row.put("cellY", getInt(c, "cellY", 0));
                    row.put("spanX", getInt(c, "spanX", 1));
                    row.put("spanY", getInt(c, "spanY", 1));
                    row.put("rank", getInt(c, "rank", 0));
                    row.put("profileId", getInt(c, "profileId", 0));
                } catch (Throwable ignored) { }
                list.add(row);
            }
        } catch (Throwable t) {
            Log.w(TAG, "queryAll failed: " + t.getMessage());
        }
        return list;
    }

    private static JSONObject findByIntentAndProfile(List<JSONObject> list, String intent, int profileId) {
        if (intent == null) return null;
        for (JSONObject row : list) {
            if (intent.equals(row.optString("intent"))
                    && profileId == row.optInt("profileId", 0)) {
                return row;
            }
        }
        return null;
    }

    private static boolean positionDiffers(JSONObject current, JSONObject snap) {
        return current.optInt("container") != snap.optInt("container")
                || current.optInt("screen") != snap.optInt("screen")
                || current.optInt("cellX") != snap.optInt("cellX")
                || current.optInt("cellY") != snap.optInt("cellY")
                || current.optInt("spanX") != snap.optInt("spanX")
                || current.optInt("spanY") != snap.optInt("spanY")
                || current.optInt("rank") != snap.optInt("rank");
    }

    private static ContentValues positionValues(JSONObject snap) {
        ContentValues v = new ContentValues();
        putInt(v, snap, "container");
        putInt(v, snap, "screen");
        putInt(v, snap, "cellX");
        putInt(v, snap, "cellY");
        putInt(v, snap, "spanX");
        putInt(v, snap, "spanY");
        putInt(v, snap, "rank");
        putInt(v, snap, "profileId");
        return v;
    }

    private static ContentValues fullValues(JSONObject snap) {
        ContentValues v = positionValues(snap);
        putInt(v, snap, "itemType");
        putInt(v, snap, "iconType");
        putInt(v, snap, "restored");
        putInt(v, snap, "options");
        if (snap.has("intent")) v.put("intent", snap.optString("intent"));
        if (snap.has("title")) v.put("title", snap.optString("title"));
        if (snap.has("iconPackage")) v.put("iconPackage", snap.optString("iconPackage"));
        if (snap.has("iconResource")) v.put("iconResource", snap.optString("iconResource"));
        return v;
    }

    private static String packageFromIntent(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            Intent i = Intent.parseUri(text, 0);
            ComponentName cn = i.getComponent();
            if (cn != null) return cn.getPackageName();
            if (i.getPackage() != null) return i.getPackage();
        } catch (Throwable ignored) { }
        return "";
    }

    private static Set<String> toSet(String[] packages) {
        Set<String> s = new HashSet<>();
        if (packages == null) return s;
        for (String p : packages) {
            if (p != null && !p.isEmpty() && p.contains(".")) s.add(p);
        }
        return s;
    }

    private static JSONArray readArray(SharedPreferences prefs) {
        try {
            return new JSONArray(prefs.getString(KEY_SNAPSHOTS, "[]"));
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static void putInt(ContentValues v, JSONObject o, String key) {
        if (o.has(key)) v.put(key, o.optInt(key));
    }

    private static void putIfPresent(JSONObject out, Cursor c, String col) {
        try {
            int idx = c.getColumnIndex(col);
            if (idx >= 0 && !c.isNull(idx)) out.put(col, c.getString(idx));
        } catch (Throwable ignored) { }
    }

    private static String getString(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return (i >= 0 && !c.isNull(i)) ? c.getString(i) : "";
    }

    private static int getInt(Cursor c, String col, int def) {
        int i = c.getColumnIndex(col);
        return (i >= 0 && !c.isNull(i)) ? c.getInt(i) : def;
    }

    private static long getLong(Cursor c, String col, long def) {
        int i = c.getColumnIndex(col);
        return (i >= 0 && !c.isNull(i)) ? c.getLong(i) : def;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
