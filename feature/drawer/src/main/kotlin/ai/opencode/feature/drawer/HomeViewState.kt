package ai.opencode.feature.drawer

import ai.opencode.core.store.StoreOf
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.feature.drawer.list.DrawerGroupEditor
import ai.opencode.feature.drawer.list.DrawerSelection
import ai.opencode.feature.drawer.motion.DrawerKeyboardHold
import ai.opencode.feature.drawer.motion.DrawerKeyboardMonitor
import ai.opencode.feature.drawer.motion.DrawerPanRegions
import ai.opencode.feature.drawer.motion.DrawerSearchState
import ai.opencode.feature.drawer.motion.DrawerShortcutMotion
import ai.opencode.feature.drawer.motion.DrawerTimeReveal
import ai.opencode.feature.drawer.style.DrawerHeaderScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Home's view-local state, as one plain holder that the drawer panel (`HomeDrawer`) and the session
 * container read and drive. Durable state stays in [HomeFeature.State]. Create it with
 * [rememberHomeViewState]; [HomeView] fills in [keyboardHold] and [shortcutMotion] (they need a
 * composition) before anything reads them.
 */
@Stable
class HomeViewState internal constructor(
    val store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    initiallyOpenDrawer: Boolean,
) {
    /**
     * The phone's drawer is open, or opening. The container animates the change (Reduce Motion
     * lands at once).
     */
    var isSessionDrawerPresented: Boolean by mutableStateOf(initiallyOpenDrawer)

    /** The session last chosen in the drawer, until it's the one shown. */
    var chosenSessionID: String? by mutableStateOf(null)

    /** The window is wide enough for the sidebar (`SidebarMetrics.minimumWindowWidth`). */
    var usesSidebar: Boolean by mutableStateOf(false)
        internal set

    /** The app is in the foreground. */
    var isSceneActive: Boolean by mutableStateOf(true)
        internal set

    /** The header shows the search field in place of the title. */
    val search = DrawerSearchState()

    /** The rows chosen by holding one. */
    val selection = DrawerSelection()

    /** The local-group dialog the drawer is asking about. */
    val groupEditor = DrawerGroupEditor()

    /** The drawer shows its Archived page over the list. */
    var showsArchived: Boolean by mutableStateOf(false)

    /** How far the drawer's list has scrolled beneath its title. */
    val headerScroll = DrawerHeaderScrollState()

    /** The rows' slide uncovering their times. */
    val timeReveal = DrawerTimeReveal()

    /** Whether a keyboard is on screen or held. */
    val keyboardMonitor = DrawerKeyboardMonitor()

    /** The touch regions the drawer's pan tests (the list registers itself with `drawerPanList`). */
    val panRegions = DrawerPanRegions()

    /** Counts the times leaving Catch Up hands TalkBack back to its row. */
    var catchUpFocusRequests: Int by mutableIntStateOf(0)

    /** The drawer's keyboard hold. */
    var keyboardHold: DrawerKeyboardHold? = null
        internal set

    /** Catch Up's and What’s New's shortcut rows. */
    var shortcutMotion: DrawerShortcutMotion? = null
        internal set

    /**
     * Cancels a drawer row's pin drag; the drawer panel sets it while it owns a `DrawerPinDrag`.
     */
    var cancelPinDrag: () -> Unit = {}

    private val state: HomeFeature.State get() = store.state

    /** The session on screen. */
    val selectedSessionID: String? get() = state.sessionDetail?.session?.id

    /**
     * The row the drawer highlights. Catch Up shows its cards in the session's place, but the
     * drawer behind it still belongs to the session it was opened from.
     */
    fun drawerSelectedSessionID(state: HomeFeature.State = this.state): String? =
        state.catchUp?.returnSessionID ?: state.sessionDetail?.session?.id

    fun closeSessionDrawer() {
        cancelPinDrag()
        isSessionDrawerPresented = false
    }

    /** Settings pushes over the open drawer, which waits beneath it. */
    fun openSettings() {
        store.send(HomeFeature.Action.SettingsTapped)
    }

    /** Manage Servers… in the drawer's title menu: Settings arrives showing the server page. */
    fun manageServers() {
        store.send(HomeFeature.Action.ManageServersTapped)
    }

    /**
     * The drawer animates search itself (`DrawerSearchPill`). Cancel and Escape clear the search
     * and bring the title back.
     */
    fun endSearch() {
        keyboardHold?.setDrawerSearching(false)
        if (!search.isSearching && !search.isFocused && state.search.isEmpty()) return
        if (state.search.isNotEmpty()) store.send(HomeFeature.Action.SearchChanged(""))
        if (search.isSearching) search.isSearching = false
    }

    /** A drawer row was tapped. */
    fun selectSession(sessionID: String) {
        // Another session opens with the keyboard down from its first frame.
        if (sessionID != selectedSessionID) keyboardHold?.cardWillChange()
        // An uncached session keeps the old one's card until it loads; the drawer closes onto the
        // one chosen, in the same update.
        chosenSessionID = sessionID
        if (selectedSessionID != sessionID) store.send(HomeFeature.Action.SessionTapped(sessionID))
        endSearch()
        closeSessionDrawer()
    }

    /** New Session in the drawer. */
    fun startNewSessionFromDrawer() {
        keyboardHold?.cardWillChange()
        closeSessionDrawer()
        store.send(HomeFeature.Action.NewSessionTapped)
    }

    /** A group header's + in the drawer. */
    fun startNewSessionInGroup(groupID: String) {
        keyboardHold?.cardWillChange()
        closeSessionDrawer()
        store.send(HomeFeature.Action.NewSessionInGroupTapped(groupID))
    }

    /** The drawer's Catch Up row. */
    fun openCatchUp() {
        keyboardHold?.cardWillChange()
        closeSessionDrawer()
        store.send(HomeFeature.Action.catchUpTapped)
    }

    /** The drawer's What’s New row. */
    fun openWhatsNew() {
        keyboardHold?.cardWillChange()
        store.send(HomeFeature.Action.WhatsNew(WhatsNewFeature.Action.ButtonTapped))
    }

    /** The screen a screen recording names while nothing covers Home. */
    fun screenName(state: HomeFeature.State = this.state): String {
        if (state.catchUp != null) return "Catch Up"
        if (isSessionDrawerPresented || state.isDrawerOpen) return "Sessions"
        val top = state.topDetail ?: return "Sessions"
        if (top.browser.pageID != null) return "In-App Browser"
        if (state.subagentStack.isNotEmpty()) return "Session · subagent"
        return if (top.session == null) "New Session" else "Session"
    }
}

/**
 * A [HomeViewState] for [store], kept across recompositions.
 *
 * A configuration change (light/dark, rotation) recreates the activity and this view with it,
 * while Home's state lives on in the retained store: a drawer that last arrived open
 * ([HomeFeature.State.isDrawerOpen]) opens again, at rest, rather than snapping shut.
 */
@Composable
fun rememberHomeViewState(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    initiallyOpenDrawer: Boolean = false,
): HomeViewState = remember(store) { HomeViewState(store, initiallyOpenDrawer || store.state.isDrawerOpen) }
