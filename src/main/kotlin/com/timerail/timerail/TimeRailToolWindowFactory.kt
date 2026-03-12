package com.timerail

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.*

class TimeRailToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val ui = TimeRailUI(project)
        val content = ContentFactory.getInstance().createContent(ui.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class TimeRailUI(private val project: Project) {

    // ---- Buttons ----
    private val statusLabel = JBLabel("● Idle")
    private val startBtn = JButton("Start Recording")
    private val stopBtn = JButton("Stop").apply { isEnabled = false }
    private val clearBtn = JButton("Clear")
    private val openBtn = JButton("Open")

    // ---- Popup ----
    private var popup: JBPopup? = null
    private var popupW: Int = 1100
    private var popupH: Int = 720

    // 真半透明（0~255）
    private val popupAlpha = 90        // 背景透明度（更透明就更小）
    private val cardAlpha = 80         // 卡片底透明度
    private val hintAlpha = 200

    // ---- debounce (可选保留：避免极端输入频率下 UI 频繁刷新) ----
    private val debounceMs = 200
    private var refreshTimer: Timer? = null

    // ---- line-based record rule ----
    // 规则：以“基准行 baseLine”为准；当一次输入发生在 != baseLine 的行，就记录一次并更新 baseLine
    private var baseLine: Int? = null

    val component: JComponent = buildToolWindow()

    init {
        wireActions()
        installDocumentListener()
    }

    private fun buildToolWindow(): JComponent {
        val root = SimpleToolWindowPanel(true, true)

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            isOpaque = false

            add(startBtn)
            add(Box.createVerticalStrut(8))
            add(stopBtn)
            add(Box.createVerticalStrut(8))
            add(clearBtn)
            add(Box.createVerticalStrut(8))
            add(openBtn)
            add(Box.createVerticalStrut(10))
            add(statusLabel)
        }

        root.setContent(panel)
        return root
    }

    private fun wireActions() {
        startBtn.addActionListener {
            TimeRailRecorder.start()
            statusLabel.text = "● Recording"
            startBtn.isEnabled = false
            stopBtn.isEnabled = true

            // 初始化 baseLine：当前光标所在行
            baseLine = currentCaretLine()

            // 记录初始状态（可选，但一般很有用）
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val doc = editor?.document
            val file = doc?.let { FileDocumentManager.getInstance().getFile(it) }
            if (doc != null && file != null) {
                TimeRailRecorder.addSnapshot(file.path, file.name, doc.text, capturePreview(editor))
            }
        }

        stopBtn.addActionListener {
            TimeRailRecorder.stop()
            statusLabel.text = "● Idle"
            startBtn.isEnabled = true
            stopBtn.isEnabled = false
        }

        clearBtn.addActionListener {
            TimeRailRecorder.clear()
            baseLine = currentCaretLine()
            if (popup?.isVisible == true) refreshPopupNow()
        }

        openBtn.addActionListener {
            if (popup?.isVisible == true) {
                popup?.cancel()
                popup = null
                openBtn.text = "Open"
                return@addActionListener
            }
            showPopup()
        }
    }

    // ===== POPUP UI (横向 + 半透明 + 大卡片) =====

    private fun showPopup() {
        val (w, h) = computePopupSize75Percent()
        popupW = w
        popupH = h

        val content = buildHorizontalTimeline(popupW, popupH)

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle("TimeRail")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        // 设定初始大小：75% IDE窗口
        popup!!.setSize(Dimension(popupW, popupH))
        popup!!.showInFocusCenter()
        openBtn.text = "Hide"
    }

    private fun refreshPopupNow() {
        // 最稳：关掉再打开（避免旧组件残留导致点击/渲染问题）
        val wasVisible = popup?.isVisible == true
        if (!wasVisible) return
        popup?.cancel()
        popup = null
        openBtn.text = "Open"
        showPopup()
    }

    private fun refreshPopupDebounced() {
        if (popup?.isVisible != true) return
        refreshTimer?.stop()
        refreshTimer = Timer(debounceMs) {
            refreshPopupNow()
        }.apply { isRepeats = false; start() }
    }

    /**
     * 横向时间线：每张卡片很大（接近 popup 的 3/4）
     * 真半透明：根面板自己画 alpha，子组件全部 isOpaque=false
     */
    private fun buildHorizontalTimeline(w: Int, h: Int): JComponent {
        val overlay = TranslucentPanel(popupAlpha).apply {
            layout = BorderLayout()
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            isOpaque = false
        }

        val titleBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
            add(JBLabel("TimeRail").apply {
                foreground = Color(240, 240, 240, 220)
                font = font.deriveFont(Font.BOLD, 16f)
            }, BorderLayout.WEST)
        }
        overlay.add(titleBar, BorderLayout.NORTH)

        val cardsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }

        // 卡片尺寸：接近你说的“占编程界面 3/4”
        val cardW = (w * 0.75).toInt().coerceAtLeast(900)
        val cardH = (h * 0.78).toInt().coerceAtLeast(520)

        val n = TimeRailRecorder.snapshotCount()
        for (i in 0 until n) {
            val snap = TimeRailRecorder.snapshotAt(i) ?: continue
            val card = buildBigCard(i, snap, cardW, cardH)
            cardsRow.add(card)
            cardsRow.add(Box.createHorizontalStrut(18))
        }

        val scroller = JBScrollPane(cardsRow).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            isOpaque = false
            viewport.isOpaque = false
            border = BorderFactory.createEmptyBorder()
        }

        overlay.add(scroller, BorderLayout.CENTER)

        val hint = JBLabel("Rule: snapshot when you start typing on a different line than the current caret line.").apply {
            foreground = Color(230, 230, 230, hintAlpha)
            border = BorderFactory.createEmptyBorder(8, 2, 0, 2)
        }
        overlay.add(hint, BorderLayout.SOUTH)

        return overlay
    }

    private fun buildBigCard(index: Int, snap: TimeRailRecorder.Snapshot, w: Int, h: Int): JComponent {
        val card = CardPanel(cardAlpha).apply {
            layout = BorderLayout()
            preferredSize = Dimension(w, h)
            minimumSize = Dimension(w, h)
            maximumSize = Dimension(w, h)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(220, 220, 220, 80), 1, true),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)
            )
        }

        val img = JLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            isOpaque = false
            icon = scaledToFit(snap.preview, w - 24, h - 70)
        }

        val title = JBLabel("#$index  ${snap.fileName}  ${snap.time}").apply {
            foreground = Color(240, 240, 240, 220)
            border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
        }

        card.add(img, BorderLayout.CENTER)
        card.add(title, BorderLayout.SOUTH)

        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                TimeRailRecorder.applySnapshot(project, snap)
                statusLabel.text = "● Viewing ${snap.fileName} @ ${snap.time}"
            }
        })

        return card
    }

    // ===== Recording logic (line-based) =====

    private fun installDocumentListener() {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (!TimeRailRecorder.isRecording()) return
                if (TimeRailRecorder.isReplaying()) return

                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                val path = file.path
                val name = file.name

                // 找到触发事件的 editor（优先拿绑定这个 document 的 editor）
                val editor = findEditorForDocument(event.document) ?: FileEditorManager.getInstance(project).selectedTextEditor
                val caretLine = editor?.caretModel?.primaryCaret?.logicalPosition?.line

                // 没拿到行就不记录（避免乱存）
                if (caretLine == null) return

                // 初始化 baseLine
                if (baseLine == null) baseLine = caretLine

                // ✅ 新规则：当一次输入发生在 != baseLine 的行，就记录一次并更新 baseLine
                if (caretLine != baseLine) {
                    baseLine = caretLine
                    val preview = capturePreview(editor)
                    TimeRailRecorder.addSnapshot(path, name, event.document.text, preview)

                    // popup 若打开则刷新（保证新卡片能点能看）
                    refreshPopupDebounced()
                }
            }
        }, project)
    }

    private fun findEditorForDocument(doc: com.intellij.openapi.editor.Document): Editor? {
        val editors = EditorFactory.getInstance().getEditors(doc, project)
        return editors.firstOrNull()
    }

    private fun currentCaretLine(): Int? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return editor.caretModel.primaryCaret.logicalPosition.line
    }

    // ===== Screenshot capture / scale =====

    private fun capturePreview(editor: Editor?): ImageIcon? {
        val ed = editor ?: return null
        val comp = ed.contentComponent ?: return null

        val srcW = comp.width.coerceAtLeast(1)
        val srcH = comp.height.coerceAtLeast(1)

        val img = BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            // 更清晰一点
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            comp.paint(g)
        } finally {
            g.dispose()
        }

        return ImageIcon(img)
    }

    private fun scaledToFit(icon: ImageIcon?, maxW: Int, maxH: Int): ImageIcon? {
        if (icon == null) return null
        val iw = icon.iconWidth.toDouble().coerceAtLeast(1.0)
        val ih = icon.iconHeight.toDouble().coerceAtLeast(1.0)
        val scale = minOf(maxW / iw, maxH / ih).coerceAtMost(1.0)
        val nw = (iw * scale).toInt().coerceAtLeast(1)
        val nh = (ih * scale).toInt().coerceAtLeast(1)
        val img = icon.image.getScaledInstance(nw, nh, Image.SCALE_SMOOTH)
        return ImageIcon(img)
    }

    // ===== Popup size: 75% of IDE frame =====

    private fun computePopupSize75Percent(): Pair<Int, Int> {
        val frame = WindowManager.getInstance().getIdeFrame(project)
        val c = frame?.component
        if (c != null && c.width > 200 && c.height > 200) {
            val w = (c.width * 0.75).toInt().coerceAtLeast(1000)
            val h = (c.height * 0.75).toInt().coerceAtLeast(650)
            return w to h
        }
        // fallback：屏幕
        val screen = Toolkit.getDefaultToolkit().screenSize
        val w = (screen.width * 0.75).toInt().coerceAtLeast(1000)
        val h = (screen.height * 0.75).toInt().coerceAtLeast(650)
        return w to h
    }

    // ===== panels for true translucency =====

    private class TranslucentPanel(private val alpha: Int) : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.composite = AlphaComposite.SrcOver
                g2.color = Color(0, 0, 0, alpha)
                g2.fillRoundRect(0, 0, width, height, 18, 18)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private class CardPanel(private val alpha: Int) : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.composite = AlphaComposite.SrcOver
                g2.color = Color(30, 30, 30, alpha)
                g2.fillRoundRect(0, 0, width, height, 18, 18)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}

// ===== Recorder =====

private object TimeRailRecorder {

    @Volatile private var recording = false
    @Volatile private var replaying = false

    fun start() { recording = true }
    fun stop() { recording = false }
    fun isRecording() = recording
    fun isReplaying() = replaying

    data class Snapshot(
        val path: String,
        val fileName: String,
        val content: String,
        val time: String,
        val preview: ImageIcon?
    )

    private val snaps = mutableListOf<Snapshot>()

    fun clear() = snaps.clear()
    fun snapshotCount() = snaps.size
    fun snapshotAt(i: Int) = snaps.getOrNull(i)

    fun addSnapshot(path: String, name: String, content: String, preview: ImageIcon?) {
        val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        snaps.add(Snapshot(path, name, content, ts, preview))
    }

    fun applySnapshot(project: Project, s: Snapshot) {
        val vFile =
            com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath(s.path) ?: return

        val doc =
            FileDocumentManager.getInstance()
                .getDocument(vFile) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            replaying = true
            try {
                doc.setText(s.content)
                FileDocumentManager.getInstance().saveDocument(doc)
            } finally {
                replaying = false
            }
        }
    }
}