package com.launcher.positionfixer;

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

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Standalone Java LSPosed module for Oplus Launcher v15.8.x.
 *
 * It does not alter Ice Box and does not clear launcher data. It stores the launcher
 * favorites coordinates before a package-unavailable callback and restores only the
 * affected rows after the package becomes available again.
 */
public final class LauncherPositionHook implements IXposedHookLoadPackage {
    private static final String TAG = "LauncherPositionFixer";
    private static final String LAUNCHER_PKG = "com.android.launcher";
    private static final String MODEL = "com.android.launcher3.model.LauncherModel";
    private static final String OP_MODEL = "com.android.launcher3.OplusLauncherModel";
    private static final Uri FAVORITES = Uri.parse("content://com.android.launcher.settings/favorites");
    private static final String PREFS = "launcher_position_fixer";
    private static final String SNAPSHOTS = "snapshots_v1";
    private static final String[] PROJECTION = new String[]{
            "_id", "intent", "title", "itemType", "container", "screen",
            "cellX", "cellY", "spanX", "spanY", "rank", "profileId",
            "iconType", "iconPackage", "iconResource", "restored", "options"
    };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LauncherPositionFixer-IO");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean attached;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!LAUNCHER_PKG.equals(lpparam.packageName)) return;
        if (lpparam.processName != null && !LAUNCHER_PKG.equals(lpparam.processName)) return;
        if (attached) return;
        attached = true;
        try {
            hookModel(lpparam, MODEL);
            hookModel(lpparam, OP_MODEL);
            Log.i(TAG, "Attached to Oplus Launcher");
        } catch (Throwable t) {
            attached = false;
            XposedBridge.log(TAG + ": attach failed: " + Log.getStackTraceString(t));
        }
    }

    private static void hookModel(XC_LoadPackage.LoadPackageParam lp, String className) {
        final Class<?> clazz;
        try {
            clazz = XposedHelpers.findClass(className, lp.classLoader);
        } catch (Throwable ignored) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "onPackagesUnavailable",
                    String[].class, UserHandle.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String[] packages = (String[]) p.args[0];
                            if (packages == null || packages.length == 0) return;
                            Context context = currentContext();
                            if (context == null) return;
                            IO.execute(() -> snapshot(context, packages));
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unavailable hook failed for " + className + ": " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(clazz, "onPackagesAvailable",
                    String[].class, UserHandle.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            String[] packages = (String[]) p.args[0];
                            if (packages == null || packages.length == 0) return;
                            Context context = currentContext();
                            if (context == null) return;
                            // Oplus may finish its package bind asynchronously. Wait briefly so
                            // a DB update cannot race the first model bind.
                            IO.execute(() -> {
                                try { Thread.sleep(350L); } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                }
                                restore(context, packages);
                            });
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": available hook failed for " + className + ": " + t);
        }
    }

    private static Context currentContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object current = XposedHelpers.callStaticMethod(at, "currentApplication");
            return current instanceof Context ? (Context) current : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void snapshot(Context context, String[] packages) {
        Set<String> targets = new HashSet<>();
        for (String p : packages) if (p != null) targets.add(p);
        if (targets.isEmpty()) return;
        try {
            ContentResolver cr = context.getContentResolver();
            List<JSONObject> rows = new ArrayList<>();
            try (Cursor c = cr.query(FAVORITES, PROJECTION, null, null, null)) {
                if (c == null) return;
                while (c.moveToNext()) {
                    String intent = text(c, "intent");
                    String pkg = packageFromIntent(intent);
                    if (!targets.contains(pkg)) continue;
                    JSONObject row = new JSONObject();
                    row.put("_id", integer(c, "_id", -1));
                    row.put("intent", intent);
                    row.put("title", text(c, "title"));
                    copyInt(row, c, "itemType");
                    copyInt(row, c, "container");
                    copyInt(row, c, "screen");
                    copyInt(row, c, "cellX");
                    copyInt(row, c, "cellY");
                    copyInt(row, c, "spanX");
                    copyInt(row, c, "spanY");
                    copyInt(row, c, "rank");
                    copyInt(row, c, "profileId");
                    copyInt(row, c, "iconType");
                    copy(row, c, "iconPackage");
                    copy(row, c, "iconResource");
                    copyInt(row, c, "restored");
                    copyInt(row, c, "options");
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) return;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = readSnapshots(prefs);
            for (JSONObject row : rows) arr.put(row);
            prefs.edit().putString(SNAPSHOTS, arr.toString()).apply();
            Log.i(TAG, "snapshotted " + rows.size() + " rows for " + targets);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": snapshot failed: " + Log.getStackTraceString(t));
        }
    }

    private static void restore(Context context, String[] packages) {
        Set<String> targets = new HashSet<>();
        for (String p : packages) if (p != null) targets.add(p);
        if (targets.isEmpty()) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray arr = readSnapshots(prefs);
            if (arr.length() == 0) return;
            List<JSONObject> keep = new ArrayList<>();
            List<JSONObject> toRestore = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject row = arr.getJSONObject(i);
                String pkg = packageFromIntent(row.optString("intent"));
                if (targets.contains(pkg)) toRestore.add(row);
                else keep.add(row);
            }
            if (toRestore.isEmpty()) return;
            ContentResolver cr = context.getContentResolver();
            List<JSONObject> current = new ArrayList<>();
            try (Cursor c = cr.query(FAVORITES, PROJECTION, null, null, null)) {
                if (c != null) while (c.moveToNext()) {
                    JSONObject row = new JSONObject();
                    row.put("_id", integer(c, "_id", -1));
                    row.put("intent", text(c, "intent"));
                    row.put("title", text(c, "title"));
                    copyInt(row, c, "itemType");
                    copyInt(row, c, "container");
                    copyInt(row, c, "screen");
                    copyInt(row, c, "cellX");
                    copyInt(row, c, "cellY");
                    copyInt(row, c, "spanX");
                    copyInt(row, c, "spanY");
                    copyInt(row, c, "rank");
                    copyInt(row, c, "profileId");
                    copyInt(row, c, "iconType");
                    copy(row, c, "iconPackage");
                    copy(row, c, "iconResource");
                    copyInt(row, c, "restored");
                    copyInt(row, c, "options");
                    current.add(row);
                }
            }
            int updated = 0;
            for (JSONObject target : toRestore) {
                JSONObject same = findSame(current, target);
                if (same == null) continue;
                ContentValues values = positionValues(target);
                Uri uri = Uri.withAppendedPath(FAVORITES, String.valueOf(same.optInt("_id")));
                int n = cr.update(uri, values, null, null);
                if (n > 0) updated++;
            }
            JSONArray remaining = new JSONArray();
            for (JSONObject row : keep) remaining.put(row);
            prefs.edit().putString(SNAPSHOTS, remaining.toString()).apply();
            Log.i(TAG, "restored " + updated + " / " + toRestore.size() + " rows for " + targets);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": restore failed: " + Log.getStackTraceString(t));
        }
    }

    private static JSONObject findSame(List<JSONObject> current, JSONObject target) {
        String intent = target.optString("intent");
        int profile = target.optInt("profileId", 0);
        for (JSONObject row : current) {
            if (intent.equals(row.optString("intent")) && profile == row.optInt("profileId", 0)) return row;
        }
        return null;
    }

    private static ContentValues positionValues(JSONObject row) {
        ContentValues v = new ContentValues();
        putInt(v, row, "itemType"); putInt(v, row, "container"); putInt(v, row, "screen");
        putInt(v, row, "cellX"); putInt(v, row, "cellY"); putInt(v, row, "spanX");
        putInt(v, row, "spanY"); putInt(v, row, "rank"); putInt(v, row, "profileId");
        putInt(v, row, "iconType"); putInt(v, row, "restored"); putInt(v, row, "options");
        if (row.has("iconPackage")) v.put("iconPackage", row.optString("iconPackage"));
        if (row.has("iconResource")) v.put("iconResource", row.optString("iconResource"));
        return v;
    }

    private static JSONArray readSnapshots(SharedPreferences prefs) {
        try { return new JSONArray(prefs.getString(SNAPSHOTS, "[]")); }
        catch (Throwable ignored) { return new JSONArray(); }
    }

    private static String packageFromIntent(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            Intent i = Intent.parseUri(text, 0);
            if (i.getComponent() != null) return i.getComponent().getPackageName();
            if (i.getPackage() != null) return i.getPackage();
        } catch (Throwable ignored) { }
        return "";
    }

    private static String text(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i >= 0 && !c.isNull(i) ? c.getString(i) : "";
    }

    private static int integer(Cursor c, String name, int fallback) {
        int i = c.getColumnIndex(name);
        return i >= 0 && !c.isNull(i) ? c.getInt(i) : fallback;
    }

    private static void copy(JSONObject out, Cursor c, String key) throws Exception {
        int i = c.getColumnIndex(key);
        if (i >= 0 && !c.isNull(i)) out.put(key, c.getString(i));
    }

    private static void copyInt(JSONObject out, Cursor c, String key) throws Exception {
        int i = c.getColumnIndex(key);
        if (i >= 0 && !c.isNull(i)) out.put(key, c.getInt(i));
    }

    private static void putInt(ContentValues v, JSONObject row, String key) {
        if (row.has(key)) v.put(key, row.optInt(key));
    }
}
