package com.nextflow.nftouch.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nextflow.nftouch.TaskOrchestrator
import com.nextflow.nftouch.channel.Channel
import com.nextflow.nftouch.service.ClawAccessibilityService
import com.nextflow.nftouch.utils.KVUtils
import com.nextflow.nftouch.ClawApplication
import com.nextflow.nftouch.R
import com.nextflow.nftouch.utils.XLog

/**
 * 远程指令执行器：解析云控下发的指令并路由到对应执行路径。
 * 自动将仪表盘坐标（基于 JPEG 尺寸）转换为手机触摸坐标。
 */
class RemoteExecutor(
    private val taskOrchestrator: TaskOrchestrator?
) {

    init {
        instance = this
    }

    companion object {

        @Volatile
        private var instance: RemoteExecutor? = null

        fun getInstance(): RemoteExecutor? = instance
        private const val TAG = "RemoteExecutor"
    }

    // JPEG 截图尺寸（由 ScreenStreamer 更新）
    @Volatile var jpgW = 720
    @Volatile var jpgH = 1600

    private var scaleX = 1.0f
    private var scaleY = 1.0f

    private fun ensureCalibrated() {
        val tw = DeviceInfo.touchW
        val th = DeviceInfo.touchH
        if (tw > 0 && th > 0 && jpgW > 0 && jpgH > 0) {
            scaleX = tw.toFloat() / jpgW.toFloat()
            scaleY = th.toFloat() / jpgH.toFloat()
        }
    }

    /** 将仪表盘坐标转换为手机触摸坐标 */
    private fun toTouch(dashX: Int, dashY: Int): Pair<Int, Int> {
        ensureCalibrated()
        return (dashX * scaleX).toInt() to (dashY * scaleY).toInt()
    }

    fun execute(type: String, params: Map<String, Any?>) {
        executeWithResult(type, params)
    }

    fun executeWithResult(type: String, params: Map<String, Any?>): String? {
        XLog.d(TAG, "Execute: type=$type, params=$params")
        when (type) {
            "cmd_tap" -> {
                val x = (params["x"] as? Number)?.toInt() ?: return ClawApplication.instance.getString(R.string.remote_invalid_x)
                val y = (params["y"] as? Number)?.toInt() ?: return ClawApplication.instance.getString(R.string.remote_invalid_y)
                val duration = (params["duration"] as? Number)?.toLong() ?: 100L
                val (tx, ty) = toTouch(x, y)
                ClawAccessibilityService.getInstance()?.performTap(tx, ty, duration)
                XLog.d(TAG, "Tap: dash($x,$y) → touch($tx,$ty)")
                return null
            }
            "cmd_swipe" -> {
                val x1 = (params["x1"] as? Number)?.toInt() ?: return ClawApplication.instance.getString(R.string.remote_invalid_start_x)
                val y1 = (params["y1"] as? Number)?.toInt() ?: return ClawApplication.instance.getString(R.string.remote_invalid_start_y)
                val x2 = (params["x2"] as? Number)?.toInt() ?: return ClawApplication.instance.getString(R.string.remote_invalid_end_x)
                val y2 = (params["y2"] as? Number)?.toInt() ?: return ClawApplication.instance.getString(R.string.remote_invalid_end_y)
                val duration = (params["duration"] as? Number)?.toLong() ?: 300L
                val (tx1, ty1) = toTouch(x1, y1)
                val (tx2, ty2) = toTouch(x2, y2)
                ClawAccessibilityService.getInstance()?.performSwipe(tx1, ty1, tx2, ty2, duration)
                XLog.d(TAG, "Swipe: dash($x1,$y1)→($x2,$y2) → touch($tx1,$ty1)→($tx2,$ty2)")
                return null
            }
            "cmd_input" -> {
                val text = params["text"] as? String ?: return ClawApplication.instance.getString(R.string.remote_no_input_text)
                val service = ClawAccessibilityService.getInstance() ?: return ClawApplication.instance.getString(R.string.remote_no_accessibility)

                // ===== Phase 1: Try Accessibility API (standard path) =====
                val target = findTextInputNode(service)
                if (target != null) {
                    XLog.d(TAG, "cmd_input: found node, class=${target.className}, editable=${target.isEditable}")

                    target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
                    target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(300)

                    // Strategy 1: ACTION_SET_TEXT
                    val args = android.os.Bundle()
                    args.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                    val ok = target.performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
                    )
                    if (ok) {
                        XLog.d(TAG, "cmd_input: SET_TEXT ok")
                        target.recycle()
                        return ClawApplication.instance.getString(R.string.remote_input_ok, text)
                    }

                    // Strategy 2: Clipboard + ACTION_PASTE (disabled — clipboard-only, not typing into field)
                    // setClipboardSync(service, text)
                    // val pasted = target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                    // if (pasted) {
                    //     XLog.d(TAG, "cmd_input: PASTE ok")
                    //     target.recycle()
                    //     return ClawApplication.instance.getString(R.string.remote_paste_ok, text)
                    // }

                    // Fallback: try shell input text (works for terminals like Termux)
                    target.recycle()
                    if (text.all { it.code in 0x20..0x7E }) {
                        val typed = inputTextViaShell(text)
                        if (typed) return ClawApplication.instance.getString(R.string.remote_input_ok, text)
                    }
                }

                // ===== Phase 2: Accessibility tree empty → fallback =====
                XLog.w(TAG, "cmd_input: Accessibility tree empty, falling back to shell injection")

                // Clipboard-based strategies disabled — only pastes to clipboard, doesn't type into field
                // setClipboardSync(service, text)
                // Thread.sleep(200)
                // val pasteSuccess = tryPasteKeyevents()
                // if (pasteSuccess) {
                //     return ClawApplication.instance.getString(R.string.remote_paste_ok, text)
                // }

                // Strategy 3: input text via shell (ASCII) — may work on some devices
                if (text.all { it.code in 0x20..0x7E }) {
                    val typed = inputTextViaShell(text)
                    if (typed) {
                        return ClawApplication.instance.getString(R.string.remote_input_ok, text)
                    }
                }

                return ClawApplication.instance.getString(R.string.remote_input_all_failed)
            }
            "cmd_task" -> {
                val prompt = params["prompt"] as? String ?: return ClawApplication.instance.getString(R.string.remote_no_task_prompt)
                if (!KVUtils.hasLlmConfig()) {
                    return ClawApplication.instance.getString(R.string.remote_no_llm_config)
                }
                val orch = taskOrchestrator ?: return ClawApplication.instance.getString(R.string.remote_no_orchestrator)
                val messageId = "cloud_${System.currentTimeMillis()}"
                if (orch.tryAcquireTask(messageId, Channel.CLOUD)) {
                    orch.startNewTask(Channel.CLOUD, prompt, messageId)
                    return ClawApplication.instance.getString(R.string.remote_task_started, prompt)
                }
                return ClawApplication.instance.getString(R.string.remote_task_in_progress)
            }
            "cmd_key" -> {
                val key = params["key"] as? String ?: return ClawApplication.instance.getString(R.string.remote_no_key)
                val service = ClawAccessibilityService.getInstance() ?: return ClawApplication.instance.getString(R.string.remote_no_accessibility)
                when (key) {
                    "home" -> service.pressHome()
                    "back" -> service.pressBack()
                    "recent" -> service.openRecentApps()
                    "lock" -> service.lockScreen()
                    else -> return ClawApplication.instance.getString(R.string.remote_unknown_key, key)
                }
                return null
            }
            else -> return ClawApplication.instance.getString(R.string.remote_unknown_command, type)
        }
        return null
    }

    // ======================== Input Field Search (multi-strategy fallback) ========================

    /**
     * Multi-strategy search for editable nodes, compatible with WeChat and other special input fields.
     *
     * Fallback order:
     *   1. FOCUS_INPUT + isEditable
     *   2. 递归 isEditable
     *   3. Match by class name keywords (EditText / Input)
     *   4. Iterate all windows and repeat above strategies
     */
    private fun findTextInputNode(service: ClawAccessibilityService): android.view.accessibility.AccessibilityNodeInfo? {
        // ---- Round 1: current active window ----
        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString() ?: ""
            val childCount = activeRoot.childCount
            XLog.d(TAG, "findInput: activeRoot class=${activeRoot.className}, pkg=$pkg, children=$childCount")
            dumpTree(activeRoot, 0, pkg)
            // 如果根节点有子节点，尝试在其中查找
            if (childCount > 0) {
                val found = findInputInTree(activeRoot)
                if (found != null) return found
            } else {
                XLog.w(TAG, "findInput: activeRoot is empty (children=0), scanning all windows")
            }
            activeRoot.recycle()
        } else {
            XLog.w(TAG, "findInput: rootInActiveWindow is null")
        }

        // ---- Round 2: iterate all windows (key: WeChat actual UI tree may be here) ----
        try {
            val windows = service.windows
            XLog.d(TAG, "findInput: scanning ${windows.size} windows")
            for (window in windows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString() ?: ""
                XLog.d(TAG, "findInput: window pkg=$pkg, class=${root.className}, children=${root.childCount}")
                if (pkg == "com.tencent.mm") {
                    dumpTree(root, 0, pkg)
                }
                if (root.childCount > 0) {
                    val found = findInputInTree(root)
                    if (found != null) {
                        root.recycle()
                        return found
                    }
                }
                root.recycle()
            }
        } catch (e: Exception) {
            XLog.e(TAG, "findInput: error scanning windows: ${e.message}")
        }

        return null
    }

    /**
     * 递归 dump UI 树结构，用于调试。
     * 只 dump 微信窗口的前 5 层，避免日志过多。
     */
    private fun dumpTree(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int, pkg: String) {
        if (node == null || depth > 5) return
        if (pkg != "com.tencent.mm") return
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString() ?: "null"
        val text = node.text
        val desc = node.contentDescription
        val editable = node.isEditable
        val focusable = node.isFocusable
        val focused = node.isFocused
        val clickable = node.isClickable
        val resId = node.viewIdResourceName ?: ""
        XLog.d(TAG, "${indent}[${cls.substringAfterLast('.')}]" +
                " edit=$editable focus=$focusable focused=$focused click=$clickable" +
                if (text != null) " text=\"$text\"" else "" +
                if (desc != null) " desc=\"$desc\"" else "" +
                if (resId.isNotEmpty()) " id=$resId" else "")
        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1, pkg)
        }
    }

    /**
     * Search for input nodes in a single UI tree by priority:
     *   1) FOCUS_INPUT + isEditable
     *   2) Recursive isEditable
     *   3) Recursive class name keyword match
     *   4) Match by known WeChat resource IDs
     *   5) FOCUS_INPUT node direct return (fallback)
     */
    private fun findInputInTree(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        // Strategy 1: FOCUS_INPUT
        val focusNode = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null) {
            if (focusNode.isEditable) {
                XLog.d(TAG, "findInput: strategy=FOCUS_INPUT+editable, class=${focusNode.className}")
                return focusNode
            }
            // FOCUS_INPUT node is not editable but may be an input container, check its children
            val child = findFirstEditable(focusNode)
            if (child != null) {
                XLog.d(TAG, "findInput: strategy=FOCUS_INPUT child editable, class=${child.className}")
                focusNode.recycle()
                return child
            }
            // Fallback: FOCUS_INPUT node itself may be the input field (e.g. WeChat custom view)
            XLog.d(TAG, "findInput: strategy=FOCUS_INPUT fallback, class=${focusNode.className}, pkg=${focusNode.packageName}")
            return focusNode
        }

        // Strategy 2: Recursive isEditable
        val editable = findFirstEditable(root)
        if (editable != null) {
            XLog.d(TAG, "findInput: strategy=recursive editable, class=${editable.className}")
            return editable
        }

        // Strategy 3: Class name keyword match (WeChat etc. where isEditable=false)
        val byClassName = findByClassName(root)
        if (byClassName != null) {
            XLog.d(TAG, "findInput: strategy=className match, class=${byClassName.className}")
            return byClassName
        }

        // Strategy 4: Match by known WeChat resource IDs
        val byResId = findByWeChatResId(root)
        if (byResId != null) {
            XLog.d(TAG, "findInput: strategy=wechatResId match, class=${byResId.className}, id=${byResId.viewIdResourceName}")
            return byResId
        }

        return null
    }

    /** Recursively find isEditable node */
    private fun findFirstEditable(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child)
            if (result != null) {
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    /**
     * Recursively find input field nodes by class name keywords.
     * WeChat and other apps may not set isEditable, but class name still contains "EditText"/"Input".
     */
    private fun findByClassName(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("Input", ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByClassName(child)
            if (result != null) {
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    /**
     * Find input field by known WeChat resource IDs.
     * WeChat chat input box resource-id usually contains "chatting_content_et" or "input".
     */
    private fun findByWeChatResId(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        val pkg = node.packageName?.toString() ?: ""
        if (pkg == "com.tencent.mm") {
            val resId = node.viewIdResourceName ?: ""
            if (resId.contains("chatting_content_et", ignoreCase = true) ||
                resId.contains("input", ignoreCase = true)) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findByWeChatResId(child)
            if (result != null) {
                if (result != child) child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    // ======================== System-level Text Injection ========================

    /** Set clipboard on main thread */
    private fun setClipboardSync(service: ClawAccessibilityService, text: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val clipboard = service.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("nf", text))
            } catch (e: Exception) {
                XLog.e(TAG, "setClipboard error: ${e.message}")
            }
            latch.countDown()
        }
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    /**
     * Directly inject ASCII text via `input text` command.
     * Bypasses Accessibility API, suitable for WeChat etc. where UI tree is empty.
     * Note: only supports ASCII printable characters (0x20-0x7E).
     */
    private fun inputTextViaShell(text: String): Boolean {
        return try {
            // In input text, spaces need %s escaping
            val escaped = text.replace(" ", "%s")
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text '$escaped'"))
            val ok = proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            XLog.d(TAG, "inputTextViaShell: text='$text', success=$ok")
            ok
        } catch (e: Exception) {
            XLog.e(TAG, "inputTextViaShell failed: ${e.message}")
            false
        }
    }

    /**
     * Try multiple paste key event combinations, compatible with different vendor ROMs.
     * After each method, check if clipboard is still valid.
     */
    private fun tryPasteKeyevents(): Boolean {
        // Combo 1: KEYCODE_PASTE (279)
        try {
            val p1 = Runtime.getRuntime().exec(arrayOf("input", "keyevent", "279"))
            val ok1 = p1.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            XLog.d(TAG, "paste: keyevent 279, exit=$ok1")
            Thread.sleep(300)
            return true  // Cannot directly determine success, return true for upper layer to check
        } catch (e: Exception) {
            XLog.e(TAG, "paste: keyevent 279 failed: ${e.message}")
        }

        // Combo 2: KEYCODE_V (50) — some devices send V key directly
        try {
            val p2 = Runtime.getRuntime().exec(arrayOf("input", "keyevent", "50"))
            p2.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            XLog.d(TAG, "paste: keyevent 50 (V)")
            Thread.sleep(300)
            return true
        } catch (e: Exception) {
            XLog.e(TAG, "paste: keyevent 50 failed: ${e.message}")
        }

        // Combo 3: Ctrl+V = KEYCODE_CTRL_LEFT(113) + KEYCODE_V(50) via shell
        try {
            val p3 = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent 113 && input keyevent 50"))
            p3.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            XLog.d(TAG, "paste: Ctrl+V via shell")
            Thread.sleep(300)
            return true
        } catch (e: Exception) {
            XLog.e(TAG, "paste: Ctrl+V failed: ${e.message}")
        }

        // Combo 4: KEYCODE_PASTE with META_CTRL_ON (279 + 4096)
        try {
            val p4 = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent --longpress 279"))
            p4.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            XLog.d(TAG, "paste: longpress 279")
            Thread.sleep(300)
            return true
        } catch (e: Exception) {
            XLog.e(TAG, "paste: longpress 279 failed: ${e.message}")
        }

        return false
    }
}
