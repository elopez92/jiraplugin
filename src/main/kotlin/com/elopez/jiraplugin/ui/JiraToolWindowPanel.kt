package com.elopez.jiraplugin.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.icons.AllIcons
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SeparatorFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI.CurrentTheme
import com.intellij.util.ui.UIUtil
import com.elopez.jiraplugin.git.BranchIssueKeyResolver
import com.elopez.jiraplugin.git.BranchCreator
import com.elopez.jiraplugin.jira.JiraComment
import com.elopez.jiraplugin.jira.JiraFetchResult
import com.elopez.jiraplugin.jira.JiraIssueActionResult
import com.elopez.jiraplugin.jira.JiraIssueKeys
import com.elopez.jiraplugin.jira.JiraIssueListItem
import com.elopez.jiraplugin.jira.JiraIssueService
import com.elopez.jiraplugin.jira.JiraIssueView
import com.elopez.jiraplugin.jira.JiraSearchListResult
import com.elopez.jiraplugin.settings.JiraCredentialStore
import com.elopez.jiraplugin.settings.JiraPluginConfigurable
import com.elopez.jiraplugin.settings.JiraPluginSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Timer
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.KeyStroke
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

class JiraToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val properties = PropertiesComponent.getInstance(project)
    private val issueKeyField = JBTextField()
    private val jqlField = JBTextField()
    private val listIssuesButton = JButton("List issues", AllIcons.Actions.Find)
    private val loadMoreButton = JButton("Load more", AllIcons.Actions.More).apply { addActionListener { loadMoreIssueList() } }
    private val saveFilterButton = JButton("Save filter", AllIcons.General.Add).apply { addActionListener { saveCurrentFilter() } }
    private val applyFilterButton = JButton("Apply filter", AllIcons.Actions.Find).apply { addActionListener { applySelectedSavedFilter() } }
    private val favoriteFilterButton = JButton("Favorite", AllIcons.Nodes.Favorite).apply { addActionListener { toggleSelectedFilterFavorite() } }
    private val deleteFilterButton = JButton("Delete filter", AllIcons.General.Remove).apply { addActionListener { deleteSelectedFilter() } }
    private val applyRecentButton = JButton("Apply recent", AllIcons.Actions.Find).apply { addActionListener { applySelectedRecentJql() } }
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
    private val branchButton = JButton("Branch", AllIcons.Vcs.Branch)
    private val focusedCreateBranchButton = JButton("Create branch", AllIcons.Vcs.Branch).apply {
        toolTipText = "Create and checkout a new Git branch from this Jira issue"
        addActionListener { createBranchFromCurrentIssue() }
    }
    private val openSettingsButton = JButton("Settings", AllIcons.General.Settings)

    private val backToSearchButton = JButton("Search", AllIcons.Actions.Back).apply {
        toolTipText = "Show JQL and issue list again"
        horizontalTextPosition = SwingConstants.RIGHT
        addActionListener { showBrowseMode() }
    }
    private val focusedTitleLabel = JBLabel(" ")
    private val focusedMetaLabel = JBLabel(" ")
    private val focusedRefreshButton = JButton("Refresh", AllIcons.Actions.Refresh).apply { addActionListener { loadIssue() } }
    private val focusedOpenInBrowserButton = JButton("Open in browser", AllIcons.General.Web).apply {
        toolTipText = "Open this issue in Jira in the browser"
        addActionListener { openCurrentIssueInBrowser() }
    }
    private val focusedCopyKeyButton = JButton("Copy key", AllIcons.Actions.Copy).apply {
        toolTipText = "Copy issue key to clipboard"
        addActionListener { copyCurrentIssueKeyToClipboard() }
    }
    private val focusedAssignButton = JButton("Assign to me", AllIcons.General.User).apply { addActionListener { assignToMe() } }
    private val focusedWatchButton = JButton("Watch", AllIcons.General.Add).apply { addActionListener { watchIssue() } }
    private val focusedUnwatchButton = JButton("Unwatch", AllIcons.General.Remove).apply { addActionListener { unwatchIssue() } }
    private val focusedCommentButton = JButton("Comment", AllIcons.Toolwindows.ToolWindowMessages).apply { addActionListener { addComment() } }
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
    }

    private val summaryLabel = JBLabel(" ")
    private val metaLabel = JBLabel(" ")

    private val bodyPane = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKit()
        contentType = "text/html"
        background = JBColor.background()
        border = JBUI.Borders.empty(6, 14, 12, 14)
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

    /** Stack browse vs focused headers; visibility toggles avoid CardLayout reserving height of the tall browse card. */
    private val northStrip = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private lateinit var browseHeader: JPanel
    private lateinit var focusedToolbar: JPanel
    private lateinit var mainSplit: OnePixelSplitter
    private lateinit var leftTabbedPane: JBTabbedPane
    private lateinit var browseListPanel: JPanel
    private lateinit var detailHeaderPanel: JPanel
    private lateinit var jqlSectionPanel: JPanel
    private lateinit var toggleJqlSectionButton: JButton
    private lateinit var quickScopeCombo: JComboBox<QuickScope>
    private lateinit var quickSortCombo: JComboBox<QuickSort>
    private lateinit var applyQuickSearchButton: JButton
    private val notificationIssueListModel = DefaultListModel<JiraIssueListItem>()
    private val notificationIssueList = JBList(notificationIssueListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 12
        background = UIUtil.getListBackground()
        fixedCellHeight = JBUI.scale(44)
    }
    private lateinit var notificationFeedCombo: JComboBox<NotificationFeed>
    private lateinit var notificationRefreshButton: JButton
    private lateinit var notificationMarkReadButton: JButton
    private lateinit var notificationIntervalCombo: JComboBox<NotificationIntervalOption>
    private lateinit var notificationStatusLabel: JBLabel
    private var notificationAutoRefreshTimer: Timer? = null
    private var currentIssueKey: String? = null
    private var currentIssueIsWatching: Boolean? = null
    private var currentIssueAssignedToMe: Boolean? = null
    private val savedFilters = mutableListOf<SavedFilter>()
    private val savedFilterModel = DefaultComboBoxModel<SavedFilter>()
    private val savedFilterCombo = JComboBox(savedFilterModel)
    private val recentJqlModel = DefaultComboBoxModel<String>()
    private val recentJqlCombo = JComboBox(recentJqlModel)
    private var currentSearchLimit = SEARCH_PAGE_SIZE

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
        loadMoreButton.toolTipText = "Load additional issues for current JQL"
        refreshButton.toolTipText = "Load issue details for the key above"
        saveFilterButton.toolTipText = "Save current JQL as a named filter"
        applyFilterButton.toolTipText = "Apply the selected saved filter"
        favoriteFilterButton.toolTipText = "Mark selected filter as favorite"
        deleteFilterButton.toolTipText = "Delete selected saved filter"
        applyRecentButton.toolTipText = "Apply the selected recent JQL"
        focusedBranchButton.toolTipText = branchButton.toolTipText
        focusedSettingsButton.toolTipText = openSettingsButton.toolTipText
        focusedRefreshButton.toolTipText = refreshButton.toolTipText
        focusedOpenInBrowserButton.toolTipText = "Open this issue in Jira in the browser"
        focusedCopyKeyButton.toolTipText = "Copy issue key to clipboard"
        focusedAssignButton.toolTipText = "Assign selected issue to your Atlassian account"
        focusedWatchButton.toolTipText = "Start watching selected issue"
        focusedUnwatchButton.toolTipText = "Stop watching selected issue"
        focusedCommentButton.toolTipText = "Add a comment to selected issue"

        styleIconButtons(
            refreshButton,
            listIssuesButton,
            loadMoreButton,
            saveFilterButton,
            applyFilterButton,
            favoriteFilterButton,
            deleteFilterButton,
            applyRecentButton,
            branchButton,
            openSettingsButton,
            backToSearchButton,
            focusedRefreshButton,
            focusedOpenInBrowserButton,
            focusedCopyKeyButton,
            focusedAssignButton,
            focusedWatchButton,
            focusedUnwatchButton,
            focusedCommentButton,
            focusedBranchButton,
            focusedSettingsButton,
        )
        loadMoreButton.isEnabled = false

        savedFilterCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                val filter = value as? SavedFilter
                c.text = if (filter == null) "No saved filters" else if (filter.favorite) "★ ${filter.name}" else filter.name
                return c
            }
        }
        loadSavedFilters()
        loadRecentJql()

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
        issueList.cellRenderer = createIssueListCellRenderer { false }

        notificationIssueList.cellRenderer = createIssueListCellRenderer { isNotificationUnread(it) }
        notificationIssueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    openNotificationSelectedIssue()
                }
            }
        })
        notificationIssueList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    openNotificationSelectedIssue()
                }
            }
        })

        browseHeader = JPanel(BorderLayout()).apply browseHeader@{
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
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                        add(issueKeyField.apply { columns = 14 })
                        add(refreshButton)
                        add(branchButton)
                        add(openSettingsButton)
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
                        add(JBLabel("Include:"))
                        quickScopeCombo = JComboBox(QuickScope.entries.toTypedArray()).apply {
                            toolTipText = "Simple filter — builds a JQL query for the issue list"
                            setRenderer(quickScopeComboRenderer())
                            maximumSize = Dimension(JBUI.scale(200), preferredSize.height)
                        }
                        add(quickScopeCombo)
                        add(JBLabel("Sort:"))
                        quickSortCombo = JComboBox(QuickSort.entries.toTypedArray()).apply {
                            toolTipText = "Sort order for the issue list"
                            setRenderer(quickSortComboRenderer())
                            maximumSize = Dimension(JBUI.scale(200), preferredSize.height)
                        }
                        add(quickSortCombo)
                        applyQuickSearchButton = JButton("Search list", AllIcons.Actions.Find).apply {
                            toolTipText = "Run search with the options above (same as List issues)"
                            addActionListener { applyQuickSearchFromUi() }
                        }
                        add(applyQuickSearchButton)
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(Box.createVerticalStrut(JBUI.scale(8)))
                add(
                    JPanel(BorderLayout()).apply {
                        add(
                            SeparatorFactory.createSeparator("Advanced: JQL & saved filters", this@browseHeader),
                            BorderLayout.CENTER,
                        )
                        toggleJqlSectionButton = JButton().apply {
                            toolTipText = "Show or hide JQL, saved filters, and presets"
                            addActionListener {
                                setJqlSectionVisible(!jqlSectionPanel.isVisible)
                            }
                        }
                        add(
                            JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                                add(toggleJqlSectionButton)
                                isOpaque = false
                            },
                            BorderLayout.EAST,
                        )
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        alignmentX = Component.LEFT_ALIGNMENT
                    },
                )
                add(Box.createVerticalStrut(JBUI.scale(8)))
                jqlSectionPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    alignmentX = Component.LEFT_ALIGNMENT
                    add(
                        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                            add(jqlField, BorderLayout.CENTER)
                            add(
                                JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                                    add(listIssuesButton)
                                    add(loadMoreButton)
                                },
                                BorderLayout.EAST,
                            )
                            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(
                        JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                            add(
                                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                                    add(JBLabel("Saved:"))
                                    add(savedFilterCombo.apply { preferredSize = Dimension(JBUI.scale(180), preferredSize.height) })
                                    add(applyFilterButton)
                                    add(saveFilterButton)
                                    add(favoriteFilterButton)
                                    add(deleteFilterButton)
                                },
                                BorderLayout.WEST,
                            )
                            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(
                        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                            add(JBLabel("Recent:"))
                            add(recentJqlCombo.apply { preferredSize = Dimension(JBUI.scale(320), preferredSize.height) })
                            add(applyRecentButton)
                            add(JButton("Assigned to me").apply {
                                addActionListener { applyPresetAndSearch("assignee = currentUser() ORDER BY updated DESC") }
                            })
                            add(JButton("Updated today").apply {
                                addActionListener { applyPresetAndSearch("updated >= startOfDay() ORDER BY updated DESC") }
                            })
                            add(JButton("Open bugs").apply {
                                addActionListener { applyPresetAndSearch("issuetype = Bug AND statusCategory != Done ORDER BY updated DESC") }
                            })
                            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                            alignmentX = Component.LEFT_ALIGNMENT
                        },
                    )
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                }
                add(jqlSectionPanel)
            }
            add(stack, BorderLayout.CENTER)
        }
        setJqlSectionVisible(!properties.getBoolean(JQL_SECTION_COLLAPSED_PROP, true))

        val listScroll = ScrollPaneFactory.createScrollPane(issueList, true).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            preferredSize = Dimension(JBUI.scale(300), JBUI.scale(240))
            minimumSize = Dimension(JBUI.scale(180), JBUI.scale(120))
        }

        val listHint = JBLabel("Matching issues · double‑click or Enter").apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            foreground = CurrentTheme.Label.disabledForeground()
        }
        browseListPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0),
                JBUI.Borders.empty(0, 0, 0, JBUI.scale(10)),
            )
            add(listHint, BorderLayout.NORTH)
            add(listScroll, BorderLayout.CENTER)
        }

        notificationStatusLabel = JBLabel(" ").apply {
            foreground = CurrentTheme.Label.disabledForeground()
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }
        notificationFeedCombo = JComboBox(NotificationFeed.entries.toTypedArray()).apply {
            toolTipText = "Notification source"
            setRenderer(notificationFeedComboRenderer())
            addActionListener {
                if (::leftTabbedPane.isInitialized && leftTabbedPane.selectedIndex == 1) {
                    refreshNotificationFeed()
                }
            }
        }
        notificationRefreshButton = JButton("Refresh", AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh this notification feed"
            addActionListener { refreshNotificationFeed() }
        }
        notificationMarkReadButton = JButton("Mark all read").apply {
            toolTipText = "Treat current results as read (clears bold unread styling and tab badge)"
            addActionListener { markNotificationsRead() }
        }
        notificationIntervalCombo = JComboBox(NotificationIntervalOption.entries.toTypedArray()).apply {
            toolTipText = "How often to refresh the active feed while the IDE is open"
            setRenderer(notificationIntervalComboRenderer())
            selectedItem = loadNotificationIntervalFromProperties()
            addActionListener {
                saveNotificationIntervalFromProperties()
                restartNotificationAutoRefreshTimer()
            }
        }
        val notificationToolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 0, 8, 0)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
                    add(JBLabel("Feed:"))
                    add(notificationFeedCombo)
                    add(notificationRefreshButton)
                    add(notificationMarkReadButton)
                },
            )
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
                    add(JBLabel("Auto-refresh:"))
                    add(notificationIntervalCombo)
                },
            )
            add(notificationStatusLabel)
        }
        val notificationListHint = JBLabel("Notification issues · double‑click or Enter").apply {
            border = JBUI.Borders.empty(0, 0, 6, 0)
            foreground = CurrentTheme.Label.disabledForeground()
        }
        val notificationListScroll = ScrollPaneFactory.createScrollPane(notificationIssueList, true).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            preferredSize = Dimension(JBUI.scale(300), JBUI.scale(240))
            minimumSize = Dimension(JBUI.scale(180), JBUI.scale(120))
        }
        val notificationListPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0),
                JBUI.Borders.empty(0, 0, 0, JBUI.scale(10)),
            )
            add(notificationToolbar, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(notificationListHint, BorderLayout.NORTH)
                    add(notificationListScroll, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
        }

        leftTabbedPane = JBTabbedPane().apply {
            addTab("Issues", browseListPanel)
            addTab("Notifications", notificationListPanel)
            addChangeListener {
                if (selectedIndex == 1 && isJiraConfigured()) {
                    refreshNotificationFeed()
                }
            }
        }
        styleIconButtons(applyQuickSearchButton, notificationRefreshButton)

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
            firstComponent = leftTabbedPane
            secondComponent = detailColumn
            dividerWidth = JBUI.scale(1)
        }

        focusedTitleLabel.font = JBFont.h3().asBold()
        focusedTitleLabel.foreground = CurrentTheme.Label.foreground()
        focusedMetaLabel.foreground = CurrentTheme.Label.disabledForeground()
        focusedToolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(0, 0, 4, 0),
            )
            val topRow = JPanel(BorderLayout()).apply {
                val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    add(backToSearchButton)
                    add(focusedTitleLabel)
                    isOpaque = false
                }
                add(left, BorderLayout.WEST)
                add(focusedMetaLabel, BorderLayout.CENTER)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }
            val actionRow = JPanel(WrapFlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(6))).apply {
                add(focusedRefreshButton)
                add(focusedOpenInBrowserButton)
                add(focusedCopyKeyButton)
                add(focusedAssignButton)
                add(focusedWatchButton)
                add(focusedUnwatchButton)
                add(focusedCommentButton)
                add(focusedBranchButton)
                add(focusedCreateBranchButton)
                add(focusedSettingsButton)
                maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }
            add(topRow)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(actionRow)
        }

        northStrip.add(browseHeader)
        northStrip.add(focusedToolbar)
        focusedToolbar.isVisible = false
        browseHeader.alignmentX = Component.LEFT_ALIGNMENT
        focusedToolbar.alignmentX = Component.LEFT_ALIGNMENT

        add(northStrip, BorderLayout.NORTH)
        add(mainSplit, BorderLayout.CENTER)
        registerQualityOfLifeShortcuts()
        restartNotificationAutoRefreshTimer()

        showBrowseMode()
        if (isJiraConfigured()) {
            SwingUtilities.invokeLater {
                if (!project.isDisposed) {
                    loadIssueList()
                }
            }
        } else {
            showPlaceholder(
                "Set up Jira in Settings | Tools | Jira Companion (site URL, email, API token). " +
                    "Then the issue list loads automatically; you can refine it with JQL or presets.",
            )
        }
    }

    private fun isJiraConfigured(): Boolean {
        val settings = JiraPluginSettings.getInstance()
        return settings.getBaseUrl().isNotBlank() &&
            settings.getEmail().isNotBlank() &&
            !JiraCredentialStore.getToken().isNullOrBlank()
    }

    private fun showBrowseMode() {
        browseHeader.isVisible = true
        focusedToolbar.isVisible = false
        detailHeaderPanel.isVisible = true
        if (mainSplit.firstComponent == null) {
            mainSplit.firstComponent = leftTabbedPane
        }
        leftTabbedPane.isVisible = true
        mainSplit.proportion = BROWSE_SPLIT_PROPORTION
        mainSplit.dividerWidth = JBUI.scale(1)
        mainSplit.validate()
        mainSplit.repaint()
        revalidate()
    }

    private fun showFocusedMode() {
        browseHeader.isVisible = false
        focusedToolbar.isVisible = true
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
        focusedOpenInBrowserButton.isEnabled = !busy
        focusedCopyKeyButton.isEnabled = !busy
        listIssuesButton.isEnabled = !busy
        loadMoreButton.isEnabled = !busy
        saveFilterButton.isEnabled = !busy
        applyFilterButton.isEnabled = !busy
        favoriteFilterButton.isEnabled = !busy
        deleteFilterButton.isEnabled = !busy
        applyRecentButton.isEnabled = !busy
        branchButton.isEnabled = !busy
        focusedBranchButton.isEnabled = !busy
        focusedCreateBranchButton.isEnabled = !busy
        updateAssignButtonState()
        focusedWatchButton.isEnabled = !busy
        focusedUnwatchButton.isEnabled = !busy
        focusedCommentButton.isEnabled = !busy
        issueKeyField.isEnabled = !busy
        jqlField.isEnabled = !busy
        savedFilterCombo.isEnabled = !busy
        recentJqlCombo.isEnabled = !busy
        issueList.isEnabled = !busy
        notificationIssueList.isEnabled = !busy
        notificationRefreshButton.isEnabled = !busy
        notificationFeedCombo.isEnabled = !busy
        notificationIntervalCombo.isEnabled = !busy
        notificationMarkReadButton.isEnabled = !busy
        quickScopeCombo.isEnabled = !busy
        quickSortCombo.isEnabled = !busy
        applyQuickSearchButton.isEnabled = !busy
        if (::leftTabbedPane.isInitialized) {
            leftTabbedPane.isEnabled = !busy
        }
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
        currentIssueKey = key
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
        currentSearchLimit = SEARCH_PAGE_SIZE
        loadIssueListInternal(currentSearchLimit)
    }

    private fun loadMoreIssueList() {
        val jql = effectiveSearchJql(jqlField.text)
        if (jql.isBlank()) return
        currentSearchLimit += SEARCH_PAGE_SIZE
        loadIssueListInternal(currentSearchLimit)
    }

    private fun loadIssueListInternal(maxResults: Int) {
        showBrowseMode()
        val jql = effectiveSearchJql(jqlField.text)
        jqlField.text = jql
        properties.setValue(SEARCH_JQL_PROP, jql)
        pushRecentJql(jql)
        beginNetwork()
        issueListModel.clear()
        showPlaceholder("Loading issues…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = JiraIssueService.getInstance().searchIssues(jql, maxResults = maxResults)
            SwingUtilities.invokeLater {
                endNetwork()
                if (!project.isDisposed) {
                    handleSearchResult(result, maxResults)
                }
            }
        }
    }

    private fun handleFetchResult(result: JiraFetchResult) {
        when (result) {
            is JiraFetchResult.Ok -> {
                showFocusedMode()
                currentIssueKey = result.issue.key
                renderIssue(result.issue)
            }
            is JiraFetchResult.ConfigError -> {
                showBrowseMode()
                currentIssueKey = null
                currentIssueIsWatching = null
                currentIssueAssignedToMe = null
                updateWatchButtons()
                updateAssignButtonState()
                showError(result.message)
            }
            is JiraFetchResult.HttpError -> {
                showBrowseMode()
                currentIssueKey = null
                currentIssueIsWatching = null
                currentIssueAssignedToMe = null
                updateWatchButtons()
                updateAssignButtonState()
                showError("HTTP ${result.code}: ${result.message}")
            }
            is JiraFetchResult.NetworkError -> {
                showBrowseMode()
                currentIssueKey = null
                currentIssueIsWatching = null
                currentIssueAssignedToMe = null
                updateWatchButtons()
                updateAssignButtonState()
                showError(result.message)
            }
        }
    }

    private fun handleSearchResult(result: JiraSearchListResult, maxResults: Int) {
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
                metaLabel.text = if (result.issues.isEmpty()) "0 issues" else "${result.issues.size} issue(s) (max $maxResults)"
                loadMoreButton.isEnabled = result.issues.isNotEmpty()
            }
            is JiraSearchListResult.ConfigError -> {
                loadMoreButton.isEnabled = false
                showError(result.message)
            }
            is JiraSearchListResult.HttpError -> {
                loadMoreButton.isEnabled = false
                showError("HTTP ${result.code}: ${result.message}")
            }
            is JiraSearchListResult.NetworkError -> {
                loadMoreButton.isEnabled = false
                showError(result.message)
            }
        }
    }
    
    private fun createBranchFromCurrentIssue() {
        val key = currentIssueKey ?: JiraIssueKeys.normalizeUserInput(issueKeyField.text)
        if (key.isBlank()) {
            showStatusFeedback("Load an issue first.")
            return
        }
        val summary = focusedTitleLabel.text
            ?.substringAfter("—", "")
            ?.trim()
            .orEmpty()
        val suggested = BranchCreator.suggestBranchName(key, summary)
        val chosenName = Messages.showInputDialog(
            project,
            "Edit branch name before creation:",
            "Create Branch from Jira Issue",
            Messages.getQuestionIcon(),
            suggested,
            null,
        ) ?: return
        val trimmed = chosenName.trim()
        if (trimmed.isBlank()) {
            showStatusFeedback("Branch creation cancelled: empty branch name.")
            return
        }

        val result = BranchCreator.createAndCheckout(project, trimmed)
        result
            .onSuccess { branch ->
                showStatusFeedback("Created and checked out: $branch")
            }
            .onFailure { e ->
                showError(e.message ?: "Could not create branch.")
            }
    }

    private fun renderIssue(issue: JiraIssueView) {
        summaryLabel.text = issue.summary.ifBlank { issue.key }
        metaLabel.text = formatIssueMetaPrimary(issue)
        focusedTitleLabel.text = "${issue.key} — ${issue.summary.ifBlank { issue.key }}"
        focusedMetaLabel.text = formatIssueMetaSecondary(issue)
        currentIssueIsWatching = issue.isWatching
        currentIssueAssignedToMe = JiraIssueService.getInstance().isIssueAssignedToMe(issue)
        updateWatchButtons()
        updateAssignButtonState()

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
              body { font-family: '$family', sans-serif; font-size: ${size}pt; line-height: 1.6; color: $fg; background: $bg; margin: 0; }
              pre { white-space: pre-wrap; font-family: 'JetBrains Mono',monospace; font-size: ${size - 1}pt; }
              a { color: $link; text-decoration: none; }
              a:hover { text-decoration: underline; }
              img { max-width: 100%; height: auto; }
              .comment-card { margin:14px 0; padding:12px 14px; border-left:3px solid $link; background:${rgbHex(ColorUtil.mix(JBColor.background(), JBColor.foreground(), 0.07))}; }
              .comment-meta { margin-bottom:8px; }
              .comment-date { color:${rgbHex(CurrentTheme.Label.disabledForeground())}; font-size:90%; }
              .comment-mention { color:$link; font-weight:600; }
              .comment-body p { margin:0 0 8px 0; }
              .comment-body p:last-child { margin-bottom:0; }
              .comment-body code { font-family:'JetBrains Mono',monospace; font-size:${size - 1}pt; background:${rgbHex(ColorUtil.mix(JBColor.background(), JBColor.foreground(), 0.10))}; padding:1px 4px; }
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
        val blocks = comments.joinToString("") { c ->
            val bodyHtml = renderCommentBodyHtml(c.bodyPlain)
            val authorEsc = StringUtil.escapeXmlEntities(c.author)
            val createdEsc = StringUtil.escapeXmlEntities(c.created)
            val collapsed = shouldCollapseComment(c.bodyPlain)
            if (collapsed) {
                """
                <div class="comment-card">
                  <div class="comment-meta"><strong>$authorEsc</strong>
                  <span class="comment-date"> $createdEsc</span></div>
                  <div class="comment-body">${commentPreviewHtml(c.bodyPlain)}</div>
                  <p style="margin-top:8px;color:${rgbHex(CurrentTheme.Label.disabledForeground())};font-size:90%;">
                    Long comment preview shown. Open the issue in Jira to view the full thread text.
                  </p>
                </div>
                """.trimIndent()
            } else {
                """
                <div class="comment-card">
                  <div class="comment-meta"><strong>$authorEsc</strong>
                  <span class="comment-date"> $createdEsc</span></div>
                  <div class="comment-body">$bodyHtml</div>
                </div>
                """.trimIndent()
            }
        }
        return "<h2 style=\"${sectionHeadingStyle()};margin-top:20px;\">Comments</h2>$blocks"
    }

    private fun shouldCollapseComment(body: String): Boolean {
        val lines = body.lines().size
        return body.length > COMMENT_COLLAPSE_CHARS || lines > COMMENT_COLLAPSE_LINES
    }

    private fun commentPreviewHtml(body: String): String {
        val preview = body.lines().take(4).joinToString("\n").take(COMMENT_PREVIEW_CHARS).trim()
        return renderCommentBodyHtml(if (preview.isBlank()) "(empty)" else "$preview…")
    }

    private fun renderCommentBodyHtml(bodyPlain: String): String {
        val chunks = splitFenceCodeBlocks(bodyPlain)
        if (chunks.isEmpty()) return "<p><em>(empty)</em></p>"
        return chunks.joinToString("") { part ->
            if (part.isCode) {
                "<pre>${StringUtil.escapeXmlEntities(part.text)}</pre>"
            } else {
                formatCommentTextChunk(part.text)
            }
        }
    }

    private fun formatCommentTextChunk(text: String): String {
        val paragraphs = text.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return ""
        return paragraphs.joinToString("") { para ->
            val escaped = StringUtil.escapeXmlEntities(para)
            val withMentions = escaped.replace(Regex("""\[\~([^\]]+)]""")) {
                "<span class=\"comment-mention\">@${it.groupValues[1]}</span>"
            }
            val withInlineCode = withMentions.replace(Regex("`([^`\\n]+)`")) { m ->
                "<code>${m.groupValues[1]}</code>"
            }
            val withLinks = withInlineCode.replace(Regex("""(https?://[^\s<]+)""")) { m ->
                val url = m.groupValues[1]
                "<a href=\"$url\">$url</a>"
            }
            "<p>${withLinks.replace("\n", "<br/>")}</p>"
        }
    }

    private data class TextChunk(val text: String, val isCode: Boolean)

    private fun splitFenceCodeBlocks(raw: String): List<TextChunk> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<TextChunk>()
        val parts = raw.replace("\r\n", "\n").split("```")
        parts.forEachIndexed { idx, s ->
            if (s.isBlank()) return@forEachIndexed
            val isCode = idx % 2 == 1
            val codeText = if (isCode) s.lines().dropWhile { it.trim().isNotEmpty() && !it.contains(' ') && it.length < 20 }.joinToString("\n").trim().ifBlank { s.trim() } else s
            out.add(TextChunk(if (isCode) codeText else s, isCode))
        }
        return out
    }

    private fun showPlaceholder(html: String) {
        if (html.startsWith("Enter an issue key")) {
            currentIssueKey = null
            currentIssueIsWatching = null
            currentIssueAssignedToMe = null
            updateWatchButtons()
            updateAssignButtonState()
        }
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
        currentIssueKey = null
        currentIssueIsWatching = null
        currentIssueAssignedToMe = null
        updateWatchButtons()
        updateAssignButtonState()
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

    private fun setJqlSectionVisible(visible: Boolean) {
        jqlSectionPanel.isVisible = visible
        toggleJqlSectionButton.text = if (visible) "Hide" else "Show"
        toggleJqlSectionButton.icon = if (visible) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent
        properties.setValue(JQL_SECTION_COLLAPSED_PROP, (!visible).toString())
        revalidate()
        repaint()
    }

    private fun assignToMe() {
        if (currentIssueAssignedToMe == true) {
            showStatusFeedback("Issue is already assigned to you.")
            updateAssignButtonState()
            return
        }
        runIssueAction(
            progressMessage = "Assigning issue to you…",
            successReload = true,
            action = { key -> JiraIssueService.getInstance().assignIssueToMe(key) },
            onSuccess = {
                currentIssueAssignedToMe = true
                updateAssignButtonState()
            },
        )
    }

    private fun watchIssue() {
        runIssueAction(
            progressMessage = "Adding watcher…",
            successReload = false,
            action = ::watchIssueAction,
            onSuccess = {
                currentIssueIsWatching = true
                updateWatchButtons()
            },
        )
    }

    private fun unwatchIssue() {
        runIssueAction(
            progressMessage = "Removing watcher…",
            successReload = false,
            action = ::unwatchIssueAction,
            onSuccess = {
                currentIssueIsWatching = false
                updateWatchButtons()
            },
        )
    }

    private fun addComment() {
        val key = currentIssueKey
            ?: JiraIssueKeys.normalizeUserInput(issueKeyField.text).ifBlank { "" }
        if (key.isBlank()) {
            showStatusFeedback("Load an issue first.")
            return
        }
        val input = Messages.showMultilineInputDialog(
            project,
            "Add comment to $key",
            "Jira Comment",
            "",
            null,
            null,
        ) ?: return
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            showStatusFeedback("Comment not added: empty text.")
            return
        }
        runIssueAction(
            progressMessage = "Adding comment…",
            successReload = true,
            action = { issueKey -> JiraIssueService.getInstance().addComment(issueKey, trimmed) },
        )
    }

    private fun runIssueAction(
        progressMessage: String,
        successReload: Boolean,
        action: (String) -> JiraIssueActionResult,
        onSuccess: (() -> Unit)? = null,
    ) {
        val key = currentIssueKey
            ?: JiraIssueKeys.normalizeUserInput(issueKeyField.text).ifBlank { "" }
        if (key.isBlank()) {
            showStatusFeedback("Load an issue first.")
            return
        }
        beginNetwork()
        showStatusFeedback(progressMessage)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = action(key)
            SwingUtilities.invokeLater {
                endNetwork()
                if (project.isDisposed) return@invokeLater
                when (result) {
                    is JiraIssueActionResult.Ok -> {
                        onSuccess?.invoke()
                        showStatusFeedback(result.message)
                        if (successReload) {
                            loadIssue()
                        }
                    }
                    is JiraIssueActionResult.ConfigError -> showError(result.message)
                    is JiraIssueActionResult.HttpError -> showError("HTTP ${result.code}: ${result.message}")
                    is JiraIssueActionResult.NetworkError -> showError(result.message)
                }
            }
        }
    }

    private fun showStatusFeedback(message: String) {
        WindowManager.getInstance().getStatusBar(project)?.info = message
        focusedMetaLabel.text = message
    }

    private fun watchIssueAction(key: String): JiraIssueActionResult =
        JiraIssueService.getInstance().addWatcher(key)

    private fun unwatchIssueAction(key: String): JiraIssueActionResult =
        JiraIssueService.getInstance().removeWatcher(key)

    private fun updateWatchButtons() {
        val watching = currentIssueIsWatching ?: false
        focusedWatchButton.isVisible = !watching
        focusedUnwatchButton.isVisible = watching
        focusedWatchButton.revalidate()
        focusedUnwatchButton.revalidate()
        focusedWatchButton.repaint()
        focusedUnwatchButton.repaint()
    }

    private fun updateAssignButtonState() {
        val busy = inFlight.get() > 0
        val assignedToMe = currentIssueAssignedToMe == true
        focusedAssignButton.isEnabled = !busy && !assignedToMe
        focusedAssignButton.toolTipText = if (assignedToMe) {
            "Already assigned to you"
        } else {
            "Assign selected issue to your Atlassian account"
        }
    }

    private fun assigneeSummary(issue: JiraIssueView): String =
        issue.assigneeDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: "Unassigned"

    private fun formatIssueMetaPrimary(issue: JiraIssueView): String =
        "${issue.key} · ${issue.issueType} · ${issue.status} · ${assigneeSummary(issue)}"

    private fun formatIssueMetaSecondary(issue: JiraIssueView): String =
        "${issue.issueType} · ${issue.status} · ${assigneeSummary(issue)}"

    private fun issueBrowseUri(issueKey: String): URI? {
        val base = JiraPluginSettings.getInstance().getBaseUrl().trim().trimEnd('/')
        if (base.isBlank()) return null
        val key = issueKey.trim()
        if (key.isBlank()) return null
        return try {
            URI.create("$base/browse/$key")
        } catch (_: Exception) {
            null
        }
    }

    private fun openCurrentIssueInBrowser() {
        val key = currentIssueKey
            ?: JiraIssueKeys.normalizeUserInput(issueKeyField.text).trim().takeIf { it.isNotBlank() }
        if (key.isNullOrBlank()) {
            showStatusFeedback("Load an issue first.")
            return
        }
        val uri = issueBrowseUri(key) ?: run {
            showStatusFeedback("Set your Jira site URL in Settings | Tools | Jira Companion.")
            return
        }
        BrowserUtil.browse(uri)
    }

    private fun copyCurrentIssueKeyToClipboard() {
        val key = currentIssueKey
            ?: JiraIssueKeys.normalizeUserInput(issueKeyField.text).trim()
        if (key.isBlank()) {
            showStatusFeedback("No issue key to copy.")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(key))
        showStatusFeedback("Copied $key")
    }

    private fun applyPresetAndSearch(jql: String) {
        jqlField.text = jql
        loadIssueList()
    }

    private fun applySelectedRecentJql() {
        val selected = recentJqlCombo.selectedItem as? String ?: return
        jqlField.text = selected
        loadIssueList()
    }

    private fun applySelectedSavedFilter() {
        val selected = savedFilterCombo.selectedItem as? SavedFilter ?: return
        jqlField.text = selected.jql
        loadIssueList()
    }

    private fun saveCurrentFilter() {
        val jql = effectiveSearchJql(jqlField.text)
        if (jql.isBlank()) {
            showStatusFeedback("Cannot save empty JQL.")
            return
        }
        val suggestedName = when {
            jql.length > 32 -> jql.substring(0, 32) + "…"
            else -> jql
        }
        val name = Messages.showInputDialog(
            project,
            "Filter name:",
            "Save JQL Filter",
            Messages.getQuestionIcon(),
            suggestedName,
            null,
        )?.trim().orEmpty()
        if (name.isBlank()) return
        val existing = savedFilters.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            existing.jql = jql
            showStatusFeedback("Updated filter: $name")
        } else {
            savedFilters.add(SavedFilter(name = name, jql = jql))
            showStatusFeedback("Saved filter: $name")
        }
        persistSavedFilters()
        refreshSavedFilterModel(selectName = name)
    }

    private fun toggleSelectedFilterFavorite() {
        val selected = savedFilterCombo.selectedItem as? SavedFilter ?: return
        selected.favorite = !selected.favorite
        persistSavedFilters()
        refreshSavedFilterModel(selectName = selected.name)
    }

    private fun deleteSelectedFilter() {
        val selected = savedFilterCombo.selectedItem as? SavedFilter ?: return
        val ok = Messages.showYesNoDialog(
            project,
            "Delete saved filter \"${selected.name}\"?",
            "Delete Filter",
            Messages.getQuestionIcon(),
        )
        if (ok != Messages.YES) return
        savedFilters.removeIf { it.name == selected.name }
        persistSavedFilters()
        refreshSavedFilterModel(selectName = null)
        showStatusFeedback("Deleted filter: ${selected.name}")
    }

    private fun pushRecentJql(jql: String) {
        if (jql.isBlank()) return
        val items = mutableListOf<String>()
        items.add(jql)
        for (i in 0 until recentJqlModel.size) {
            val v = recentJqlModel.getElementAt(i)
            if (!v.equals(jql, ignoreCase = true)) {
                items.add(v)
            }
            if (items.size >= RECENT_JQL_LIMIT) break
        }
        recentJqlModel.removeAllElements()
        items.forEach { recentJqlModel.addElement(it) }
        properties.setValue(RECENT_JQL_PROP, items.joinToString("\n"))
    }

    private fun loadRecentJql() {
        recentJqlModel.removeAllElements()
        val raw = properties.getValue(RECENT_JQL_PROP).orEmpty()
        raw.split('\n').map { it.trim() }.filter { it.isNotBlank() }.take(RECENT_JQL_LIMIT).forEach {
            recentJqlModel.addElement(it)
        }
    }

    private fun loadSavedFilters() {
        savedFilters.clear()
        val raw = properties.getValue(SAVED_FILTERS_PROP).orEmpty()
        if (raw.isBlank()) {
            refreshSavedFilterModel(selectName = null)
            return
        }
        try {
            val arr = JsonParser.parseString(raw).asJsonArray
            for (el in arr) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val name = o.get("name")?.asString?.trim().orEmpty()
                val jql = o.get("jql")?.asString?.trim().orEmpty()
                if (name.isBlank() || jql.isBlank()) continue
                val favorite = o.get("favorite")?.asBoolean ?: false
                savedFilters.add(SavedFilter(name = name, jql = jql, favorite = favorite))
            }
        } catch (_: Exception) {
            // ignore invalid stored JSON and reset model
        }
        refreshSavedFilterModel(selectName = null)
    }

    private fun persistSavedFilters() {
        val arr = JsonArray()
        savedFilters.forEach {
            arr.add(JsonObject().apply {
                addProperty("name", it.name)
                addProperty("jql", it.jql)
                addProperty("favorite", it.favorite)
            })
        }
        properties.setValue(SAVED_FILTERS_PROP, arr.toString())
    }

    private fun refreshSavedFilterModel(selectName: String?) {
        val selected = selectName ?: (savedFilterCombo.selectedItem as? SavedFilter)?.name
        val sorted = savedFilters.sortedWith(
            compareByDescending<SavedFilter> { it.favorite }.thenBy { it.name.lowercase() },
        )
        savedFilterModel.removeAllElements()
        sorted.forEach { savedFilterModel.addElement(it) }
        if (!selected.isNullOrBlank()) {
            for (i in 0 until savedFilterModel.size) {
                val item = savedFilterModel.getElementAt(i)
                if (item.name == selected) {
                    savedFilterCombo.selectedIndex = i
                    break
                }
            }
        }
    }

    private fun registerQualityOfLifeShortcuts() {
        val root = this
        root.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            "jira.backToSearch",
        )
        root.actionMap.put(
            "jira.backToSearch",
            object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    showBrowseMode()
                }
            },
        )
    }

    override fun removeNotify() {
        super.removeNotify()
        notificationAutoRefreshTimer?.stop()
        notificationAutoRefreshTimer = null
    }

    private fun createIssueListCellRenderer(unreadPredicate: (JiraIssueListItem) -> Boolean): DefaultListCellRenderer =
        object : DefaultListCellRenderer() {
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
                        val unread = unreadPredicate(value)
                        val weight = if (unread) "700" else "600"
                        c.text =
                            "<html><body style='margin:0'><div style='color:$fg;font-weight:$weight;'>$keyEsc</div>" +
                                "<div style='color:$muted;font-size:90%;margin-top:2px;'>$sumEsc · $stEsc</div></body></html>"
                        c.toolTipText = "${value.key} · ${value.status}"
                    }
                    else -> {
                        c.text = value?.toString() ?: ""
                    }
                }
                return c
            }
        }

    private fun quickScopeComboRenderer(): DefaultListCellRenderer =
        object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                c.text = (value as? QuickScope)?.label ?: ""
                return c
            }
        }

    private fun quickSortComboRenderer(): DefaultListCellRenderer =
        object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                c.text = (value as? QuickSort)?.label ?: ""
                return c
            }
        }

    private fun notificationFeedComboRenderer(): DefaultListCellRenderer =
        object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                c.text = (value as? NotificationFeed)?.label ?: ""
                return c
            }
        }

    private fun notificationIntervalComboRenderer(): DefaultListCellRenderer =
        object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                c.text = (value as? NotificationIntervalOption)?.label ?: ""
                return c
            }
        }

    private fun applyQuickSearchFromUi() {
        val scope = quickScopeCombo.selectedItem as? QuickScope ?: return
        val sort = quickSortCombo.selectedItem as? QuickSort ?: return
        jqlField.text = "${scope.jqlPredicate} ${sort.orderClause}"
        loadIssueList()
    }

    private fun openNotificationSelectedIssue() {
        val item = notificationIssueList.selectedValue ?: return
        issueKeyField.text = item.key
        loadIssue()
    }

    private fun unreadBaselineMs(): Long {
        val lastRead = properties.getValue(NOTIFICATION_LAST_READ_MS_PROP)?.toLongOrNull() ?: 0L
        if (lastRead > 0L) return lastRead
        return System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
    }

    private fun isNotificationUnread(item: JiraIssueListItem): Boolean {
        val t = item.updatedEpochMillis
        if (t <= 0L) return false
        return t > unreadBaselineMs()
    }

    private fun countNotificationUnreadInModel(): Int {
        val base = unreadBaselineMs()
        var n = 0
        for (i in 0 until notificationIssueListModel.size()) {
            val t = notificationIssueListModel.getElementAt(i).updatedEpochMillis
            if (t > base) n++
        }
        return n
    }

    private fun updateNotificationsTabTitle() {
        if (!::leftTabbedPane.isInitialized || leftTabbedPane.tabCount < 2) return
        val n = countNotificationUnreadInModel()
        leftTabbedPane.setTitleAt(1, if (n > 0) "Notifications · $n" else "Notifications")
    }

    private fun markNotificationsRead() {
        properties.setValue(NOTIFICATION_LAST_READ_MS_PROP, System.currentTimeMillis().toString())
        notificationIssueList.repaint()
        updateNotificationsTabTitle()
    }

    private fun loadNotificationIntervalFromProperties(): NotificationIntervalOption {
        val mins = properties.getValue(NOTIFICATION_INTERVAL_MIN_PROP)?.toIntOrNull() ?: 0
        return NotificationIntervalOption.entries.firstOrNull { it.minutes == mins }
            ?: NotificationIntervalOption.MANUAL
    }

    private fun saveNotificationIntervalFromProperties() {
        val opt = notificationIntervalCombo.selectedItem as? NotificationIntervalOption ?: return
        properties.setValue(NOTIFICATION_INTERVAL_MIN_PROP, opt.minutes.toString())
    }

    private fun restartNotificationAutoRefreshTimer() {
        notificationAutoRefreshTimer?.stop()
        notificationAutoRefreshTimer = null
        if (!::notificationIntervalCombo.isInitialized) return
        val opt = notificationIntervalCombo.selectedItem as? NotificationIntervalOption ?: return
        if (opt.minutes <= 0) return
        notificationAutoRefreshTimer = Timer(opt.minutes * 60_000) {
            if (!isDisplayable || project.isDisposed || !isJiraConfigured()) return@Timer
            refreshNotificationFeed(silent = true)
        }.also {
            it.isRepeats = true
            it.start()
        }
    }

    private fun notificationSinceCheckJql(): String {
        val raw = properties.getValue(NOTIFICATION_ANCHOR_MS_PROP)?.toLongOrNull()
        val anchorMs = raw ?: (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))
        val z = Instant.ofEpochMilli(anchorMs).atZone(ZoneId.systemDefault())
        val formatted = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").format(z)
        return """updated >= "$formatted" ORDER BY updated DESC"""
    }

    private fun jqlForNotificationFeed(feed: NotificationFeed): String = when (feed) {
        NotificationFeed.ASSIGNED -> "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC"
        NotificationFeed.MENTIONED -> "mentionee = currentUser() ORDER BY updated DESC"
        NotificationFeed.SINCE_LAST_CHECK -> notificationSinceCheckJql()
    }

    private fun refreshNotificationFeed(silent: Boolean = false) {
        if (!isJiraConfigured()) {
            notificationStatusLabel.text = "Configure Jira Companion in Settings first."
            return
        }
        val feed = notificationFeedCombo.selectedItem as? NotificationFeed ?: return
        beginNetwork()
        if (!silent) {
            notificationStatusLabel.text = "Loading…"
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val jql = jqlForNotificationFeed(feed)
            val searchResult = JiraIssueService.getInstance().searchIssues(jql, maxResults = NOTIFICATIONS_PAGE_SIZE)
            SwingUtilities.invokeLater {
                endNetwork()
                if (project.isDisposed) return@invokeLater
                handleNotificationSearchResult(searchResult, feed)
            }
        }
    }

    private fun handleNotificationSearchResult(result: JiraSearchListResult, feed: NotificationFeed) {
        when (result) {
            is JiraSearchListResult.Ok -> {
                notificationIssueListModel.clear()
                result.issues.forEach { notificationIssueListModel.addElement(it) }
                if (feed == NotificationFeed.SINCE_LAST_CHECK) {
                    properties.setValue(NOTIFICATION_ANCHOR_MS_PROP, System.currentTimeMillis().toString())
                }
                notificationStatusLabel.text =
                    if (result.issues.isEmpty()) "No issues match this feed right now."
                    else "${result.issues.size} issue(s)"
                updateNotificationsTabTitle()
            }
            is JiraSearchListResult.ConfigError -> {
                notificationStatusLabel.text = result.message
            }
            is JiraSearchListResult.HttpError -> {
                val detail = if (feed == NotificationFeed.MENTIONED && result.code == 400) {
                    " Mention JQL (mentionee) may be unsupported on this site."
                } else {
                    ""
                }
                notificationStatusLabel.text = "HTTP ${result.code}: ${result.message}$detail"
            }
            is JiraSearchListResult.NetworkError -> {
                notificationStatusLabel.text = result.message
            }
        }
    }

    private enum class QuickScope(val label: String, val jqlPredicate: String) {
        ALL("All I can browse", "issuekey is not EMPTY"),
        ASSIGNED("Assigned to me", "assignee = currentUser()"),
        OPEN_NOT_DONE("Open (not done)", "statusCategory != Done"),
    }

    private enum class QuickSort(val label: String, val orderClause: String) {
        UPDATED_DESC("Recently updated", "ORDER BY updated DESC"),
        CREATED_DESC("Newest created", "ORDER BY created DESC"),
        PRIORITY_DESC("Priority", "ORDER BY priority DESC, updated DESC"),
    }

    private enum class NotificationFeed(val label: String) {
        ASSIGNED("Assigned to me"),
        MENTIONED("Mentioned me"),
        SINCE_LAST_CHECK("Updated since last check"),
    }

    private enum class NotificationIntervalOption(val label: String, val minutes: Int) {
        MANUAL("Manual only", 0),
        MIN1("Every 1 minute", 1),
        MIN5("Every 5 minutes", 5),
        MIN15("Every 15 minutes", 15),
        MIN30("Every 30 minutes", 30),
    }

    companion object {
        private data class SavedFilter(
            val name: String,
            var jql: String,
            var favorite: Boolean = false,
        )

        private const val LAST_ISSUE_KEY_PROP = "jiraplugin.lastIssueKey"
        private const val SEARCH_JQL_PROP = "jiraplugin.searchJql"
        private const val SAVED_FILTERS_PROP = "jiraplugin.savedFilters"
        private const val RECENT_JQL_PROP = "jiraplugin.recentJql"
        private const val RECENT_JQL_LIMIT = 12
        private const val SEARCH_PAGE_SIZE = 50
        private const val JQL_SECTION_COLLAPSED_PROP = "jiraplugin.jqlSectionCollapsed"
        private const val COMMENT_COLLAPSE_CHARS = 420
        private const val COMMENT_COLLAPSE_LINES = 10
        private const val COMMENT_PREVIEW_CHARS = 220
        /** Matches every issue the user can browse (not limited to recently updated). */
        private const val DEFAULT_JQL = "issuekey is not EMPTY ORDER BY updated DESC"
        private const val LEGACY_DEFAULT_JQL = "updated >= -365d ORDER BY updated DESC"
        private const val BROWSE_SPLIT_PROPORTION = 0.28f
        private const val NOTIFICATION_LAST_READ_MS_PROP = "jiraplugin.notificationsLastReadMs"
        private const val NOTIFICATION_INTERVAL_MIN_PROP = "jiraplugin.notificationsIntervalMin"
        private const val NOTIFICATION_ANCHOR_MS_PROP = "jiraplugin.notificationsSinceCheckAnchorMs"
        private const val NOTIFICATIONS_PAGE_SIZE = 50
    }
}

/**
 * Flow layout that reports wrapped preferred size so controls reflow in narrow tool windows.
 */
private class WrapFlowLayout(align: Int, hgap: Int, vgap: Int) : FlowLayout(align, hgap, vgap) {
    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension = layoutSize(target, preferred = false)

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val targetWidth = resolveAvailableWidth(target)
            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
            val maxWidth = targetWidth - horizontalInsetsAndGap
            var dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth > 0 && rowWidth + hgap + d.width > maxWidth) {
                    dim.width = maxOf(dim.width, rowWidth)
                    dim.height += rowHeight + vgap
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            dim.width = maxOf(dim.width, rowWidth)
            dim.height += rowHeight
            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }

    private fun resolveAvailableWidth(target: Container): Int {
        if (target.width > 0) return target.width
        var parent = target.parent
        while (parent != null) {
            if (parent.width > 0) return parent.width
            parent = parent.parent
        }
        return Int.MAX_VALUE
    }
}
