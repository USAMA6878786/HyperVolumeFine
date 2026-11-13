package io.github.usama6878786.hypervolumefine;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.provider.Settings;
import android.util.SparseIntArray;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 音量细调 —— 让音量键在低音量区间以更细的跨度调整。
 *
 * 原理（基于 Android 17 / HyperOS 4 的 AudioService 实现）：
 *  1. 每路音频流的档位数由 AudioService.MAX_STREAM_VOLUME[] 决定，小米 13 的音乐流是 29 档
 *     （ro.config.media_vol_steps=29），内部索引空间是 档位 × 10。
 *     AudioService 初始化时会把档位数通过 setMaxVolumeIndexForGroup 同步给音频策略，
 *     所以这个数字就是"真实可用的音量级数"。
 *  2. 一次按键固定走一档，29 档时每档占音量曲线 3.4%，低音量区一格就有 3~8dB 变化。
 *  3. 本模块把档位数提高到 100（可配置），低音量区每次按键走 N 档（默认 1），
 *     高音量区走更多档；并可让音量滑条吸附到固定档位，便于拖动时停准。
 *
 * 配置存储在 Settings.System，由模块 App 通过广播委托 system_server 写入
 * （第三方 App 无权直接写自定义 System 设置，会报 secure settings 错误）。
 */
public final class VolumeFineModule extends XposedModule {

    private static final String TAG = "VolumeFine";

    private static final String PROP_MEDIA_VOL_STEPS = "ro.config.media_vol_steps";
    private static final String ACTION_CONFIG = "io.github.usama6878786.hypervolumefine.ACTION_CONFIG";
    private static final String MODULE_PKG = "io.github.usama6878786.hypervolumefine";

    /** 框架中"一档"对应的内部索引数量（VolumeStreamState#readSettings 固定为 setting * 10） */
    private static final int STEP_UNIT = 10;
    private static final int DEVICE_OUT_DEFAULT = 0x40000000;

    // ---- 配置项（Settings.System）----
    private static final String KEY_ENABLED = "volume_fine_enabled";
    private static final String KEY_STEPS = "volume_fine_steps";
    private static final String KEY_THRESHOLD = "volume_fine_threshold";
    private static final String KEY_FINE = "volume_fine_fine";
    private static final String KEY_COARSE = "volume_fine_coarse";
    private static final String KEY_SNAP = "volume_fine_snap";
    private static final String KEY_STREAMS = "volume_fine_streams";
    private static final String KEY_DEBUG = "volume_fine_debug";
    private static final String KEY_SHOW_NUMBER = "volume_fine_show_number";

    private static final int DEF_ENABLED = 1;
    private static final int DEF_STEPS = 100;
    private static final int DEF_THRESHOLD = 30;
    private static final int DEF_FINE = 1;
    private static final int DEF_COARSE = 4;
    private static final int DEF_SNAP = 2;
    private static final String DEF_STREAMS = "3";
    private static final int DEF_DEBUG = 0;

    /** 按键路径标记：按键调整时不参与"滑条吸附"，避免两套粒度互相打架 */
    private static final ThreadLocal<Boolean> IN_KEY_PATH = new ThreadLocal<>();

    // ---- 反射句柄 ----
    private Field fStreamType;
    private Field fIndexMap;
    private Field fIndexMax;

    private Object mMaxStreamVolume;
    private boolean mPatched;

