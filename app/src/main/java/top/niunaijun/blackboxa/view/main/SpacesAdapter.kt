package top.niunaijun.blackboxa.view.main

import android.graphics.Point
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import cbfg.rvadapter.RVAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.databinding.ItemNewSpaceBinding
import top.niunaijun.blackboxa.databinding.ItemSpaceCardBinding
import top.niunaijun.blackboxa.util.GmsPrompt
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.VpnPrompt
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.apps.AppsAdapter
import top.niunaijun.blackboxa.view.apps.AppsTouchCallBack
import top.niunaijun.blackboxa.view.apps.AppsViewModel
import java.util.Collections

/**
 * TwinSpace home: a vertical list of Space cards.
 *
 * Each card is one BlackBox virtual user ("space"). The card body hosts the
 * space's app grid through an [RVAdapter] bound with `.bind()`, running the existing
 * [AppsAdapter] item layout, fed by a per-space [AppsViewModel] — the same
 * repository / ViewModel data flow the old ViewPager pages (AppsFragment)
 * used, bound to a card instead of a full-screen page. The last item is the
 * "New Space" card (the old pager's extra empty page, as a card).
 */
class SpacesAdapter(
        private val activity: MainActivity,
        private val callbacks: Callbacks
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Callbacks {
        /** Open the add-app picker targeting [userId]. */
        fun onAddApp(userId: Int)

        /** The set of spaces may have changed (install / uninstall / delete). */
        fun onSpacesChanged()

        fun showLoading()

        fun hideLoading()
    }

    private class SpaceState(val userId: Int) {
        var viewModel: AppsViewModel? = null
        var gridAdapter: RVAdapter<AppInfo>? = null
        var observing = false
        var items: List<AppInfo> = emptyList()

        /**
         * TwinSpace, 2026-11-27: true once the app list has been loaded
         * at least once for this space. Before the first load the grid is
         * empty, but that does NOT mean the space has no apps — so the
         * "No apps in this space yet" text (and the loading spinner) are
         * only shown based on this flag, not on items.isEmpty() alone.
         * Otherwise every cold start flashes "No apps" for a second
         * before the real list arrives.
         */
        var loaded = false

        /**
         * Clone-time GMS prompt guard for the current picker session: reset
         * by [installIntoSpace], set when a clone-success event is handled,
         * so one clone operation can raise the prompt at most once. It is
         * deliberately NOT persisted — the next clone into a space that
         * still has no GMS may prompt again.
         */
        var gmsPromptHandled = false

        /**
         * Clone-time USA VPN prompt guard, mirroring [gmsPromptHandled]:
         * reset by [installIntoSpace], set when a clone-success event is
         * handled, so one clone operation can raise the prompt at most once.
         * Deliberately NOT persisted.
         */
        var vpnPromptHandled = false

        /** Long-press menu waiting for touch-up (upstream AppsFragment pattern). */
        var pendingMenu: PopupMenu? = null
    }

    /** Per-grid touch bookkeeping for the deferred long-press menu. */
    private class GridTouch(var state: SpaceState) {
        val point = Point()
        var downTime = 0L

        /** Drag + touch helpers attach once per RecyclerView, not per bind. */
        var helpersAttached = false
    }

    private val states = mutableMapOf<Int, SpaceState>()
    private var userIds: List<Int> = emptyList()

    companion object {
        private const val TAG = "SpacesAdapter"
        private const val TYPE_SPACE = 0
        private const val TYPE_NEW_SPACE = 1
    }

    fun submitSpaces(ids: List<Int>) {
        userIds = ids
        states.keys.retainAll(ids.toSet())
        ids.forEach { ensureState(it) }
        notifyDataSetChanged()
    }

    /** Load (or reload) the app list of one space. */
    fun loadSpace(userId: Int) {
        ensureState(userId).viewModel?.getInstalledApps(userId)
    }

    /** Install [source] into a space (used for the add-app picker result). */
    fun installIntoSpace(userId: Int, source: String) {
        callbacks.showLoading()
        val state = ensureState(userId)
        // A new picker session starts here: allow this clone to raise the
        // GMS prompt once (see SpaceState.gmsPromptHandled).
        state.gmsPromptHandled = false
        // And the USA VPN prompt once (see SpaceState.vpnPromptHandled).
        state.vpnPromptHandled = false
        state.viewModel?.install(source, userId)
    }

    fun nextNewUserId(): Int {
        var candidate = 0
        while (userIds.contains(candidate)) candidate++
        return candidate
    }

    private fun ensureState(userId: Int): SpaceState {
        val existing = states[userId]
        if (existing != null) return existing
        val state = SpaceState(userId)
        val viewModel =
                ViewModelProvider(activity, InjectionUtil.getAppsFactory())
                        .get("twinspace_apps_$userId", AppsViewModel::class.java)
        state.viewModel = viewModel
        states[userId] = state
        observeState(state)
        return state
    }

    private fun observeState(state: SpaceState) {
        if (state.observing) return
        state.observing = true
        val viewModel = state.viewModel ?: return
        val userId = state.userId
        viewModel.appsLiveData.observe(activity) { list ->
            state.loaded = true
            state.items = list ?: emptyList()
            notifySpaceChanged(userId)
        }
        viewModel.resultLiveData.observe(activity) { msg ->
            if (!msg.isNullOrEmpty()) {
                callbacks.hideLoading()
                toast(msg)
                viewModel.getInstalledApps(userId)
                // Install/uninstall can create or empty-delete a space.
                callbacks.onSpacesChanged()
            }
        }
        viewModel.cloneSuccessLiveData.observe(activity) { event ->
            if (event != null) {
                // Consume the event so a re-delivery (observer re-attach,
                // state recreation) cannot raise the prompt a second time.
                viewModel.cloneSuccessLiveData.value = null
                val (clonedUserId, clonedPackage) = event
                if (clonedUserId == userId && !state.gmsPromptHandled) {
                    state.gmsPromptHandled = true
                    GmsPrompt.maybePrompt(activity, clonedUserId, clonedPackage) {
                        // Refresh this Space's app list live after the GMS
                        // install, so the Google apps show immediately.
                        viewModel.getInstalledApps(clonedUserId)
                    }
                }
                if (clonedUserId == userId && !state.vpnPromptHandled) {
                    state.vpnPromptHandled = true
                    VpnPrompt.maybePrompt(activity, clonedUserId, clonedPackage)
                }
            }
        }
        viewModel.launchLiveData.observe(activity) { ok ->
            callbacks.hideLoading()
            if (ok == false) {
                toast(R.string.start_fail)
            }
        }
        viewModel.updateSortLiveData.observe(activity) { changed ->
            if (changed == true) {
                state.gridAdapter?.let { viewModel.updateApkOrder(userId, it.getItems()) }
            }
        }
    }

    private fun notifySpaceChanged(userId: Int) {
        val index = userIds.indexOf(userId)
        if (index >= 0) {
            notifyItemChanged(index)
        }
    }

    private fun displayName(userId: Int, position: Int): String {
        val remark = AppManager.mRemarkSharedPreferences.getString("Remark$userId", null)
        // Upstream's legacy default was "User <id>"; treat it as unset.
        return if (remark.isNullOrEmpty() || remark == "User $userId") {
            activity.getString(R.string.space_default_name, position + 1)
        } else {
            remark
        }
    }

    override fun getItemCount(): Int = userIds.size + 1

    override fun getItemViewType(position: Int): Int =
            if (position < userIds.size) TYPE_SPACE else TYPE_NEW_SPACE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SPACE) {
            SpaceVH(ItemSpaceCardBinding.inflate(inflater, parent, false))
        } else {
            NewSpaceVH(ItemNewSpaceBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is SpaceVH && position < userIds.size) {
            bindSpace(holder, userIds[position], position)
        } else if (holder is NewSpaceVH) {
            holder.binding.newSpaceCard.setOnClickListener {
                callbacks.onAddApp(nextNewUserId())
            }
        }
    }

    private fun bindSpace(holder: SpaceVH, userId: Int, position: Int) {
        val state = ensureState(userId)
        holder.binding.spaceBadge.text = (position + 1).toString()
        holder.binding.spaceName.text = displayName(userId, position)
        holder.binding.spaceMenu.setOnClickListener { view ->
            showSpaceMenu(view, userId, position)
        }

        val grid = holder.binding.spaceGrid
        val gridAdapter = state.gridAdapter ?: createGridAdapter(state).also {
            state.gridAdapter = it
        }
        val touch = (grid.tag as? GridTouch) ?: GridTouch(state).also { grid.tag = it }
        touch.state = state
        if (grid.adapter !== gridAdapter) {
            grid.layoutManager = GridLayoutManager(activity, 4)
            // TwinSpace: grid items are fixed-size app icons, so the
            // RecyclerView can skip re-measuring on every change, and a
            // small view cache (instead of the default) keeps fewer
            // off-screen holders — and their bitmaps — in memory.
            grid.setHasFixedSize(true)
            grid.setItemViewCacheSize(4)
            // A2: use the upstream-proven .bind(grid), as in AppsFragment,
            // FakeManagerActivity, GmsManagerActivity and ListActivity,
            // instead of assigning the adapter directly. bind() does the
            // adapter's internal RecyclerView wiring; a direct assignment
            // compiles but leaves that wiring unconfirmed.
            gridAdapter.bind(grid)
        }
        if (!touch.helpersAttached) {
            touch.helpersAttached = true
            attachDragHelper(grid)
            attachMenuTouch(grid)
        }
        gridAdapter.setItems(state.items)
        // TwinSpace, 2026-11-27: show the spinner while the first load is
        // still running, and the "No apps" text only once the load finished
        // AND the space is really empty. Before the first load items is
        // empty too, and showing "No apps" then is wrong (it flashes on
        // every cold start before the real list arrives).
        holder.binding.spaceLoading.visibility =
                if (!state.loaded) View.VISIBLE else View.GONE
        holder.binding.spaceEmpty.visibility =
                if (state.loaded && state.items.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (state.items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun createGridAdapter(state: SpaceState): RVAdapter<AppInfo> {
        val userId = state.userId
        val adapter = RVAdapter<AppInfo>(activity, AppsAdapter())
        adapter.setItemClickListener { _, data, _ ->
            callbacks.showLoading()
            state.viewModel?.launchApk(data.packageName, userId)
        }
        adapter.setItemLongClickListener { view, data, _ ->
            showAppMenu(view, state, data)
        }
        return adapter
    }

    private fun attachDragHelper(grid: RecyclerView) {
        // The callback resolves the owning space dynamically from the grid's
        // tag, because a recycled grid is rebound to different spaces.
        val callback =
                AppsTouchCallBack { from, to ->
                    val state = (grid.tag as? GridTouch)?.state
                            ?: return@AppsTouchCallBack
                    val adapter = state.gridAdapter ?: return@AppsTouchCallBack
                    val items = adapter.getItems()
                    if (from in items.indices && to in items.indices) {
                        if (from < to) {
                            for (i in from until to) Collections.swap(items, i, i + 1)
                        } else {
                            for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
                        }
                        adapter.notifyItemMoved(from, to)
                        state.viewModel?.updateSortLiveData?.postValue(true)
                    }
                }
        ItemTouchHelper(callback).attachToRecyclerView(grid)
    }

    /**
     * Mirrors upstream AppsFragment: the long-press only *prepares* the menu;
     * [attachMenuTouch] shows it on a clean touch-up, so a long-press drag
     * (reorder) does not pop the menu, and a long-press hold does.
     */
    private fun attachMenuTouch(grid: RecyclerView) {
        grid.setOnTouchListener { v, e ->
            val touch = v.tag as? GridTouch ?: return@setOnTouchListener false
            val state = touch.state
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    touch.point.set(0, 0)
                    touch.downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touch.point.x == 0 && touch.point.y == 0) {
                        touch.point.set(e.rawX.toInt(), e.rawY.toInt())
                    }
                    val moved =
                            kotlin.math.abs(touch.point.x - e.rawX) > 40 ||
                                    kotlin.math.abs(touch.point.y - e.rawY) > 40
                    if (moved) {
                        state.pendingMenu?.dismiss()
                        state.pendingMenu = null
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touch.downTime
                    val moved =
                            touch.point.x != 0 &&
                                    (kotlin.math.abs(touch.point.x - e.rawX) > 40 ||
                                            kotlin.math.abs(touch.point.y - e.rawY) > 40)
                    if (!moved && duration < 500) {
                        state.pendingMenu?.show()
                    }
                    state.pendingMenu = null
                    touch.point.set(0, 0)
                }
            }
            false
        }
    }

    private fun showAppMenu(anchor: View, state: SpaceState, data: AppInfo) {
        val userId = state.userId
        state.pendingMenu = PopupMenu(activity, anchor).also { menu ->
            menu.inflate(R.menu.app_menu)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.app_remove -> {
                        if (data.isXpModule) {
                            toast(R.string.uninstall_module_toast)
                        } else {
                            confirmDialog(
                                    R.string.uninstall_app,
                                    activity.getString(R.string.uninstall_app_hint, data.name)
                            ) {
                                callbacks.showLoading()
                                state.viewModel?.unInstall(data.packageName, userId)
                            }
                        }
                    }
                    R.id.app_clear -> {
                        confirmDialog(
                                R.string.app_clear,
                                activity.getString(R.string.app_clear_hint, data.name)
                        ) {
                            callbacks.showLoading()
                            state.viewModel?.clearApkData(data.packageName, userId)
                        }
                    }
                    R.id.app_stop -> {
                        confirmDialog(
                                R.string.app_stop,
                                activity.getString(R.string.app_stop_hint, data.name)
                        ) {
                            try {
                                BlackBoxCore.get().stopPackage(data.packageName, userId)
                                toast(activity.getString(R.string.is_stop, data.name))
                            } catch (e: Exception) {
                                Log.e(TAG, "Error stopping app: ${e.message}")
                            }
                        }
                    }
                    R.id.app_shortcut -> {
                        ShortcutUtil.createShortcut(activity, userId, data)
                    }
                }
                true
            }
            // Not shown here: attachMenuTouch shows it on a clean touch-up.
        }
    }

    private fun showSpaceMenu(anchor: View, userId: Int, position: Int) {
        PopupMenu(activity, anchor).also { menu ->
            menu.inflate(R.menu.space_menu)
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.space_add_app -> callbacks.onAddApp(userId)
                    R.id.space_rename -> showRenameDialog(userId, position)
                    R.id.space_delete -> {
                        confirmDialog(
                                R.string.delete_space,
                                activity.getString(
                                        R.string.delete_space_hint,
                                        displayName(userId, position)
                                )
                        ) {
                            try {
                                BlackBoxCore.get().deleteUser(userId)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error deleting space: ${e.message}")
                            }
                            AppManager.mRemarkSharedPreferences.edit {
                                remove("Remark$userId")
                                remove("AppList$userId")
                            }
                            states.remove(userId)
                            callbacks.onSpacesChanged()
                        }
                    }
                }
                true
            }
            menu.show()
        }
    }

    private fun showRenameDialog(userId: Int, position: Int) {
        MaterialDialog(activity).show {
            title(res = R.string.userRemark)
            input(
                    hintRes = R.string.userRemark,
                    prefill = displayName(userId, position)
            ) { _, input ->
                AppManager.mRemarkSharedPreferences.edit {
                    putString("Remark$userId", input.toString())
                }
                notifySpaceChanged(userId)
            }
            positiveButton(res = R.string.done)
            negativeButton(res = R.string.cancel)
        }
    }

    private fun confirmDialog(titleRes: Int, message: String, onDone: () -> Unit) {
        MaterialDialog(activity).show {
            title(res = titleRes)
            message(text = message)
            positiveButton(res = R.string.done) { onDone() }
            negativeButton(res = R.string.cancel)
        }
    }

    private class SpaceVH(val binding: ItemSpaceCardBinding) :
            RecyclerView.ViewHolder(binding.root)

    private class NewSpaceVH(val binding: ItemNewSpaceBinding) :
            RecyclerView.ViewHolder(binding.root)
}
