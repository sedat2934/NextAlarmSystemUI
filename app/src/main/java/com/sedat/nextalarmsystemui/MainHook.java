package com.sedat.nextalarmsystemui;

import android.app.AlarmManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String SYSTEMUI = "com.android.systemui";
    private static final String MARKER = "⏰";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Context systemUiContext;
    private static String alarmSuffix = "";
    private static boolean receiverRegistered = false;
    private static boolean textViewHookInstalled = false;

    private static final Map<TextView, CharSequence> baseTexts = new WeakHashMap<>();
    private static final ArrayList<WeakReference<TextView>> targets = new ArrayList<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEMUI.equals(lpparam.packageName)) return;

        hookApplicationAttach();
        installTextViewHook();
    }

    private static void hookApplicationAttach() {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                if (context == null || !SYSTEMUI.equals(context.getPackageName())) return;

                systemUiContext = context.getApplicationContext();
                refreshAlarm();
                registerAlarmReceiver();
            }
        });
    }

    private static void installTextViewHook() {
        if (textViewHookInstalled) return;
        textViewHookInstalled = true;

        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || !(param.thisObject instanceof TextView)) return;
                if (!(param.args[0] instanceof CharSequence)) return;

                TextView tv = (TextView) param.thisObject;
                TargetType type = getTargetType(tv);
                if (type == TargetType.NONE) return;

                CharSequence incoming = (CharSequence) param.args[0];
                CharSequence base = stripOurAlarm(incoming);
                synchronized (baseTexts) {
                    baseTexts.put(tv, base);
                }
                rememberTarget(tv);

                if (TextUtils.isEmpty(alarmSuffix)) {
                    param.args[0] = base;
                } else {
                    param.args[0] = decorate(base, type);
                }
            }
        });
    }

    private static CharSequence decorate(CharSequence base, TargetType type) {
        if (TextUtils.isEmpty(alarmSuffix)) return base;
        String b = base == null ? "" : base.toString().trim();

        // QS: tarih/saat metninin yanına; Kilit ekranı: üst durum çubuğu saatinin yanına.
        if (TextUtils.isEmpty(b)) return alarmSuffix;
        return b + "   " + alarmSuffix;
    }

    private static CharSequence stripOurAlarm(CharSequence text) {
        if (text == null) return "";
        String s = text.toString();
        int idx = s.indexOf(MARKER);
        if (idx < 0) return text;
        return s.substring(0, idx).trim();
    }

    private static TargetType getTargetType(TextView tv) {
        String idName = resourceEntryName(tv);
        if (idName == null) idName = "";
        String lowerId = idName.toLowerCase();

        boolean clockish = lowerId.contains("clock") || lowerId.contains("time") || lowerId.contains("date");
        if (!clockish) return TargetType.NONE;

        ViewParent p = tv.getParent();
        int depth = 0;
        while (p != null && depth++ < 12) {
            String cn = p.getClass().getName();
            String lcn = cn.toLowerCase();

            if (lcn.contains("quickstatusbarheader") ||
                    lcn.contains("qsheader") ||
                    lcn.contains("shadeheader") ||
                    lcn.contains("quickqs") ||
                    lcn.contains("quick_settings")) {
                // Öncelik tarih/saat alanlarına verilir; alakasız metinleri atla.
                if (lowerId.contains("date") || lowerId.contains("clock") || lowerId.contains("time")) {
                    return TargetType.QS;
                }
            }

            if (lcn.contains("keyguardstatusbarview") ||
                    lcn.contains("keyguardstatusbar") ||
                    lcn.contains("keyguard_header") ||
                    lcn.contains("keyguardheader")) {
                if (lowerId.contains("clock") || lowerId.contains("time")) {
                    return TargetType.LOCKSCREEN;
                }
            }

            p = p.getParent();
        }
        return TargetType.NONE;
    }

    private static String resourceEntryName(View v) {
        try {
            int id = v.getId();
            if (id == View.NO_ID) return null;
            return v.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void rememberTarget(TextView tv) {
        synchronized (targets) {
            for (WeakReference<TextView> ref : targets) {
                if (ref.get() == tv) return;
            }
            targets.add(new WeakReference<>(tv));
        }
    }

    private static void registerAlarmReceiver() {
        if (systemUiContext == null || receiverRegistered) return;
        receiverRegistered = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshAlarm();
            }
        };

        try {
            systemUiContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            try {
                // Eski imzaya geri dönüş.
                systemUiContext.registerReceiver(receiver, filter);
            } catch (Throwable t2) {
                XposedBridge.log("NextAlarmSystemUI: receiver registration failed: " + t2);
            }
        }
    }

    private static void refreshAlarm() {
        if (systemUiContext == null) return;

        String next = "";
        try {
            AlarmManager am = (AlarmManager) systemUiContext.getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo info = am != null ? am.getNextAlarmClock() : null;
            if (info != null) {
                long trigger = info.getTriggerTime();
                java.text.DateFormat fmt = DateFormat.getTimeFormat(systemUiContext);
                next = MARKER + " " + fmt.format(new java.util.Date(trigger));
            }
        } catch (Throwable t) {
            XposedBridge.log("NextAlarmSystemUI: getNextAlarmClock failed: " + t);
        }

        alarmSuffix = next;
        MAIN.post(MainHook::refreshTargetViews);
    }

    private static void refreshTargetViews() {
        synchronized (targets) {
            Iterator<WeakReference<TextView>> it = targets.iterator();
            while (it.hasNext()) {
                TextView tv = it.next().get();
                if (tv == null) {
                    it.remove();
                    continue;
                }

                CharSequence base;
                synchronized (baseTexts) {
                    base = baseTexts.get(tv);
                }
                if (base == null) base = stripOurAlarm(tv.getText());

                try {
                    tv.setText(base);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private enum TargetType {
        NONE,
        QS,
        LOCKSCREEN
    }
}