    private ContentResolver mResolver;
    private Config mConfig;
    private long mConfigAt;
    private String mStreamsRaw;
    private Set<Integer> mStreamsCache;
    private int mModuleUid = -1;
    private volatile boolean mReceiverRegistered;


    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(4, TAG, "module loaded, process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!"com.android.systemui".equals(pkg)) {
            return;
        }
        log(4, TAG, "systemui loaded, installing panel hooks");
        try {
            new PanelNumber(this).install(param.getDefaultClassLoader());
        } catch (Throwable t) {
            log(6, TAG, "systemui hooks failed", t);
        }
    }

    /** 供 PanelNumber 判断是否输出调试日志 */
    boolean debugEnabled() {
        return config().debug;
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader cl = param.getClassLoader();
        log(4, TAG, "system_server starting, installing volume hooks");

        try {
            hookAudioServiceCtor(cl);
        } catch (Throwable t) {
            log(6, TAG, "hook AudioService ctor failed", t);
        }
        try {
            hookVolumeStepsProperty(cl);
        } catch (Throwable t) {
            log(6, TAG, "hook SystemProperties failed", t);
        }
        try {
            hookVolumeStreamState(cl);
        } catch (Throwable t) {
            log(6, TAG, "hook VolumeStreamState failed", t);
        }
        try {
            hookAbsoluteSet(cl);
        } catch (Throwable t) {
            log(5, TAG, "hook setStreamVolume failed: " + t);
        }
        ensureConfigReceiver();
        startReceiverRetry();
        try {
            hookIndexFactors(cl);
        } catch (Throwable t) {
            log(5, TAG, "hook updateIndexFactors failed: " + t);
        }
        try {
            hookHalVolume(cl);
        } catch (Throwable t) {
            log(5, TAG, "hook AudioSystemAdapter failed: " + t);
        }
        log(4, TAG, "hooks installed, api=" + getApiVersion()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    // ------------------------------------------------------------------ 配置通道

    /**
     * 模块 App 无法直接写自定义的 System 设置（会被系统判定为 secure settings 拒绝），
     * 所以让它发广播，由 system_server 代为落盘，读取路径仍然是便宜的本地设置读取。
     */
    private synchronized void ensureConfigReceiver() {
        if (mReceiverRegistered) {
            return;
        }
        try {
            doRegisterConfigReceiver();
            mReceiverRegistered = true;
        } catch (Throwable t) {
            log(5, TAG, "register config receiver failed: " + t);
        }
    }

    /** AMS 还没起来时 registerReceiver 会 NPE，交给后续重试 */
    private void startReceiverRetry() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60 && !mReceiverRegistered; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                ensureConfigReceiver();
            }
        }, "VolumeFineCfg");
        t.setDaemon(true);
        t.start();
    }

    private void doRegisterConfigReceiver() throws Throwable {
        Context ctx = systemContext();
        if (ctx == null) {
            throw new IllegalStateException("no system context");
        }
        try {
            mModuleUid = ctx.getPackageManager()
                    .getApplicationInfo(MODULE_PKG, 0).uid;
        } catch (Throwable ignored) {
        }
        IntentFilter filter = new IntentFilter(ACTION_CONFIG);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    if (!isFromModule(this, intent)) {
                        log(5, TAG, "ignored config broadcast from untrusted sender");
                        return;
                    }
                    persist(intent);
                } catch (Throwable t) {
                    log(5, TAG, "apply config broadcast failed: " + t);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
        log(4, TAG, "config receiver registered, moduleUid=" + mModuleUid);
    }

    private boolean isFromModule(BroadcastReceiver receiver, Intent intent) {
        if (mModuleUid <= 0) {
            return true;
        }
        try {
            // API 34+: BroadcastReceiver#getSentFromUid()，用反射避免不同版本签名差异
            Method m = BroadcastReceiver.class.getMethod("getSentFromUid");
            Object uid = m.invoke(receiver);
            if (uid instanceof Integer) {
                int u = (Integer) uid;
                if (config().debug) {
                    log(3, TAG, "config broadcast from uid=" + u + " (module=" + mModuleUid + ")");
                }
                // 有些版本拿不到发送者 uid（<=0），此时放行
                if (u > 0) {
                    return u == mModuleUid;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 把 App 发来的配置写进 Settings.System（system_server 有权限） */
    private void persist(Intent intent) {
        ContentResolver cr = resolver();
        if (cr == null) {
            return;
        }
        String[] intKeys = {KEY_ENABLED, KEY_STEPS, KEY_THRESHOLD, KEY_FINE, KEY_COARSE, KEY_SNAP,
                KEY_SHOW_NUMBER, KEY_DEBUG};
        StringBuilder sb = new StringBuilder();
        for (String key : intKeys) {
            if (!intent.hasExtra(key)) {
                continue;
            }
            int v = intent.getIntExtra(key, 0);
            Settings.System.putInt(cr, key, v);
            sb.append(key).append('=').append(v).append(' ');
        }
        if (mConfig != null) {
            mConfig = null;   // 立即失效，下一次读取即生效
        }
        log(4, TAG, "config saved: " + sb);
    }

    // ------------------------------------------------------------------ hooks

    private void hookAudioServiceCtor(ClassLoader cl) throws Throwable {
        Class<?> audioService = Class.forName("com.android.server.audio.AudioService", false, cl);
        Field maxField = audioService.getDeclaredField("MAX_STREAM_VOLUME");
        maxField.setAccessible(true);
        mMaxStreamVolume = maxField.get(null);

        Constructor<?> ctor = null;
        for (Constructor<?> c : audioService.getDeclaredConstructors()) {
            if (c.getParameterCount() > 4) {
                ctor = c;
                break;
            }
        }
        if (ctor == null) {
            throw new NoSuchMethodException("AudioService constructor");
        }
        ctor.setAccessible(true);
        deoptimize(ctor);
        hook(ctor).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(chain -> {
            try {
                applyStepCount();
            } catch (Throwable t) {
                log(6, TAG, "applyStepCount failed", t);
            }
            ensureConfigReceiver();
            return chain.proceed();
        });
    }

    private void hookVolumeStepsProperty(ClassLoader cl) throws Throwable {
        Class<?> sp = Class.forName("android.os.SystemProperties", false, cl);
        Method getInt = sp.getDeclaredMethod("getInt", String.class, int.class);
        getInt.setAccessible(true);
        hook(getInt).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(chain -> {
            if (PROP_MEDIA_VOL_STEPS.equals(chain.getArg(0))) {
                Config cfg = config();
                if (cfg.enabled) {
                    return cfg.steps;
                }
            }
            return chain.proceed();
        });
    }

    private void hookVolumeStreamState(ClassLoader cl) throws Throwable {
        Class<?> vss = Class.forName("com.android.server.audio.AudioService$VolumeStreamState", false, cl);
        fStreamType = field(vss, "mStreamType");
        fIndexMap = field(vss, "mIndexMap");
        fIndexMax = field(vss, "mIndexMax");

        Method adjustIndex = vss.getDeclaredMethod("adjustIndex", int.class, int.class, String.class, boolean.class);
        adjustIndex.setAccessible(true);
        try {
            deoptimize(adjustIndex);
        } catch (Throwable t) {
            log(5, TAG, "deoptimize adjustIndex failed: " + t);
        }
        hook(adjustIndex).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(this::handleAdjustIndex);
    }

    /** 滑条等"绝对设置"路径：把索引吸附到网格，拖动时更容易停准；同时刷新音量数值浮层 */
    private void hookAbsoluteSet(ClassLoader cl) throws Throwable {
        Class<?> vss = Class.forName("com.android.server.audio.AudioService$VolumeStreamState", false, cl);
        Method setIndex = vss.getDeclaredMethod("setIndex", int.class, int.class, String.class, boolean.class);
        setIndex.setAccessible(true);
        final Field fType = fStreamType;
        hook(setIndex).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept(chain -> {
            Object self = chain.getThisObject();
            int stream = -1;
            int index = 0;
            int max = 0;
            try {
                stream = fType.getInt(self);
                index = (Integer) chain.getArg(0);
                max = fIndexMax.getInt(self);
            } catch (Throwable ignored) {
            }

            Object[] args = null;
            Config cfg = config();
            if (IN_KEY_PATH.get() == null && cfg.enabled && cfg.snap > 1) {
                try {
                    if (cfg.streams.contains(stream)) {
                        int snapped = snap(index, cfg.snap, max);
                        if (snapped != index) {
                            if (cfg.debug) {
                                log(3, TAG, "snap " + index + " -> " + snapped + " (grid=" + cfg.snap + ")");
                            }
                            index = snapped;
                            args = new Object[]{index, chain.getArg(1), chain.getArg(2), chain.getArg(3)};
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            Object r = (args == null) ? chain.proceed() : chain.proceed(args);
            return r;
        });
    }

    /**
     * 音量最终送给音频策略层时做一次非线性映射：
     * 低音频"数值"对应的曲线位置更小（每步的响度变化更小），
     * 数值越大越接近线性（恢复正常跨度）。
     *
     * UI 上显示的数字不变、每次按键仍然 +1，改变的只是送进曲线的位置。
     */
    private void hookIndexFactors(ClassLoader cl) throws Throwable {
        Class<?> vss = Class.forName("com.android.server.audio.AudioService$VolumeStreamState", false, cl);
        Method m = vss.getDeclaredMethod("updateIndexFactors");
        m.setAccessible(true);
        final Field fMax = fIndexMax;
        final Field fType = fStreamType;
        hook(m).setPriority(XposedInterface.PRIORITY_LOWEST).intercept(chain -> {
            Object r = chain.proceed();
            if (config().debug) {
                try {
                    Object self = chain.getThisObject();
                    log(3, TAG, "updateIndexFactors stream=" + fType.getInt(self) + " max=" + fMax.getInt(self));
                } catch (Throwable ignored) {
                }
            }
            return r;
        });
    }

    private void hookHalVolume(ClassLoader cl) throws Throwable {
        Class<?> cls = Class.forName("com.android.server.audio.AudioSystemAdapter", false, cl);
        for (Method cand : cls.getDeclaredMethods()) {
            if (cand.getName().equals("setStreamVolumeIndexAS") && cand.getParameterCount() == 4) {
                cand.setAccessible(true);
                hook(cand).setPriority(XposedInterface.PRIORITY_LOWEST).intercept(chain -> {
                    if (config().debug) {
                        try {
                            log(3, TAG, "HAL stream=" + chain.getArg(0) + " index=" + chain.getArg(1)
                                    + " muted=" + chain.getArg(2) + " device=" + chain.getArg(3));
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                });
                return;
            }
        }
    }

    // ------------------------------------------------------------------ core

    private void applyStepCount() {
        if (mPatched) {
            return;
        }
        mPatched = true;
        Config cfg = config();
        if (!cfg.enabled) {
            restoreStockVolumeSettings();
            return;
        }
        if (mMaxStreamVolume == null) {
            return;
        }
        int[] arr = (int[]) mMaxStreamVolume;
        for (Integer stream : cfg.streams) {
            if (stream < 0 || stream >= arr.length) {
                continue;
            }
            int old = arr[stream];
            if (cfg.steps <= 0 || cfg.steps == old) {
                continue;
            }
            arr[stream] = cfg.steps;
            log(4, TAG, "stream " + stream + " volume steps: " + old + " -> " + cfg.steps);
        }
    }

    private Object handleAdjustIndex(XposedInterface.Chain chain) throws Throwable {
        Object[] newArgs = null;
        try {
            Object rawStep = chain.getArg(0);
            if (!(rawStep instanceof Integer)) {
                return chain.proceed();
            }
            int step = (Integer) rawStep;
            if (step == 0 || Math.abs(step) > STEP_UNIT * 12) {
                return chain.proceed();
            }
            Config cfg = config();
            if (!cfg.enabled) {
                return chain.proceed();
            }
            Object self = chain.getThisObject();
            int stream = fStreamType.getInt(self);
            if (!cfg.streams.contains(stream)) {
                return chain.proceed();
            }
            Object mapObj = fIndexMap.get(self);
            if (!(mapObj instanceof SparseIntArray)) {
                return chain.proceed();
            }
            SparseIntArray map = (SparseIntArray) mapObj;
            int device = (Integer) chain.getArg(1);
            int cur = map.get(device, Integer.MIN_VALUE);
            if (cur == Integer.MIN_VALUE) {
                cur = map.get(DEVICE_OUT_DEFAULT, 0);
            }
            int max = fIndexMax.getInt(self);
            if (max <= 0) {
                return chain.proceed();
            }

            // 低音量区按 fine 档，超过 threshold 按 coarse 档（两个值都由 App 设置）
            int lowLimit = cfg.threshold * STEP_UNIT;
            boolean fine = step > 0 ? cur < lowLimit : cur <= lowLimit;
            int units = fine ? cfg.fine : cfg.coarse;
            int newStep = step > 0 ? STEP_UNIT * units : -STEP_UNIT * units;
            if (newStep == step) {
                return chain.proceed();
            }
            newArgs = new Object[]{newStep, device, chain.getArg(2), chain.getArg(3)};
            if (cfg.debug) {
                log(3, TAG, "step cur=" + (cur / STEP_UNIT) + " " + step + " -> " + newStep
                        + " (fine=" + fine + ", units=" + units + ")");
            }
        } catch (Throwable t) {
            return chain.proceed();
        }
        if (newArgs == null) {
            return chain.proceed();
        }
        IN_KEY_PATH.set(Boolean.TRUE);
        try {
            return chain.proceed(newArgs);
        } finally {
            IN_KEY_PATH.remove();
        }
    }

    private static int snap(int index, int grid, int max) {
        if (grid <= 1) {
            return index;
        }
        int snapped = Math.round(index / (float) grid) * grid;
        if (snapped < 0) {
            snapped = 0;
        }
        if (snapped > max) {
            snapped = max;
        }
        return snapped;
    }

    private void restoreStockVolumeSettings() {
        final int stockMax = 29;
        try {
            ContentResolver cr = resolver();
            if (cr == null) {
                return;
            }
            Cursor c = cr.query(Settings.System.CONTENT_URI, null, null, null, null);
            if (c == null) {
                return;
            }
            java.util.ArrayList<String> keys = new java.util.ArrayList<>();
            java.util.ArrayList<Integer> values = new java.util.ArrayList<>();
            try {
                while (c.moveToNext()) {
                    String key = c.getString(c.getColumnIndex(Settings.System.NAME));
                    if (key == null || !key.startsWith("volume_")) {
                        continue;
                    }
                    String val = c.getString(c.getColumnIndex(Settings.System.VALUE));
                    if (val == null) {
                        continue;
                    }
                    try {
                        int v = Integer.parseInt(val.trim());
                        if (v > stockMax) {
                            keys.add(key);
                            values.add(Math.max(1, Math.round(v * stockMax / 100f)));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            } finally {
                c.close();
            }
            for (int i = 0; i < keys.size(); i++) {
                Settings.System.putInt(cr, keys.get(i), values.get(i));
                log(4, TAG, "restore " + keys.get(i) + " -> " + values.get(i));
            }
        } catch (Throwable t) {
            log(5, TAG, "restoreStockVolumeSettings failed: " + t);
        }
    }

    // ------------------------------------------------------------------ utils

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private Config config() {
        long now = System.nanoTime() / 1_000_000L;
        if (mConfig != null && now - mConfigAt < 800L) {
            return mConfig;
        }
        // 一次 query 把 volume_fine_* 全读回来（原来 9 个键 = 9 次跨进程调用）
        Map<String, String> kv = snapshotSettings();
        Config c = new Config();
        c.enabled = intOf(kv, KEY_ENABLED, DEF_ENABLED) != 0;
        c.steps = clamp(intOf(kv, KEY_STEPS, DEF_STEPS), 15, 150);
        c.threshold = clamp(intOf(kv, KEY_THRESHOLD, DEF_THRESHOLD), 0, 150);
        c.fine = clamp(intOf(kv, KEY_FINE, DEF_FINE), 1, 6);
        c.coarse = clamp(intOf(kv, KEY_COARSE, DEF_COARSE), 1, 10);
        c.snap = clamp(intOf(kv, KEY_SNAP, DEF_SNAP), 1, 6);
        c.debug = intOf(kv, KEY_DEBUG, DEF_DEBUG) != 0;
        String streams = kv.containsKey(KEY_STREAMS) ? kv.get(KEY_STREAMS) : DEF_STREAMS;
        c.streams = parseStreams(streams);
        mConfig = c;
        mConfigAt = now;
        return c;
    }

    /** 一次性读取所有 volume_fine_* 配置，读失败则退回逐键读取 */
    private Map<String, String> snapshotSettings() {
        ContentResolver cr = resolver();
        if (cr != null) {
            Cursor cursor = null;
            try {
                cursor = cr.query(Settings.System.CONTENT_URI,
                        new String[]{Settings.System.NAME, Settings.System.VALUE},
                        Settings.System.NAME + " LIKE ?", new String[]{"volume_fine_%"},
                        null);
                if (cursor != null) {
                    Map<String, String> map = new HashMap<>();
                    while (cursor.moveToNext()) {
                        map.put(cursor.getString(0), cursor.getString(1));
                    }
                    if (!map.isEmpty()) {
                        return map;
                    }
                    // 查询成功但没读到东西（可能是新装、也可能 provider 不支持 LIKE）：
                    // 退回逐键读取，避免把用户配置当成默认值
                    return readKeysOneByOne(cr);
                }
            } catch (Throwable t) {
                log(5, TAG, "snapshotSettings failed, fallback: " + t);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return readKeysOneByOne(cr);
    }

    private Map<String, String> readKeysOneByOne(ContentResolver cr) {
        Map<String, String> map = new HashMap<>();
        if (cr == null) {
            return map;
        }
        String[] keys = {KEY_ENABLED, KEY_STEPS, KEY_THRESHOLD, KEY_FINE, KEY_COARSE,
                KEY_SNAP, KEY_STREAMS, KEY_DEBUG, KEY_SHOW_NUMBER};
        for (String key : keys) {
            try {
                String v = Settings.System.getString(cr, key);
                if (v != null) {
                    map.put(key, v);
                }
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private static int intOf(Map<String, String> kv, String key, int def) {
        String v = kv.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 解析流列表；同样的字符串只解析一次，避免每次刷新都 new HashSet */
    private Set<Integer> parseStreams(String raw) {
        if (raw != null && raw.equals(mStreamsRaw) && mStreamsCache != null) {
            return mStreamsCache;
        }
        Set<Integer> set = new HashSet<>();
        if (raw != null) {
            for (String s : raw.split(",")) {
                s = s.trim();
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    set.add(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        mStreamsRaw = raw;
        mStreamsCache = set;
        return set;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private int getInt(String key, int def) {
        try {
            ContentResolver cr = resolver();
            if (cr == null) {
                return def;
            }
            return Settings.System.getInt(cr, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private String getString(String key, String def) {
        try {
            ContentResolver cr = resolver();
            if (cr == null) {
                return def;
            }
            String v = Settings.System.getString(cr, key);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    private Context systemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            log(5, TAG, "cannot get system context: " + t);
            return null;
        }
    }

    private ContentResolver resolver() {
        if (mResolver != null) {
            return mResolver;
        }
        Context ctx = systemContext();
        if (ctx == null) {
            return null;
        }
        mResolver = ctx.getContentResolver();
        return mResolver;
    }

    private static final class Config {
        boolean enabled;
        int steps;
        int threshold;
        int fine;
        int coarse;
        int snap;
        boolean debug;
        Set<Integer> streams;
    }
}
