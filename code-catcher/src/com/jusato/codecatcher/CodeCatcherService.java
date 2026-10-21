package com.jusato.codecatcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodeCatcherService extends AccessibilityService {

    static final String PREFS = "ccprefs";
    static final String PREF_CODE_CATCH = "code_catch_enabled";
    static final String PREF_AUTO_PASTE = "auto_paste_enabled";

    static final int KEYCODE_EXTRA = 353;

    static final String TEXT_DELIVERY = "配達";
    static final String TEXT_MANUAL = "手入力";
    static final String TEXT_FIELD = "伝票番号を入力";
    static final String TEXT_FIELD_SHORT = "伝票番号";

    private static final int STEP_DELIVERY = 1;
    private static final int STEP_MANUAL = 2;
    private static final int STEP_FIELD = 3;
    private static final int STEP_PASTE = 4;
    private static final int STEP_VERIFY = 5;

    private static final long POLL_MS = 150;
    private static final long AFTER_CLICK_MS = 400;
    private static final long FINAL_WAIT_MS = 1000;
    private static final int MAX_ATTEMPTS = 60; // ~9s per step

    private static final String DASH = "[-‐‑‒–—―−－ー─]";
    private static final Pattern DASHED_CODE = Pattern.compile(
            "(?<!\\d)(\\d{4})\\s*" + DASH + "\\s*(\\d{4})\\s*" + DASH + "\\s*(\\d{4})(?!\\d)");
    private static final Pattern PLAIN_CODE = Pattern.compile("(?<!\\d)\\d{12}(?!\\d)");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stepRunnable = new StepRunnable(this);

    static final class StepRunnable implements Runnable {
        private final CodeCatcherService service;

        StepRunnable(CodeCatcherService service) {
            this.service = service;
        }

        @Override
        public void run() {
            service.runStep();
        }
    }

    static final class FinishRunnable implements Runnable {
        private final CodeCatcherService service;
        private final String code;

        FinishRunnable(CodeCatcherService service, String code) {
            this.service = service;
            this.code = code;
        }

        @Override
        public void run() {
            service.finishFlow(code);
        }
    }

    private boolean flowRunning;
    private int step;
    private int attempts;
    private String pendingCode;

    // ---------------------------------------------------------------- keys

    static boolean isTriggerKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KEYCODE_EXTRA
                || event.getScanCode() == KEYCODE_EXTRA;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!isTriggerKey(event) || !getPrefBoolean(PREF_CODE_CATCH, true)) {
            return super.onKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            runCapture();
        }
        // Consume both DOWN and UP so the volume does not change.
        return true;
    }

    // ------------------------------------------------------------- capture

    private void runCapture() {
        cancelFlow();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            showToast("Tela não disponível");
            return;
        }
        String code = findCode(root);
        if (code == null) {
            showToast("Nenhum código encontrado");
            return;
        }
        copyToClipboard(code);
        pendingCode = code;
        showToast("Catch: " + code);

        if (getPrefBoolean(PREF_AUTO_PASTE, false)) {
            flowRunning = true;
            step = STEP_DELIVERY;
            attempts = 0;
            handler.postDelayed(stepRunnable, AFTER_CLICK_MS);
        }
    }

    /** Returns the first code on screen as 12 digits without dashes, or null. */
    static String findCode(AccessibilityNodeInfo root) {
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts, 0);
        for (String t : texts) {
            String c = extractDashedCode(t);
            if (c != null) {
                return c;
            }
        }
        for (String t : texts) {
            Matcher m = PLAIN_CODE.matcher(t);
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    static String extractDashedCode(String text) {
        Matcher m = DASHED_CODE.matcher(text);
        if (m.find()) {
            return m.group(1) + m.group(2) + m.group(3);
        }
        return null;
    }

    private static void collectTexts(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 60) {
            return;
        }
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            out.add(text.toString());
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            out.add(desc.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectTexts(node.getChild(i), out, depth + 1);
        }
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("captured_code", text));
        } catch (Exception ignored) {
        }
    }

    // --------------------------------------------------------- auto paste

    private void cancelFlow() {
        // Also drops a pending FinishRunnable so it can't end a newer flow.
        handler.removeCallbacksAndMessages(null);
        flowRunning = false;
        step = 0;
        attempts = 0;
    }

    private void next(int nextStep, long delay) {
        step = nextStep;
        attempts = 0;
        handler.postDelayed(stepRunnable, delay);
    }

    private void retry() {
        attempts++;
        if (attempts > MAX_ATTEMPTS) {
            String what;
            switch (step) {
                case STEP_DELIVERY: what = TEXT_DELIVERY; break;
                case STEP_MANUAL: what = TEXT_MANUAL; break;
                default: what = TEXT_FIELD; break;
            }
            cancelFlow();
            showToast("Auto paste falhou: não encontrou " + what + " (código no clipboard)");
            return;
        }
        handler.postDelayed(stepRunnable, POLL_MS);
    }

    void runStep() {
        if (!flowRunning) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            retry();
            return;
        }
        switch (step) {
            case STEP_DELIVERY: {
                AccessibilityNodeInfo node = findBestByText(root, TEXT_DELIVERY);
                if (node != null && click(node)) {
                    next(STEP_MANUAL, AFTER_CLICK_MS);
                } else {
                    retry();
                }
                break;
            }
            case STEP_MANUAL: {
                AccessibilityNodeInfo node = findBestByText(root, TEXT_MANUAL);
                if (node != null && click(node)) {
                    next(STEP_FIELD, AFTER_CLICK_MS);
                } else {
                    retry();
                }
                break;
            }
            case STEP_FIELD: {
                AccessibilityNodeInfo field = findInputField(root);
                if (field != null && focusField(field)) {
                    next(STEP_PASTE, 300);
                } else {
                    retry();
                }
                break;
            }
            case STEP_PASTE: {
                AccessibilityNodeInfo target = findPasteTarget(root);
                if (target == null) {
                    retry();
                    break;
                }
                boolean pasted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                if (!pasted) {
                    setNodeText(target, pendingCode);
                }
                next(STEP_VERIFY, 250);
                break;
            }
            case STEP_VERIFY: {
                AccessibilityNodeInfo target = findPasteTarget(root);
                if (target != null && !containsCode(target.getText(), pendingCode)) {
                    // Paste did not land - write the text directly.
                    setNodeText(target, pendingCode);
                }
                final String code = pendingCode;
                step = 0;
                // Wait 1 second after pasting before finishing.
                handler.postDelayed(new FinishRunnable(this, code), FINAL_WAIT_MS);
                break;
            }
            default:
                flowRunning = false;
                break;
        }
    }

    void finishFlow(String code) {
        if (flowRunning) {
            flowRunning = false;
            showToast("Colado: " + code);
        }
    }

    static boolean containsCode(CharSequence text, String code) {
        if (text == null || code == null) {
            return false;
        }
        return text.toString().replaceAll("[^0-9]", "").contains(code);
    }

    private AccessibilityNodeInfo findPasteTarget(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            return focused;
        }
        AccessibilityNodeInfo field = findInputField(root);
        if (field != null && field.isEditable()) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return field;
        }
        return null;
    }

    /**
     * Finds the 伝票番号を入力 field: an editable node whose hint/text/description
     * matches, or the editable node next to a label with that text.
     */
    private static AccessibilityNodeInfo findInputField(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> editables = new ArrayList<>();
        collectEditables(root, editables, 0);
        for (AccessibilityNodeInfo e : editables) {
            if (nodeMatches(e, TEXT_FIELD)) {
                return e;
            }
        }
        for (AccessibilityNodeInfo e : editables) {
            if (nodeMatches(e, TEXT_FIELD_SHORT)) {
                return e;
            }
        }
        AccessibilityNodeInfo label = findBestByText(root, TEXT_FIELD);
        if (label == null) {
            return null;
        }
        // Editable inside or around the label.
        for (AccessibilityNodeInfo p = label; p != null; p = p.getParent()) {
            List<AccessibilityNodeInfo> inner = new ArrayList<>();
            collectEditables(p, inner, 0);
            if (!inner.isEmpty()) {
                return inner.get(0);
            }
        }
        // No editable in the tree (custom UI): tap the label itself.
        return label;
    }

    private static void collectEditables(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 60) {
            return;
        }
        if (node.isEditable() && node.isVisibleToUser()) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectEditables(node.getChild(i), out, depth + 1);
        }
    }

    private static boolean nodeMatches(AccessibilityNodeInfo node, String search) {
        if (contains(node.getText(), search) || contains(node.getContentDescription(), search)) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 26 && contains(node.getHintText(), search)) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 28 && contains(node.getPaneTitle(), search)) {
            return true;
        }
        return false;
    }

    private static boolean contains(CharSequence cs, String search) {
        return cs != null && cs.toString().contains(search);
    }

    /** Prefers an exact text match, then the shortest text containing the search. */
    static AccessibilityNodeInfo findBestByText(AccessibilityNodeInfo root, String search) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collectMatches(root, search, found, 0);
        AccessibilityNodeInfo best = null;
        int bestLen = Integer.MAX_VALUE;
        for (AccessibilityNodeInfo n : found) {
            int len = labelOf(n, search).trim().length();
            if (len < bestLen) {
                best = n;
                bestLen = len;
            }
        }
        return best;
    }

    private static String labelOf(AccessibilityNodeInfo n, String search) {
        if (contains(n.getText(), search)) {
            return n.getText().toString();
        }
        if (contains(n.getContentDescription(), search)) {
            return n.getContentDescription().toString();
        }
        if (Build.VERSION.SDK_INT >= 26 && contains(n.getHintText(), search)) {
            return n.getHintText().toString();
        }
        return search;
    }

    private static void collectMatches(AccessibilityNodeInfo node, String search,
                                       List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 60) {
            return;
        }
        if (node.isVisibleToUser() && nodeMatches(node, search)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMatches(node.getChild(i), search, out, depth + 1);
        }
    }

    /** Clicks the node or its nearest clickable ancestor; falls back to a tap gesture. */
    private boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo p = node; p != null; p = p.getParent()) {
            if (p.isClickable() && p.isEnabled()) {
                if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                break;
            }
        }
        return tap(node);
    }

    private boolean focusField(AccessibilityNodeInfo field) {
        if (field.isEditable()) {
            boolean focused = field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean clicked = field.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (focused || clicked || field.isFocused()) {
                return true;
            }
        }
        return click(field);
    }

    private boolean tap(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) {
            return false;
        }
        Path path = new Path();
        path.moveTo(r.exactCenterX(), r.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private static void setNodeText(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    // ---------------------------------------------------------------- misc

    private boolean getPrefBoolean(String key, boolean def) {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, def);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        cancelFlow();
    }

    @Override
    public void onDestroy() {
        cancelFlow();
        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Toast.makeText(this, "Code Catcher ATIVADO! Use Volume+ ou tecla 353", Toast.LENGTH_LONG).show();
    }
}
