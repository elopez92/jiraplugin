package com.elopez.jiraplugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SeparatorFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI.CurrentTheme
import com.intellij.util.ui.UIUtil
import com.elopez.jiraplugin.git.BranchIssueKeyResolver
import com.elopez.jiraplugin.jira.JiraComment
import com.elopez.jiraplugin.jira.JiraFetchResult
import com.elopez.jiraplugin.jira.JiraIssueKeys
import com.elopez.jiraplugin.jira.JiraIssueListItem
import com.elopez.jiraplugin.jira.JiraIssueService
import com.elopez.jiraplugin.jira.JiraIssueView
import com.elopez.jiraplugin.jira.JiraSearchListResult
import com.elopez.jiraplugin.settings.JiraPluginConfigurable
import com.elopez.jiraplugin.settings.JiraPluginSettings
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

class JiraToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val properties = PropertiesComponent.getInstance(project)
    private val issueKeyField = JBTextField()
    private val jqlField = JBTextField()
    private val listIssuesButton = JButton("List issues", AllIcons.Actions.Find)
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
    private val branchButton = JButton("Branch", AllIcons.Vcs.Branch)
    private val openSettingsButton = JButton("Settings", AllIcons.General.Settings)

    private val backToSearchButton = JButton("Search", AllIcons.Actions.Back).apply {
        toolTipText = "Show JQL and issue list again"
        horizontalTextPosition = SwingConstants.RIGHT
        addActionListener { showBrowseMode() }
    }
    private val focusedTitleLabel = JBLabel(" ")
    private val focusedMetaLabel = JBLabel(" ")
    private val focusedRefreshButton = JButton("Refresh", AllIcons.Actions.Refresh).apply { addActionListener { loadIssue() } }
    private val focusedBranchButton = JButton("Branch", AllIcons.Vcs.Branch).apply {
        addActionListener { useGitBranch() }
        isVisible = BranchIssueKeyResolver.isGitIntegrationAvailable()
    }
    private val focusedSettingsButton = JButton("Settings", AllIcons.General.Settings).apply {
        addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JiraPluginConfigurable::class.java)
        }
    }

    private val issueListModel = DefaultListModel<JiraIssueListItem>()
    private val issueList = JBList(issueListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        background = UIUtil.getListBackground()
        fixedCellHeight = JBUI.scale(44)
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                c.border = JBUI.Borders.empty(4, 10, 4, 10)
                when (value) {
                    is JiraIssueListItem -> {
                        val fg = rgbHex(JBColor.foreground())
                        val muted = rgbHex(CurrentTheme.Label.disabledForeground())
                        val keyEsc = StringUtil.escapeXmlEntities(value.key)
                        val sumEsc = StringUtil.escapeXmlEntities(value.summary.ifBlank { "—" })
                        val stEsc = StringUtil.escapeXmlEntities(value.status)
                        c.text =
                            "<html><body style='margin:0'><div style='color:$fg;font-weight:600;'>$keyEsc</div>" +
                            "<div style='color:$muted;font-size:90%;margin-top:2px;'>$sumEsc · $stEsc</div></body></html>"
                        c.toolTipText = "${value.key} · ${value.status}"
                    }
                    else -> c.text = value?.toString() ?: ""
                }
                return c
            }
        }
    }

    private val summaryLabel = JBLabel(" ")
    private val metaLabel = JBLabel(" ")

    private val bodyPane = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKit()
        contentType = "text/html"
        background = JBColor.background()
        border = JBUI.Borders.empty(12, 14, 12, 14)
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val url = e.url?.toString() ?: return@addHyperlinkListener
                try {
                    BrowserUtil.browse(URI(url))
                } catch (_: Exception) {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI(url))
                    }
                }
            }
        }
    }

    private val bodyScroll = ScrollPaneFactory.createScrollPane(bodyPane, true).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
    }
    private val inFlight = AtomicInteger(0)

    private val northCardLayout = CardLayout()
    private val northCards = JPanel(northCardLayout)
    private lateinit var mainSplit: OnePixelSplitter
    private lateinit var listColumnPanel: JPanel
    private lateinit var detailHeaderPanel: JPanel

    init {
        border = JBUI.Borders.empty(12, 14, 14, 14)
        issueKeyField.toolTipText = "Issue key (e.g. KAN-1) or paste the issue page URL — the key is extracted automatically"
        issueKeyField.emptyText.text = "Issue key or paste /browse/… URL"
        issueKeyField.putClientProperty("JTextField.variant", "search")
        issueKeyField.text = properties.getValue(LAST_ISSUE_KEY_PROP) ?: ""
        issueKeyField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    loadIssue()
                }
            }
        })

        jqlField.toolTipText = "Jira Query Language (JQL). Example: assignee = currentUser() ORDER BY updated DESC"
        jqlField.emptyText.text = "JQL query…"
        jqlField.putClientProperty("JTextField.variant", "search")
        jqlField.text = effectiveSearchJql(properties.getValue(SEARCH_JQL_PROP) ?: "")

        branchButton.toolTipText = "Fill issue key from current Git branch (e.g. feature/PROJ-123)"
        openSettingsButton.toolTipText = "Jira site URL, email, and API token"
        listIssuesButton.toolTipText = "Run JQL and populate the issue list"
        refreshButton.toolTipText = "Load issue details for the key above"
        focusedBranchButton.toolTipText = branchButton.toolTipText
        focusedSettingsButton.toolTipText = openSettingsButton.toolTipText
        focusedRefreshButton.toolTipText = refreshButton.toolTipText

        styleIconButtons(
            refreshButton,
            listIssuesButton,
            branchButton,
            openSettingsButton,
            backToSearchButton,
            focusedRefreshButton,
            focusedBranchButton,
            focusedSettingsButton,
        )

        refreshButton.addActionListener { loadIssue() }
        listIssuesButton.addActionListener { loadIssueList() }
        branchButton.addActionListener { useGitBranch() }
        branchButton.isVisible = BranchIssueKeyResolver.isGitIntegrationAvailable()
        openSettingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, JiraPluginConfigurable::class.java)
        }

        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    openSelectedListIssue()
                }
            }
        })
        issueList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    openSelectedListIssue()
                }
            }
        })

        val browseHeader = JPanel(BorderLayout()).apply browseHeader@{
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(0, 0, 10, 0),
            )
            val stack = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(
                    JBLabel("Look up an issue").apply {
                        font = JBFont.h3().asBold()
                        foreground = CurrentTheme.Label.foreground()
                    },
                )
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                        add(issueKeyField.apply { columns = 14 })
                        add(refreshButton)
                        add(branchButton)
                        add(openSettingsButton)
                    },
                )
                add(Box.createVerticalStrut(JBUI.scale(14)))
                add(
                    SeparatorFactory.createSeparator("Search with JQL", this@browseHeader).apply {
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(
                    JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                        add(jqlField, BorderLayout.CENTER)
                        add(listIssuesButton, BorderLayout.EAST)
                    }.apply {
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
            }
            add(stack, BorderLayout.CENTER)
        }

        val listScroll = ScrollPaneFactory.createScrollPane(issueList, true).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            preferredSize = Dimension(JBUI.scale(300), JBUI.scale(240))
            minimumSize = Dimension(JBUI.scale(180), JBUI.scale(120))
        }

        val listHint = JBLabel("Matching issues · double‑click or Enter").apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            foreground = CurrentTheme.Label.disabledForeground()
        }
        listColumnPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0),
                JBUI.Borders.empty(0, 0, 0, JBUI.scale(10)),
            )
            add(listHint, BorderLayout.NORTH)
            add(listScroll, BorderLayout.CENTER)
        }

        summaryLabel.font = JBFont.h3().asBold()
        summaryLabel.foreground = CurrentTheme.Label.foreground()
        metaLabel.foreground = CurrentTheme.Label.disabledForeground()

        detailHeaderPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.empty(0, 0, 10, 0),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            )
            add(summaryLabel)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(metaLabel)
        }

        val detailColumn = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 0, 0)
            add(detailHeaderPanel, BorderLayout.NORTH)
            add(bodyScroll, BorderLayout.CENTER)
        }

        mainSplit = OnePixelSplitter(false, 0.28f).apply {
            firstComponent = listColumnPanel
            secondComponent = detailColumn
            dividerWidth = JBUI.scale(1)
        }

        focusedTitleLabel.font = JBFont.h3().asBold()
        focusedTitleLabel.foreground = CurrentTheme.Label.foreground()
        focusedMetaLabel.foreground = CurrentTheme.Label.disabledForeground()
        val focusedToolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(0, 0, 10, 0),
            )
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(backToSearchButton)
                add(focusedTitleLabel)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
                add(focusedRefreshButton)
                add(focusedBranchButton)
                add(focusedSettingsButton)
            }
            add(left, BorderLayout.WEST)
            add(focusedMetaLabel, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }

        northCards.add(browseHeader, "browse")
        northCards.add(focusedToolbar, "focused")

        add(northCards, BorderLayout.NORTH)
        add(mainSplit, BorderLayout.CENTER)

        showBrowseMode()
        showPlaceholder("Enter an issue key and Refresh, use List issues to pick from results, or Use Git branch.")
    }

    private fun showBrowseMode() {
        northCardLayout.show(northCards, "browse")
        detailHeaderPanel.isVisible = true
        if (mainSplit.firstComponent == null) {
            mainSplit.firstComponent = listColumnPanel
        }
        listColumnPanel.isVisible = true
        mainSplit.proportion = BROWSE_SPLIT_PROPORTION
        mainSplit.dividerWidth = JBUI.scale(1)
        mainSplit.validate()
        mainSplit.repaint()
        revalidate()
    }

    private fun showFocusedMode() {
        northCardLayout.show(northCards, "focused")
        detailHeaderPanel.isVisible = false
        mainSplit.firstComponent = null
        // Splitter forbids dividerWidth == 0; with no first component the divider is not shown.
        mainSplit.validate()
        mainSplit.repaint()
        revalidate()
    }

    private fun beginNetwork() {
        inFlight.incrementAndGet()
        SwingUtilities.invokeLater { applyNetworkLock() }
    }

    private fun endNetwork() {
        inFlight.decrementAndGet()
        SwingUtilities.invokeLater { applyNetworkLock() }
    }

    private fun applyNetworkLock() {
        val busy = inFlight.get() > 0
        refreshButton.isEnabled = !busy
        focusedRefreshButton.isEnabled = !busy
        listIssuesButton.isEnabled = !busy
        branchButton.isEnabled = !busy
        focusedBranchButton.isEnabled = !busy
        issueKeyField.isEnabled = !busy
        jqlField.isEnabled = !busy
        issueList.isEnabled = !busy
    }

    private fun openSelectedListIssue() {
        val item = issueList.selectedValue ?: return
        issueKeyField.text = item.key
        loadIssue()
    }

    private fun useGitBranch() {
        val key = BranchIssueKeyResolver.resolveFromCurrentBranch(project)
        if (key == null) {
            showError("No Jira-style key found on the current Git branch (expected pattern like PROJ-123).")
            return
        }
        issueKeyField.text = key
        loadIssue()
    }

    private fun loadIssue() {
        val key = JiraIssueKeys.normalizeUserInput(issueKeyField.text)
        if (key.isEmpty()) {
            showError("Enter a Jira issue key or paste the /browse/… URL from Jira.")
            return
        }
        issueKeyField.text = key
        properties.setValue(LAST_ISSUE_KEY_PROP, key)
        beginNetwork()
        showPlaceholder("Loading…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraIssueService.getInstance().fetchIssue(key)
            SwingUtilities.invokeLater {
                endNetwork()
                if (!project.isDisposed) {
                    handleFetchResult(result)
                }
            }
        }
    }

    private fun loadIssueList() {
        showBrowseMode()
        val jql = effectiveSearchJql(jqlField.text)
        jqlField.text = jql
        properties.setValue(SEARCH_JQL_PROP, jql)
        beginNetwork()
        issueListModel.clear()
        showPlaceholder("Loading issues…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraIssueService.getInstance().searchIssues(jql)
            SwingUtilities.invokeLater {
                endNetwork()
                if (!project.isDisposed) {
                    handleSearchResult(result)
                }
            }
        }
    }

    private fun handleFetchResult(result: JiraFetchResult) {
        when (result) {
            is JiraFetchResult.Ok -> {
                showFocusedMode()
                renderIssue(result.issue)
            }
            is JiraFetchResult.ConfigError -> {
                showBrowseMode()
                showError(result.message)
            }
            is JiraFetchResult.HttpError -> {
                showBrowseMode()
                showError("HTTP ${result.code}: ${result.message}")
            }
            is JiraFetchResult.NetworkError -> {
                showBrowseMode()
                showError(result.message)
            }
        }
    }

    private fun handleSearchResult(result: JiraSearchListResult) {
        when (result) {
            is JiraSearchListResult.Ok -> {
                issueListModel.clear()
                result.issues.forEach { issueListModel.addElement(it) }
                summaryLabel.text = " "
                if (result.issues.isEmpty()) {
                    showPlaceholder("No issues match this JQL. Edit the query and click List issues again.")
                } else {
                    showPlaceholder("Select an issue in the list (double‑click or Enter), or type a key above and Refresh.")
                }
                metaLabel.text = if (result.issues.isEmpty()) "0 issues" else "${result.issues.size} issue(s)"
            }
            is JiraSearchListResult.ConfigError -> showError(result.message)
            is JiraSearchListResult.HttpError -> showError("HTTP ${result.code}: ${result.message}")
            is JiraSearchListResult.NetworkError -> showError(result.message)
        }
    }

    private fun renderIssue(issue: JiraIssueView) {
        summaryLabel.text = issue.summary.ifBlank { issue.key }
        metaLabel.text = "${issue.key} · ${issue.issueType} · ${issue.status}"
        focusedTitleLabel.text = "${issue.key} — ${issue.summary.ifBlank { issue.key }}"
        focusedMetaLabel.text = "${issue.issueType} · ${issue.status}"

        val descBlock = when {
            !issue.descriptionHtml.isNullOrBlank() -> issue.descriptionHtml
            !issue.descriptionPlainFallback.isNullOrBlank() -> "<pre>${StringUtil.escapeXmlEntities(issue.descriptionPlainFallback)}</pre>"
            else -> "<p><em>No description.</em></p>"
        }

        val commentsBlock = buildCommentsHtml(issue.comments)

        val bodyInner =
            "<h2 style=\"${sectionHeadingStyle()}\">Description</h2>$descBlock$commentsBlock"

        val font = UIUtil.getLabelFont()
        val family = font.family
        val size = font.size
        val fg = rgbHex(JBColor.foreground())
        val bg = rgbHex(JBColor.background())
        val link = rgbHex(linkAccentColor())
        val wrapped = """
            <html><head><style>
              body { font-family: '$family', sans-serif; font-size: ${size}pt; line-height: 1.6; letter-spacing: 0.01em; color: $fg; background: $bg; margin: 0; }
              pre { white-space: pre-wrap; font-family: 'JetBrains Mono',monospace; font-size: ${size - 1}pt; }
              a { color: $link; text-decoration: none; }
              a:hover { text-decoration: underline; }
              img { max-width: 100%; height: auto; }
            </style></head><body>$bodyInner</body></html>
        """.trimIndent()

        bodyPane.text = wrapped
        val doc = bodyPane.document
        if (doc is HTMLDocument) {
            val base = JiraPluginSettings.getInstance().getBaseUrl()
            if (base.isNotBlank()) {
                try {
                    doc.base = URI.create(base).toURL()
                } catch (_: Exception) {
                    // ignore invalid base
                }
            }
        }
        bodyPane.caretPosition = 0
        bodyScroll.verticalScrollBar.value = 0
    }

    private fun buildCommentsHtml(comments: List<JiraComment>): String {
        if (comments.isEmpty()) {
            return "<h2 style=\"${sectionHeadingStyle()};margin-top:20px;\">Comments</h2><p style=\"color:${rgbHex(CurrentTheme.Label.disabledForeground())};\"><em>No comments yet.</em></p>"
        }
        val accent = rgbHex(linkAccentColor())
        val fill = rgbHex(ColorUtil.mix(JBColor.background(), JBColor.foreground(), 0.07))
        val muted = rgbHex(CurrentTheme.Label.disabledForeground())
        val blocks = comments.joinToString("") { c ->
            val bodyEsc = StringUtil.escapeXmlEntities(c.bodyPlain).replace("\n", "<br/>")
            """
            <div style="margin:14px 0;padding:12px 14px;border-left:3px solid $accent;border-radius:0 8px 8px 0;background:$fill;">
              <div style="margin-bottom:8px;"><strong>${StringUtil.escapeXmlEntities(c.author)}</strong>
              <span style="color:$muted;font-size:90%;"> ${StringUtil.escapeXmlEntities(c.created)}</span></div>
              <div style="line-height:1.5;">$bodyEsc</div>
            </div>
            """.trimIndent()
        }
        return "<h2 style=\"${sectionHeadingStyle()};margin-top:20px;\">Comments</h2>$blocks"
    }

    private fun showPlaceholder(html: String) {
        summaryLabel.text = " "
        metaLabel.text = " "
        focusedTitleLabel.text = " "
        focusedMetaLabel.text = " "
        val font = UIUtil.getLabelFont()
        val family = font.family
        val size = font.size
        val muted = rgbHex(CurrentTheme.Label.disabledForeground())
        val bg = rgbHex(JBColor.background())
        val esc = StringUtil.escapeXmlEntities(html)
        bodyPane.text =
            "<html><body style=\"font-family:'$family',sans-serif;font-size:${size}pt;line-height:1.6;color:$muted;background:$bg;\"><p style=\"max-width:42em;\">$esc</p></body></html>"
        bodyPane.caretPosition = 0
    }

    /** Blank stored JQL, empty text field, and the old default are treated as the current default (all visible issues). */
    private fun effectiveSearchJql(rawFieldText: String): String {
        val trimmed = rawFieldText.trim()
        val base = trimmed.ifBlank { DEFAULT_JQL }
        return if (base == LEGACY_DEFAULT_JQL) DEFAULT_JQL else base
    }

    private fun showError(message: String) {
        summaryLabel.text = " "
        metaLabel.text = " "
        focusedTitleLabel.text = " "
        focusedMetaLabel.text = " "
        val esc = StringUtil.escapeXmlEntities(message)
        val font = UIUtil.getLabelFont()
        val family = font.family
        val size = font.size
        val bg = rgbHex(JBColor.background())
        val err = rgbHex(UIUtil.getErrorForeground())
        bodyPane.text =
            "<html><body style=\"font-family:'$family',sans-serif;font-size:${size}pt;line-height:1.55;background:$bg;\"><p style=\"color:$err;max-width:42em;\">$esc</p></body></html>"
        bodyPane.caretPosition = 0
    }

    private fun rgbHex(c: Color) = String.format("#%06x", c.rgb and 0xffffff)

    private fun linkAccentColor(): Color =
        JBColor.namedColor("link.foreground", JBColor(Color(36, 116, 189), Color(88, 157, 246)))

    private fun sectionHeadingStyle(): String {
        val line = rgbHex(JBColor.border())
        val fg = rgbHex(JBColor.foreground())
        return "font-size:${UIUtil.getLabelFont().size + 1}pt;font-weight:600;color:$fg;margin:0 0 10px 0;padding-bottom:6px;border-bottom:1px solid $line;"
    }

    private fun styleIconButtons(vararg buttons: JButton) {
        buttons.forEach {
            it.margin = JBUI.insets(4, 10, 4, 12)
            it.horizontalTextPosition = SwingConstants.RIGHT
            it.iconTextGap = JBUI.scale(6)
            it.isOpaque = false
        }
    }

    companion object {
        private const val LAST_ISSUE_KEY_PROP = "jiraplugin.lastIssueKey"
        private const val SEARCH_JQL_PROP = "jiraplugin.searchJql"
        /** Matches every issue the user can browse (not limited to recently updated). */
        private const val DEFAULT_JQL = "issuekey is not EMPTY ORDER BY updated DESC"
        private const val LEGACY_DEFAULT_JQL = "updated >= -365d ORDER BY updated DESC"
        private const val BROWSE_SPLIT_PROPORTION = 0.28f
    }
}
