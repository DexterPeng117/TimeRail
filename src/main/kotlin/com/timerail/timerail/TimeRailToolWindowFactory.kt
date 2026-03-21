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
import java.awt.geom.Path2D
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

    // (0~255）
    private val popupAlpha = 90        // 背景透明度（更透明就更小）
    private val cardAlpha = 80         // 卡片底透明度
    private val hintAlpha = 200

    // ---- debounce
    private val debounceMs = 200
    private var refreshTimer: Timer? = null

    // ---- line-based record rule ----
    //
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

            // init baseLine：
            baseLine = currentCaretLine()

            // recording
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

    // ===== POPUP UI  =====

    private fun showPopup() {
        val (w, h) = computePopupSize75Percent()
        popupW = w
        popupH = h

        val content = buildTreeTimeline(popupW, popupH)

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle("TimeRail")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        // 75% IDE window
        popup!!.setSize(Dimension(popupW, popupH))
        popup!!.showInFocusCenter()
        openBtn.text = "Hide"
    }

    private fun refreshPopupNow() {
        //
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


    private fun buildTreeTimeline(w: Int, h: Int): JComponent {
        val overlay = TranslucentPanel(popupAlpha).apply {
            layout = BorderLayout()
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            isOpaque = false
        }

        // Zoom buttons
        fun zoomBtn(label: String): JButton = JButton(label).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            isFocusable = false
            isOpaque = false
            preferredSize = Dimension(32, 26)
        }
        val zoomInBtn  = zoomBtn("+")
        val zoomOutBtn = zoomBtn("-")
        val zoomResetBtn = zoomBtn("1x")

        val zoomPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(zoomOutBtn)
            add(zoomResetBtn)
            add(zoomInBtn)
        }

        val titleBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            add(JBLabel("TimeRail - Branch History").apply {
                foreground = Color(240, 240, 240, 220)
                font = font.deriveFont(Font.BOLD, 16f)
            }, BorderLayout.WEST)
            add(zoomPanel, BorderLayout.EAST)
        }
        overlay.add(titleBar, BorderLayout.NORTH)

        val snaps = TimeRailRecorder.allSnapshots()
        val nodeW = 220
        val nodeH = 160
        val gapX  = 48
        val gapY  = 72
        val pad   = 20

        // Group by branch, sorted
        val branches = snaps.groupBy { it.branchId }.toSortedMap()

        // Calculate (x, y) for each snapshot id
        val posMap = mutableMapOf<Int, Point>()
        for ((_, branchSnaps) in branches) {
            val first = branchSnaps.first()
            val xStart = if (first.parentId == null) {
                pad
            } else {
                val parentPos = posMap[first.parentId]
                (parentPos?.x ?: pad) + nodeW + gapX
            }
            val y = pad + first.branchId * (nodeH + gapY)
            branchSnaps.forEachIndexed { i, snap ->
                posMap[snap.id] = Point(xStart + i * (nodeW + gapX), y)
            }
        }

        val canvasW = (posMap.values.maxOfOrNull { it.x } ?: pad) + nodeW + pad
        val canvasH = (posMap.values.maxOfOrNull { it.y } ?: pad) + nodeH + pad

        var scale = 1.4

        // Everything is drawn inside paintComponent — no child components needed.
        // This makes zoom reliable: canvas.preferredSize drives the scroll range directly.
        val canvas = object : JPanel(), Scrollable {
            override fun getPreferredScrollableViewportSize() = Dimension(600, 400)
            override fun getScrollableTracksViewportWidth()   = false
            override fun getScrollableTracksViewportHeight()  = false
            override fun getScrollableUnitIncrement(vr: Rectangle, o: Int, d: Int)  = 20
            override fun getScrollableBlockIncrement(vr: Rectangle, o: Int, d: Int) = 100

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,    RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,   RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g2.scale(scale, scale)

                    // --- connecting lines ---
                    g2.stroke = BasicStroke((1.5 / scale).toFloat())
                    g2.color  = Color(200, 200, 200, 130)
                    for ((_, branchSnaps) in branches) {
                        for (i in 0 until branchSnaps.size - 1) {
                            val p1 = posMap[branchSnaps[i].id]     ?: continue
                            val p2 = posMap[branchSnaps[i + 1].id] ?: continue
                            g2.drawLine(p1.x + nodeW, p1.y + nodeH / 2, p2.x, p2.y + nodeH / 2)
                        }
                        val first = branchSnaps.first()
                        if (first.parentId != null) {
                            val pp = posMap[first.parentId] ?: continue
                            val cp = posMap[first.id]       ?: continue
                            val x1 = pp.x + nodeW / 2;  val y1 = pp.y + nodeH
                            val x2 = cp.x;               val y2 = cp.y + nodeH / 2
                            val path = Path2D.Float()
                            path.moveTo(x1.toFloat(), y1.toFloat())
                            path.curveTo(x1.toFloat(), ((y1+y2)/2).toFloat(),
                                         x2.toFloat(), ((y1+y2)/2).toFloat(),
                                         x2.toFloat(), y2.toFloat())
                            g2.draw(path)
                        }
                    }

                    // --- cards ---
                    for (snap in snaps) {
                        val pos = posMap[snap.id] ?: continue
                        val cx = pos.x;  val cy = pos.y
                        val gc = g2.create() as Graphics2D
                        // card background
                        gc.composite = AlphaComposite.SrcOver
                        gc.color = Color(30, 30, 30, cardAlpha)
                        gc.fillRoundRect(cx, cy, nodeW, nodeH, 14, 14)
                        gc.color = Color(220, 220, 220, 80)
                        gc.stroke = BasicStroke((1f / scale).toFloat())
                        gc.drawRoundRect(cx, cy, nodeW, nodeH, 14, 14)
                        // preview image
                        val rawImg = snap.preview?.image
                        if (rawImg != null) {
                            val iw = rawImg.getWidth(null); val ih = rawImg.getHeight(null)
                            if (iw > 0 && ih > 0) {
                                val imgW = nodeW - 16;  val imgH = nodeH - 30
                                val s  = minOf(imgW.toDouble() / iw, imgH.toDouble() / ih)
                                val dw = (iw * s).toInt().coerceAtLeast(1)
                                val dh = (ih * s).toInt().coerceAtLeast(1)
                                val dx = cx + 8 + (imgW - dw) / 2
                                val dy = cy + 6 + (imgH - dh) / 2
                                gc.drawImage(rawImg, dx, dy, dw, dh, null)
                            }
                        }
                        gc.dispose()
                    }
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            isOpaque = false
            preferredSize = Dimension((canvasW * scale).toInt(), (canvasH * scale).toInt())
        }

        // Click detection via coordinate hit-test (no child components)
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val mx = (e.x / scale).toInt()
                val my = (e.y / scale).toInt()
                for (snap in snaps) {
                    val pos = posMap[snap.id] ?: continue
                    if (mx in pos.x..(pos.x + nodeW) && my in pos.y..(pos.y + nodeH)) {
                        TimeRailRecorder.applySnapshot(project, snap)
                        statusLabel.text = "● B${snap.branchId} #${snap.id} restored @ ${snap.time}"
                        popup?.cancel()
                        popup = null
                        openBtn.text = "Open"
                        break
                    }
                }
            }
        })
        canvas.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val mx = (e.x / scale).toInt(); val my = (e.y / scale).toInt()
                val over = snaps.any { snap ->
                    val pos = posMap[snap.id] ?: return@any false
                    mx in pos.x..(pos.x + nodeW) && my in pos.y..(pos.y + nodeH)
                }
                canvas.cursor = if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                else      Cursor.getDefaultCursor()
            }
        })

        fun updateLayout() {
            canvas.preferredSize = Dimension((canvasW * scale).toInt(), (canvasH * scale).toInt())
            canvas.revalidate()
            canvas.repaint()
        }

        // Ctrl+scroll = zoom; plain scroll = forward to JScrollPane for normal panning
        canvas.addMouseWheelListener { e ->
            if (e.isControlDown || e.isMetaDown) {
                val factor = if (e.wheelRotation < 0) 1.08 else 0.93
                scale = (scale * factor).coerceIn(0.25, 3.0)
                updateLayout()
                e.consume()
            } else {
                val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, canvas)
                sp?.dispatchEvent(SwingUtilities.convertMouseEvent(canvas, e, sp))
            }
        }

        // Zoom buttons
        zoomInBtn.addActionListener  { scale = (scale * 1.2).coerceAtMost(3.0);  updateLayout() }
        zoomOutBtn.addActionListener { scale = (scale / 1.2).coerceAtLeast(0.25); updateLayout() }
        zoomResetBtn.addActionListener { scale = 1.0; updateLayout() }

        val scroller = JBScrollPane(canvas).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
            verticalScrollBarPolicy   = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
            isOpaque = false
            viewport.isOpaque = false
            border = BorderFactory.createEmptyBorder()
        }
        overlay.add(scroller, BorderLayout.CENTER)

        val hint = JBLabel("Use +/- buttons or Ctrl+scroll to zoom  |  Click a card to restore  |  Restoring opens a new branch").apply {
            foreground = Color(230, 230, 230, hintAlpha)
            border = BorderFactory.createEmptyBorder(8, 2, 0, 2)
        }
        overlay.add(hint, BorderLayout.SOUTH)

        return overlay
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

                // find editor (document 的 editor）
                val editor = findEditorForDocument(event.document) ?: FileEditorManager.getInstance(project).selectedTextEditor
                val caretLine = editor?.caretModel?.primaryCaret?.logicalPosition?.line

                //
                if (caretLine == null) return

                // baseLine
                if (baseLine == null) baseLine = caretLine

                //  update baseLine
                if (caretLine != baseLine) {
                    baseLine = caretLine
                    val preview = capturePreview(editor)
                    TimeRailRecorder.addSnapshot(path, name, event.document.text, preview)

                    // popup
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
            //
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            comp.paint(g)
        } finally {
            g.dispose()
        }

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
        // fallback
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
    fun stop()  { recording = false }
    fun isRecording() = recording
    fun isReplaying() = replaying

    data class Snapshot(
        val id: Int,
        val parentId: Int?,   // id of snapshot we restored from (null = root of branch 0)
        val branchId: Int,
        val path: String,
        val fileName: String,
        val content: String,
        val time: String,
        val preview: ImageIcon?
    )

    private val snaps      = mutableListOf<Snapshot>()
    private var nextId     = 0
    private var nextBranch = 1
    private var curBranch  = 0
    private var curParent: Int? = null   // parentId for the next snapshot added

    fun clear() {
        snaps.clear()
        nextId     = 0
        nextBranch = 1
        curBranch  = 0
        curParent  = null
    }

    fun allSnapshots(): List<Snapshot> = snaps.toList()

    fun addSnapshot(path: String, name: String, content: String, preview: ImageIcon?) {
        val ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        snaps.add(Snapshot(nextId++, curParent, curBranch, path, name, content, ts, preview))
        curParent = null   // only the first snapshot in a new branch carries a parentId
    }

    fun applySnapshot(project: Project, s: Snapshot) {
        val vFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(s.path) ?: return
        val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return

        WriteCommandAction.runWriteCommandAction(project) {
            replaying = true
            try {
                doc.setText(s.content)
                FileDocumentManager.getInstance().saveDocument(doc)
            } finally {
                replaying = false
            }
        }

        // Open a new branch from the restored snapshot
        curBranch = nextBranch++
        curParent = s.id
    }
}