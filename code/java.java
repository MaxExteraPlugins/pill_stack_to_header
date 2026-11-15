package com.maxplugins.pillstack;

import android.content.SharedPreferences;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.RightSlidingDialogContainer;
import org.telegram.ui.Stories.DialogStoriesCell;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Main {
    private static final String TAG = "PillTabs";
    private static final String PREFS = "pill_stack_prefs";
    private static boolean loaded = false;
    private static final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();

    private static Object fragmentRef = null;
    private static boolean pillMoved = false;
    private static final Map<Integer, Integer> savedOverlayColors = new HashMap<>();
    private static Boolean pillVisibleState = null;
    private static int pillHideSeq = 0;
    private static boolean pendingMove = false;
    private static View cachedPill = null;
    private static Object cachedActionBar = null;
    private static Object cachedStoriesCell = null;
    private static Boolean lastExpanded = null;
    private static boolean titleHidden = false;
    private static final Set<Object> mainFragments = Collections.newSetFromMap(new IdentityHashMap<>());
    private static boolean pillMirrored = false;
    private static boolean pillLayoutHooked = false;
    private static boolean pillHiddenByPause = false;
    private static Object cachedRsc = null;
    private static int panelRecheckSeq = 0;
    private static int resumeRecheckSeq = 0;
    private static int moveSeq = 0;
    private static int panelWatchSeq = 0;

    private static boolean settingHideTitle = true;
    private static boolean settingExpandedOnly = true;
    private static int settingOffsetDp = 16;
    private static int settingOffsetTopDp = 1;
    private static boolean settingAlignRight = false;
    private static int settingOffsetRightDp = 16;

    private static Object abTitlesContainer = null;
    private static Object abTitleViews = null;
    private static Object abSubtitle = null;
    private static Object abAdditional = null;
    private static Object abOverlay = null;
    private static Object abCachedFor = null;
    private static boolean panelOpen = false;

    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            readSettings();
            hookDialogs();
        } catch (Exception e) {
            log("Setup error: " + e);
        }
    }

    public static void unload() {
        panelRecheckSeq++;
        resumeRecheckSeq++;
        moveSeq++;
        panelWatchSeq++;
        View pill = cachedPill;
        if (pill != null && pillMirrored) {
            try {
                mirrorPillChildrenPositions(pill);
            } catch (Exception e) {}
        }
        for (XC_MethodHook.Unhook h : hooks) {
            try {
                h.unhook();
            } catch (Exception e) {}
        }
        hooks.clear();
        loaded = false;
    }

    public static void pyFlushConfig() {
        try {
            readSettings();
            applySettingsNow();
        } catch (Exception e) {}
    }

    public static void pyFlushConfigFull() {
        try {
            readSettings();
            applySettingsFull();
        } catch (Exception e) {}
    }

    private static void readSettings() {
        try {
            SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
            settingHideTitle = p.getBoolean("hide_title", true);
            settingExpandedOnly = p.getBoolean("expanded_only", true);
            settingOffsetDp = p.getInt("offset_dp", 16);
            settingOffsetTopDp = p.getInt("offset_top_dp", 1);
            settingAlignRight = p.getBoolean("align_right", false);
            settingOffsetRightDp = p.getInt("offset_right_dp", 16);
        } catch (Exception e) {}
    }

    private static void log(String s) {
        Log.d(TAG, s);
    }

    private static Class<?> forName(String name) throws ClassNotFoundException {
        return Class.forName(name, true, ApplicationLoader.applicationContext.getClassLoader());
    }

    private static Object gf(Object obj, String fieldName) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Exception e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void runOnUI(Runnable r, long delay) {
        try {
            AndroidUtilities.runOnUIThread(r, delay);
        } catch (Exception e) {}
    }

    private static void hook(Method m, XC_MethodHook cb) {
        try {
            m.setAccessible(true);
            XC_MethodHook.Unhook h = XposedBridge.hookMethod(m, cb);
            if (h != null) hooks.add(h);
        } catch (Exception e) {
            log("hook " + m.getName() + ": " + e);
        }
    }

    private static boolean isMainDialogs(Object fragment) {
        if (mainFragments.contains(fragment)) return true;
        try {
            Object folder = gf(fragment, "folderId");
            if (folder != null && ((Number) folder).intValue() != 0) return false;
        } catch (Exception e) {}
        try {
            Object idt = gf(fragment, "initialDialogsType");
            if (idt != null) {
                int v = ((Number) idt).intValue();
                if (v != 0 && v != 7 && v != 8) return false;
            }
        } catch (Exception e) {}
        try {
            Object opened = gf(fragment, "openedDialogId");
            if (opened != null) {
                try {
                    Object d = gf(opened, "dialogId");
                    if (d != null && ((Number) d).longValue() != 0) return false;
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
        mainFragments.add(fragment);
        return true;
    }

    private static boolean pillStillValid() {
        View pill = cachedPill;
        Object ab = cachedActionBar;
        if (pill == null || ab == null) return false;
        try {
            ViewParent p = pill.getParent();
            return p != null && p == ab;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cacheAbFields(Object actionBar) {
        if (abCachedFor == actionBar) return;
        abCachedFor = actionBar;
        try { abTitlesContainer = gf(actionBar, "titlesContainer"); } catch (Exception e) { abTitlesContainer = null; }
        try { abTitleViews = gf(actionBar, "titleTextView"); } catch (Exception e) { abTitleViews = null; }
        try { abSubtitle = gf(actionBar, "subtitleTextView"); } catch (Exception e) { abSubtitle = null; }
        try { abAdditional = gf(actionBar, "additionalSubtitleTextView"); } catch (Exception e) { abAdditional = null; }
        try { abOverlay = gf(actionBar, "additionalSubTitleOverlayContainer"); } catch (Exception e) { abOverlay = null; }
    }

    private static void invalidateAbCache() {
        abCachedFor = null;
        abTitlesContainer = null;
        abTitleViews = null;
        abSubtitle = null;
        abAdditional = null;
        abOverlay = null;
    }

    private static Object[] titleViews() {
        if (abTitleViews == null) return null;
        try {
            int len = Array.getLength(abTitleViews);
            Object[] out = new Object[len];
            for (int i = 0; i < len; i++) out[i] = Array.get(abTitleViews, i);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sa(Object v, float a) {
        if (v == null) return;
        try { ((View) v).setAlpha(a); } catch (Exception e) {}
    }

    private static void sv(Object v, int vis) {
        if (v == null) return;
        try { ((View) v).setVisibility(vis); } catch (Exception e) {}
    }

    private static void stc(Object v, int color) {
        if (v == null) return;
        try { v.getClass().getMethod("setTextColor", int.class).invoke(v, color); } catch (Exception e) {}
    }

    private static void sec(Object v, int color) {
        if (v == null) return;
        try { v.getClass().getMethod("setEmojiColor", int.class).invoke(v, color); } catch (Exception e) {}
    }

    private static Integer gtc(Object v) {
        if (v == null) return null;
        try { return (Integer) v.getClass().getMethod("getTextColor").invoke(v); } catch (Exception e) {}
        try { return (Integer) v.getClass().getMethod("getCurrentTextColor").invoke(v); } catch (Exception e) {}
        return null;
    }

    private static void setDrawableAlpha(Object drawable, int alpha) {
        if (drawable == null) return;
        try {
            if (drawable instanceof android.graphics.drawable.Drawable) {
                ((android.graphics.drawable.Drawable) drawable).setAlpha(alpha);
            }
        } catch (Exception e) {}
    }

    private static void setTitleRightDrawableHidden(Object titleView, boolean hidden) {
        if (titleView == null) return;
        int alpha = hidden ? 0 : 255;
        try {
            Object rd = gf(titleView, "rightDrawable");
            setDrawableAlpha(rd, alpha);
        } catch (Exception e) {}
        try {
            Object erd = gf(titleView, "emojiStatusDrawable");
            setDrawableAlpha(erd, alpha);
        } catch (Exception e) {}
        try {
            Object rd2 = gf(titleView, "rightDrawable2");
            setDrawableAlpha(rd2, alpha);
        } catch (Exception e) {}
        try {
            Object swapDrawable = gf(titleView, "rightDrawable");
            if (swapDrawable != null) {
                String className = swapDrawable.getClass().getName();
                if (className.contains("SwapAnimatedEmojiDrawable") || className.contains("AnimatedEmojiDrawable")) {
                    setDrawableAlpha(swapDrawable, alpha);
                    try {
                        Object innerDrawable = gf(swapDrawable, "drawable");
                        setDrawableAlpha(innerDrawable, alpha);
                    } catch (Exception e) {}
                    try {
                        Object innerDrawable = gf(swapDrawable, "thumbDrawable");
                        setDrawableAlpha(innerDrawable, alpha);
                    } catch (Exception e) {}
                }
            }
        } catch (Exception e) {}
    }

    private static boolean isRecyclerLikeView(View view) {
        Class<?> c = view.getClass();
        while (c != null) {
            String name = c.getName();
            if (name.contains("ecyclerListView") || name.contains("ecyclerView")) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private static void hookDialogs() {
        try {
            final Class<?> daClass = forName("org.telegram.ui.DialogsActivity");
            for (Method m : daClass.getDeclaredMethods()) {
                String n = m.getName();
                if ("onResume".equals(n)) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object f = param.thisObject;
                                if (!isMainDialogs(f)) return;
                                fragmentRef = f;
                                panelOpen = checkPanelOpen();
                                if (panelOpen) { startPanelWatch(); return; }
                                if (pillHiddenByPause && pillMoved && pillStillValid()) {
                                    pillHiddenByPause = false;
                                    quickRestore(f);
                                    return;
                                }
                                if (pillMoved && pillStillValid()) { refreshPillLayout(f); return; }
                                invalidateAndReschedule(f);
                            } catch (Exception e) {
                                log("onResume error: " + e);
                            }
                        }
                    });
                } else if ("onConfigurationChanged".equals(n)) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object f = param.thisObject;
                                if (!isMainDialogs(f)) return;
                                fragmentRef = f;
                                if (panelOpen) return;
                                if (pillMoved) { refreshPillLayout(f); return; }
                                invalidateAndReschedule(f);
                            } catch (Exception e) {
                                log("onConfig error: " + e);
                            }
                        }
                    });
                } else if ("onPause".equals(n)) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object f = param.thisObject;
                                if (!isMainDialogs(f)) return;
                                if (!pillMoved) return;
                                View pill = cachedPill;
                                if (pill != null) {
                                    pill.setVisibility(View.GONE);
                                    pillHiddenByPause = true;
                                }
                            } catch (Exception e) {}
                        }
                    });
                } else if ("createView".equals(n)) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object f = param.thisObject;
                                if (!isMainDialogs(f)) return;
                                fragmentRef = f;
                                cachedPill = null;
                                cachedActionBar = null;
                                cachedStoriesCell = null;
                                cachedRsc = null;
                                invalidateAbCache();
                                titleHidden = false;
                                pillMirrored = false;
                                pillLayoutHooked = false;
                                pillHiddenByPause = false;
                                panelOpen = false;
                                pillMoved = false;
                                pillVisibleState = null;
                                lastExpanded = null;
                                pendingMove = false;
                                moveSeq++;
                                panelRecheckSeq++;
                                resumeRecheckSeq++;
                                panelWatchSeq++;
                                scheduleMove(f, 0);
                            } catch (Exception e) {
                                log("createView error: " + e);
                            }
                        }
                    });
                } else if ("setTitleOverlayText".equals(n)) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (fragmentRef != param.thisObject) return;
                                if (!pillMoved) return;
                                if (panelOpen) return;
                                applyTitleState();
                            } catch (Exception e) {}
                        }
                    });
                }
            }
        } catch (Exception e) {
            log("DA hook: " + e);
        }

        try {
            final Class<?> rscClass = forName("org.telegram.ui.RightSlidingDialogContainer");
            for (Method m : rscClass.getDeclaredMethods()) {
                if ("presentFragment".equals(m.getName())) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!pillMoved) return;
                                Object rsc = param.thisObject;
                                if (cachedRsc != null && cachedRsc != rsc) return;
                                if (cachedRsc == null) {
                                    Object fragment = fragmentRef;
                                    if (fragment == null) return;
                                    try {
                                        Object expected = gf(fragment, "rightSlidingDialogContainer");
                                        if (expected != rsc) return;
                                        cachedRsc = expected;
                                    } catch (Exception e) {
                                        return;
                                    }
                                }
                                panelOpen = true;
                                View pill = cachedPill;
                                if (pill != null) {
                                    pill.setVisibility(View.GONE);
                                    pillVisibleState = Boolean.FALSE;
                                }
                                startPanelWatch();
                            } catch (Exception e) {}
                        }
                    });
                }
            }
        } catch (Exception e) {
            log("RSC hook: " + e);
        }

        try {
            final Class<?> abClass = forName("org.telegram.ui.ActionBar.ActionBar");
            for (Method m : abClass.getDeclaredMethods()) {
                String n = m.getName();
                if ("setTitle".equals(n) || "setTitleAnimated".equals(n) || "setSubtitle".equals(n)) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!settingHideTitle || !titleHidden) return;
                                Object ab = cachedActionBar;
                                if (ab == null || param.thisObject != ab) return;
                                if (panelOpen) return;
                                hideTitleCached();
                            } catch (Exception e) {}
                        }
                    });
                }
            }
        } catch (Exception e) {
            log("AB hook: " + e);
        }

        try {
            final Class<?> dscClass = forName("org.telegram.ui.Stories.DialogStoriesCell");
            for (Method m : dscClass.getDeclaredMethods()) {
                if ("checkCollapsedProgress".equals(m.getName())) {
                    hook(m, new XC_MethodHook() {
                        @Override public void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!settingExpandedOnly) return;
                                if (!pillMoved) return;
                                if (panelOpen) return;
                                Object dsc = cachedStoriesCell;
                                if (dsc == null || dsc != param.thisObject) return;
                                View pill = cachedPill;
                                if (pill == null) return;
                                updatePillFromProgress(param.thisObject, pill);
                            } catch (Exception e) {}
                        }
                    });
                    break;
                }
            }
        } catch (Exception e) {
            log("DSC hook: " + e);
        }
    }

    private static boolean checkPanelOpen() {
        Object rsc = cachedRsc;
        if (rsc == null) {
            Object fragment = fragmentRef;
            if (fragment == null) return false;
            try {
                rsc = gf(fragment, "rightSlidingDialogContainer");
                cachedRsc = rsc;
            } catch (Exception e) {
                return false;
            }
        }
        if (rsc == null) return false;
        try {
            if (gf(rsc, "currentFragment") != null) return true;
        } catch (Exception e) {}
        try {
            return ((RightSlidingDialogContainer) rsc).hasFragment();
        } catch (Exception e) {
            return false;
        }
    }

    private static void startPanelWatch() {
        panelWatchSeq++;
        final int seq = panelWatchSeq;
        runOnUI(new Runnable() {
            @Override public void run() {
                try {
                    if (seq != panelWatchSeq) return;
                    if (!panelOpen) return;
                    if (!pillMoved) return;
                    if (!checkPanelOpen()) {
                        panelOpen = false;
                        pillHiddenByPause = false;
                        View pill = cachedPill;
                        if (pill != null) {
                            restorePillVisibility(pill);
                            applyTitleState();
                        }
                    } else {
                        startPanelWatch();
                    }
                } catch (Exception e) {}
            }
        }, 250);
    }

    private static void quickRestore(Object fragment) {
        View pill = cachedPill;
        Object ab = cachedActionBar;
        if (pill == null || ab == null) {
            invalidateAndReschedule(fragment);
            return;
        }
        if (panelOpen) return;
        restorePillVisibility(pill);
        applyTitleState();
    }

    private static void restorePillVisibility(View pillStack) {
        if (panelOpen) {
            setPillVisibleImmediate(pillStack, false);
            return;
        }
        if (settingExpandedOnly) {
            Boolean expanded = isExpandedSimple();
            lastExpanded = expanded;
            if (Boolean.FALSE.equals(expanded)) setPillVisibleImmediate(pillStack, false);
            else setPillVisibleImmediate(pillStack, true);
        } else {
            setPillVisibleImmediate(pillStack, true);
        }
    }

    private static void mirrorPillChildrenPositions(View pillStack) {
        try {
            int width = pillStack.getWidth();
            if (width <= 0) width = pillStack.getMeasuredWidth();
            if (width <= 0) return;
            ViewGroup vg = (ViewGroup) pillStack;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child == null) continue;
                int left = child.getLeft();
                int right = child.getRight();
                int top = child.getTop();
                int bottom = child.getBottom();
                int nl = width - right;
                int nr = width - left;
                if (nl != left || nr != right) child.layout(nl, top, nr, bottom);
            }
        } catch (Exception e) {}
    }

    private static void hookPillLayout(final View pillStack) {
        if (pillLayoutHooked) return;
        Class<?> cls = pillStack.getClass();
        Method target = null;
        while (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                if ("onLayout".equals(m.getName())) {
                    try {
                        if (m.getParameterTypes().length == 5) {
                            target = m;
                            break;
                        }
                    } catch (Exception e) {}
                }
            }
            if (target != null) break;
            try {
                cls = cls.getSuperclass();
                if (cls == null || "android.view.View".equals(cls.getName())) break;
            } catch (Exception e) {
                break;
            }
        }
        if (target == null) return;
        try {
            target.setAccessible(true);
            XC_MethodHook.Unhook h = XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override public void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!pillMirrored) return;
                        View pill = cachedPill;
                        if (pill == null || pill != param.thisObject) return;
                        mirrorPillChildrenPositions(pill);
                    } catch (Exception e) {}
                }
            });
            if (h != null) hooks.add(h);
            pillLayoutHooked = true;
        } catch (Exception e) {}
    }

    private static void refreshPillLayout(Object fragment) {
        Object realAb = null;
        try { realAb = gf(fragment, "actionBar"); } catch (Exception e) {}
        View pill = cachedPill;
        if (realAb == null || pill == null) {
            invalidateAndReschedule(fragment);
            return;
        }
        try {
            if (pill.getParent() != realAb) {
                invalidateAndReschedule(fragment);
                return;
            }
        } catch (Exception e) {
            invalidateAndReschedule(fragment);
            return;
        }
        if (cachedActionBar != realAb) {
            cachedActionBar = realAb;
            invalidateAbCache();
        }
        try { cachedStoriesCell = gf(fragment, "dialogStoriesCell"); } catch (Exception e) {}
        View titleView = null;
        try { titleView = (View) ((ActionBar) realAb).getTitleTextView(); } catch (Exception e) {}
        int[] pos = calcPosition(realAb, pill, titleView);
        int topOffset = pos[0];
        int offsetDp = pos[1];
        try {
            ViewGroup.LayoutParams lp = pill.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                boolean changed = false;
                if (mlp.topMargin != topOffset) {
                    mlp.topMargin = topOffset;
                    changed = true;
                }
                if (settingAlignRight) {
                    if (mlp.rightMargin != offsetDp) { mlp.rightMargin = offsetDp; changed = true; }
                    if (mlp.leftMargin != 0) { mlp.leftMargin = 0; changed = true; }
                } else {
                    if (mlp.leftMargin != offsetDp) { mlp.leftMargin = offsetDp; changed = true; }
                    if (mlp.rightMargin != 0) { mlp.rightMargin = 0; changed = true; }
                }
                if (changed) pill.setLayoutParams(lp);
            }
        } catch (Exception e) {}
        boolean shouldMirror = !settingAlignRight;
        if (pillMirrored != shouldMirror) {
            if (shouldMirror) {
                pillMirrored = true;
                mirrorPillChildrenPositions(pill);
            } else {
                mirrorPillChildrenPositions(pill);
                pillMirrored = false;
            }
        } else if (pillMirrored) {
            mirrorPillChildrenPositions(pill);
        }
        if (panelOpen) {
            setPillVisibleImmediate(pill, false);
        } else if (settingExpandedOnly) {
            Boolean expanded = isExpandedSimple();
            lastExpanded = expanded;
            if (Boolean.FALSE.equals(expanded)) setPillVisibleImmediate(pill, false);
            else setPillVisibleImmediate(pill, true);
        } else {
            setPillVisibleImmediate(pill, true);
        }
        applyTitleState();
    }

    private static void invalidateAndReschedule(Object fragment) {
        pillMoved = false;
        pillVisibleState = null;
        pendingMove = false;
        lastExpanded = null;
        titleHidden = false;
        pillHiddenByPause = false;
        invalidateAbCache();
        scheduleMove(fragment, 0);
    }

    private static void applyTitleState() {
        Object ab = cachedActionBar;
        if (ab == null) return;
        Object fragment = fragmentRef;
        if (fragment == null) return;
        if (panelOpen) return;
        if (settingHideTitle) {
            hideTitle(ab);
            hideExtraTitles(fragment);
        } else {
            if (titleHidden) {
                showTitle(ab);
                showExtraTitles(fragment);
            }
        }
    }

    private static void hideTitleCached() {
        int transparent = 0x00000000;
        if (abTitlesContainer != null) sa(abTitlesContainer, 0f);
        Object[] tvs = titleViews();
        if (tvs != null) {
            for (Object tv : tvs) {
                if (tv == null) continue;
                sa(tv, 0f);
                stc(tv, transparent);
                sec(tv, transparent);
                setTitleRightDrawableHidden(tv, true);
            }
        }
        if (abSubtitle != null) {
            sa(abSubtitle, 0f);
            stc(abSubtitle, transparent);
        }
        if (abAdditional != null) {
            sa(abAdditional, 0f);
            stc(abAdditional, transparent);
        }
        if (abOverlay != null) sa(abOverlay, 0f);
    }

    private static void updatePillFromProgress(Object dialogStoriesCell, View pillStack) {
        try {
            float progress = ((DialogStoriesCell) dialogStoriesCell).getCollapsedProgress();
            boolean expanded = progress <= 0.22f;
            if (lastExpanded != null && lastExpanded.booleanValue() == expanded) return;
            lastExpanded = expanded;
            if (expanded) setPillVisible(pillStack, true, true);
            else setPillVisible(pillStack, false, true);
        } catch (Exception e) {}
    }

    private static void scheduleMove(final Object fragment, final int attempt) {
        if (pendingMove && attempt > 0) return;
        if (attempt > 8) {
            pendingMove = false;
            return;
        }
        pendingMove = true;
        moveSeq++;
        final int seq = moveSeq;
        final long delay = attempt == 0 ? 50 : 120;
        runOnUI(new Runnable() {
            @Override public void run() {
                try {
                    if (seq != moveSeq) { pendingMove = false; return; }
                    if (fragmentRef != fragment) { pendingMove = false; return; }
                    Object ab = null;
                    try { ab = gf(fragment, "actionBar"); } catch (Exception e) {}
                    if (ab == null || ((View) ab).getMeasuredWidth() <= 0 || ((View) ab).getMeasuredHeight() <= 0) {
                        pendingMove = false;
                        scheduleMove(fragment, attempt + 1);
                        return;
                    }
                    pendingMove = false;
                    cachedActionBar = ab;
                    invalidateAbCache();
                    cacheAbFields(ab);
                    try { cachedStoriesCell = gf(fragment, "dialogStoriesCell"); } catch (Exception e) {}
                    try { cachedRsc = gf(fragment, "rightSlidingDialogContainer"); } catch (Exception e) {}
                    panelOpen = checkPanelOpen();
                    doMove(fragment);
                    applyTitleState();
                    if (panelOpen) startPanelWatch();
                } catch (Exception e) {}
            }
        }, delay);
    }

    private static void setPillVisibleImmediate(View pillStack, boolean visible) {
        try {
            pillVisibleState = visible;
            pillHideSeq++;
            pillStack.animate().cancel();
            pillStack.setScaleX(1f);
            pillStack.setScaleY(1f);
            if (visible) {
                pillStack.setAlpha(1f);
                pillStack.setTranslationX(0f);
                pillStack.setVisibility(View.VISIBLE);
            } else {
                pillStack.setAlpha(0f);
                pillStack.setTranslationX(0f);
                pillStack.setVisibility(View.GONE);
            }
        } catch (Exception e) {}
    }

    private static void setPillVisible(final View pillStack, boolean visible, boolean animated) {
        try {
            if (pillVisibleState != null && pillVisibleState.booleanValue() == visible) return;
            pillVisibleState = visible;
            pillHideSeq++;
            final int hideSeq = pillHideSeq;
            if (visible) {
                pillStack.animate().cancel();
                pillStack.setScaleX(1f);
                pillStack.setScaleY(1f);
                if (pillStack.getVisibility() != View.VISIBLE) {
                    if (animated) {
                        pillStack.setAlpha(0f);
                        if (settingAlignRight) pillStack.setTranslationX(AndroidUtilities.dp(52));
                        else pillStack.setTranslationX(-AndroidUtilities.dp(52));
                    } else {
                        pillStack.setAlpha(1f);
                        pillStack.setTranslationX(0f);
                    }
                    pillStack.setVisibility(View.VISIBLE);
                }
                if (animated) {
                    pillStack.animate().alpha(1f).translationX(0f).setDuration(95).start();
                } else {
                    pillStack.setAlpha(1f);
                    pillStack.setTranslationX(0f);
                }
            } else {
                pillStack.animate().cancel();
                if (pillStack.getVisibility() != View.VISIBLE) {
                    pillStack.setAlpha(0f);
                    pillStack.setTranslationX(0f);
                    pillStack.setVisibility(View.GONE);
                    return;
                }
                if (animated) {
                    final float slideTo = settingAlignRight ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32);
                    pillStack.animate().alpha(0f).translationX(slideTo).setDuration(190).start();
                    runOnUI(new Runnable() {
                        @Override public void run() {
                            finalizePillHide(pillStack, hideSeq);
                        }
                    }, 200);
                } else {
                    pillStack.setAlpha(0f);
                    pillStack.setTranslationX(0f);
                    pillStack.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {}
    }

    private static void finalizePillHide(View pillStack, int hideSeq) {
        try {
            if (pillHideSeq != hideSeq) return;
            if (!Boolean.FALSE.equals(pillVisibleState)) return;
            pillStack.setVisibility(View.GONE);
            pillStack.setTranslationX(0f);
        } catch (Exception e) {}
    }

    private static void hideTitle(Object actionBar) {
        titleHidden = true;
        cacheAbFields(actionBar);
        int transparent = 0x00000000;
        if (abTitlesContainer != null) {
            sv(abTitlesContainer, View.VISIBLE);
            sa(abTitlesContainer, 0f);
        }
        Object[] tvs = titleViews();
        if (tvs != null) {
            for (Object tv : tvs) {
                if (tv == null) continue;
                sv(tv, View.VISIBLE);
                sa(tv, 0f);
                stc(tv, transparent);
                sec(tv, transparent);
                setTitleRightDrawableHidden(tv, true);
            }
        }
        if (abSubtitle != null) {
            sv(abSubtitle, View.VISIBLE);
            sa(abSubtitle, 0f);
            stc(abSubtitle, transparent);
        }
        if (abAdditional != null) {
            sv(abAdditional, View.VISIBLE);
            sa(abAdditional, 0f);
            stc(abAdditional, transparent);
        }
        if (abOverlay != null) {
            sv(abOverlay, View.VISIBLE);
            sa(abOverlay, 0f);
        }
    }

    private static void showTitle(Object actionBar) {
        titleHidden = false;
        cacheAbFields(actionBar);
        Object rp = null;
        try { rp = gf(actionBar, "resourcesProvider"); } catch (Exception e) {}
        int titleColor = -1;
        int subtitleColor = -1;
        try { titleColor = Theme.getColor(Theme.key_actionBarDefaultTitle, (Theme.ResourcesProvider) rp); } catch (Exception e) {}
        try { subtitleColor = Theme.getColor(Theme.key_actionBarDefaultSubtitle, (Theme.ResourcesProvider) rp); } catch (Exception e) {}
        if (abTitlesContainer != null) {
            sv(abTitlesContainer, View.VISIBLE);
            sa(abTitlesContainer, 1f);
        }
        Object[] tvs = titleViews();
        if (tvs != null) {
            for (Object tv : tvs) {
                if (tv == null) continue;
                sv(tv, View.VISIBLE);
                sa(tv, 1f);
                if (titleColor != -1) {
                    stc(tv, titleColor);
                    sec(tv, titleColor);
                }
                setTitleRightDrawableHidden(tv, false);
            }
        }
        if (abSubtitle != null) {
            sv(abSubtitle, View.VISIBLE);
            sa(abSubtitle, 1f);
            if (subtitleColor != -1) stc(abSubtitle, subtitleColor);
        }
        if (abAdditional != null) {
            sv(abAdditional, View.VISIBLE);
            sa(abAdditional, 1f);
            if (subtitleColor != -1) stc(abAdditional, subtitleColor);
        }
        if (abOverlay != null) {
            sv(abOverlay, View.VISIBLE);
            sa(abOverlay, 1f);
        }
    }

    private static void setOverlayTextHidden(View view, boolean hidden) {
        if (view == null) return;
        if (isRecyclerLikeView(view)) return;
        String cname = view.getClass().getName();
        if (cname.contains("AnimatedTextView") || cname.contains("SimpleTextView")) {
            int viewId = view.hashCode();
            try { view.setAlpha(hidden ? 0f : 1f); } catch (Exception e) {}
            if (hidden) {
                if (!savedOverlayColors.containsKey(viewId)) {
                    Integer color = gtc(view);
                    savedOverlayColors.put(viewId, color);
                }
                stc(view, 0x00000000);
                sec(view, 0x00000000);
            } else {
                Integer color = savedOverlayColors.get(viewId);
                if (color != null) {
                    stc(view, color);
                    sec(view, color);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setOverlayTextHidden(vg.getChildAt(i), hidden);
            }
        }
    }

    private static void setEmojiStatusHidden(Object cell, boolean hidden) {
        if (cell == null) return;
        try {
            Object sd = gf(cell, "statusDrawable");
            if (sd instanceof android.graphics.drawable.Drawable) {
                ((android.graphics.drawable.Drawable) sd).setAlpha(hidden ? 0 : 255);
            }
        } catch (Exception e) {}
    }

    private static void hideExtraTitles(Object fragment) {
        Object dsc = null;
        try { dsc = gf(fragment, "dialogStoriesCell"); } catch (Exception e) {}
        Object tpl = null;
        try { tpl = gf(fragment, "topPanelLayout"); } catch (Exception e) {}
        setOverlayTextHidden(dsc instanceof View ? (View) dsc : null, true);
        setOverlayTextHidden(tpl instanceof View ? (View) tpl : null, true);
        setEmojiStatusHidden(dsc, true);
    }

    private static void showExtraTitles(Object fragment) {
        Object dsc = null;
        try { dsc = gf(fragment, "dialogStoriesCell"); } catch (Exception e) {}
        Object tpl = null;
        try { tpl = gf(fragment, "topPanelLayout"); } catch (Exception e) {}
        setOverlayTextHidden(dsc instanceof View ? (View) dsc : null, false);
        setOverlayTextHidden(tpl instanceof View ? (View) tpl : null, false);
        setEmojiStatusHidden(dsc, false);
    }

    private static void movePill(Object fragment) {
        try {
            doMove(fragment);
        } catch (Exception e) {
            log("Move error: " + e);
        }
    }

    private static View findPillAnywhere(Object fragment) {
        View pill = cachedPill;
        if (pill != null) {
            try {
                if (pill.getParent() != null) return pill;
            } catch (Exception e) {}
            cachedPill = null;
        }
        Object fv = gf(fragment, "fragmentView");
        if (fv instanceof View) {
            pill = findPillStack((View) fv);
            if (pill != null) {
                cachedPill = pill;
                return pill;
            }
        }
        Object ab = cachedActionBar;
        if (ab instanceof View) {
            pill = findPillStack((View) ab);
            if (pill != null) {
                cachedPill = pill;
                return pill;
            }
        }
        return null;
    }

    private static void doMove(Object fragment) {
        Object actionBar = cachedActionBar;
        if (actionBar == null) actionBar = gf(fragment, "actionBar");
        if (actionBar == null) return;
        cachedActionBar = actionBar;
        try { cachedStoriesCell = gf(fragment, "dialogStoriesCell"); } catch (Exception e) {}
        View pillStack = findPillAnywhere(fragment);
        if (pillStack == null) return;
        ViewParent parent = pillStack.getParent();
        if (parent == null) return;
        pillStack.setVisibility(View.INVISIBLE);
        View titleView = null;
        try { titleView = (View) ((ActionBar) actionBar).getTitleTextView(); } catch (Exception e) {}
        int[] pos = calcPosition(actionBar, pillStack, titleView);
        int topOffset = pos[0];
        int offsetDp = pos[1];
        FrameLayout.LayoutParams lp;
        if (settingAlignRight) {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topOffset;
            lp.gravity = Gravity.RIGHT | Gravity.TOP;
            lp.rightMargin = offsetDp;
            lp.leftMargin = 0;
        } else {
            lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = topOffset;
            lp.gravity = Gravity.LEFT | Gravity.TOP;
            lp.leftMargin = offsetDp;
            lp.rightMargin = 0;
        }
        if (parent != actionBar) {
            try {
                ((ViewGroup) parent).removeView(pillStack);
            } catch (Exception e) {
                log("Remove error: " + e);
                return;
            }
            try {
                ((ViewGroup) actionBar).addView(pillStack, lp);
            } catch (Exception e) {
                log("Add error: " + e);
                try {
                    ((ViewGroup) parent).addView(pillStack);
                } catch (Exception e2) {}
                return;
            }
        } else {
            try {
                pillStack.setLayoutParams(lp);
            } catch (Exception e) {}
        }
        if (!settingAlignRight && !pillMirrored) {
            pillMirrored = true;
        } else if (settingAlignRight && pillMirrored) {
            pillMirrored = false;
        }
        hookPillLayout(pillStack);
        pillMoved = true;
        cachedPill = pillStack;
        lastExpanded = null;
        if (panelOpen) {
            setPillVisibleImmediate(pillStack, false);
        } else if (settingExpandedOnly) {
            Boolean expanded = isExpandedSimple();
            lastExpanded = expanded;
            if (Boolean.FALSE.equals(expanded)) setPillVisibleImmediate(pillStack, false);
            else setPillVisibleImmediate(pillStack, true);
        } else {
            setPillVisibleImmediate(pillStack, true);
        }
        applyTitleState();
    }

    private static Boolean isExpandedSimple() {
        Object dsc = cachedStoriesCell;
        if (dsc == null) return null;
        try {
            if (((View) dsc).getVisibility() != View.VISIBLE) return null;
        } catch (Exception e) {
            return null;
        }
        try {
            return ((DialogStoriesCell) dsc).getCollapsedProgress() <= 0.22f;
        } catch (Exception e) {}
        return null;
    }

    private static int[] calcPosition(Object actionBar, View pillStack, View titleView) {
        int pillHeight = pillStack.getMeasuredHeight();
        if (pillHeight <= 0) {
            try {
                pillStack.measure(0, 0);
                pillHeight = pillStack.getMeasuredHeight();
            } catch (Exception e) {}
        }
        if (pillHeight <= 0) pillHeight = AndroidUtilities.dp(40);
        int statusBarHeight = AndroidUtilities.statusBarHeight;
        try {
            if (!((ActionBar) actionBar).getOccupyStatusBar()) statusBarHeight = 0;
        } catch (Exception e) {}
        int titleTop = 0;
        int titleHeight = 0;
        if (titleView != null) {
            try {
                titleTop = titleView.getTop();
                titleHeight = titleView.getMeasuredHeight();
            } catch (Exception e) {}
        }
        int topOffset;
        if (titleHeight > 0) {
            topOffset = titleTop + Math.max(0, (titleHeight - pillHeight) / 2);
        } else {
            int contentHeight = ((View) actionBar).getMeasuredHeight() - statusBarHeight;
            topOffset = statusBarHeight + Math.max(0, (contentHeight - pillHeight) / 2);
        }
        topOffset += AndroidUtilities.dp(settingOffsetTopDp);
        int offsetPx;
        if (settingAlignRight) offsetPx = AndroidUtilities.dp(settingOffsetRightDp);
        else offsetPx = AndroidUtilities.dp(settingOffsetDp);
        return new int[]{topOffset, offsetPx};
    }

    private static View findPillStack(View view) {
        if (view == null) return null;
        if (view.getClass().getName().contains("PillStackView")) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View r = findPillStack(vg.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void applySettingsNow() {
        final Object fragment = fragmentRef;
        if (fragment == null) return;
        if (!pillMoved || !pillStillValid()) return;
        View pill = cachedPill;
        Object ab = cachedActionBar;
        if (pill == null || ab == null) return;
        runOnUI(new Runnable() {
            @Override public void run() {
                if (fragmentRef != fragment) return;
                if (!pillMoved) return;
                refreshPillLayout(fragment);
            }
        }, 0);
    }

    private static void applySettingsFull() {
        final Object fragment = fragmentRef;
        if (fragment == null) return;
        if (!pillMoved || !pillStillValid()) return;
        runOnUI(new Runnable() {
            @Override public void run() {
                if (fragmentRef != fragment) return;
                if (!pillMoved) return;
                invalidateAndReschedule(fragment);
            }
        }, 0);
    }
}
