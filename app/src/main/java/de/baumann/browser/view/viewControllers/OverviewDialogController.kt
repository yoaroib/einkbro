package de.baumann.browser.view.viewControllers

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.View.*
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import de.baumann.browser.Ninja.R
import de.baumann.browser.Ninja.databinding.DialogMenuContextListBinding
import de.baumann.browser.Ninja.databinding.DialogMenuOverviewBinding
import de.baumann.browser.Ninja.databinding.DialogOveriewBinding
import de.baumann.browser.database.*
import de.baumann.browser.preference.ConfigManager
import de.baumann.browser.unit.BrowserUnit
import de.baumann.browser.unit.HelperUnit
import de.baumann.browser.unit.ViewUnit
import de.baumann.browser.view.NinjaToast
import de.baumann.browser.view.adapter.RecordAdapter
import de.baumann.browser.view.dialog.DialogManager
import de.baumann.browser.view.dialog.TextInputDialog
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject


class OverviewDialogController(
        private val context: Context,
        private val binding: DialogOveriewBinding,
        private val recordDb: RecordDb,
        private val gotoUrlAction: (String) -> Unit,
        private val addTabAction: (String, String, Boolean) -> Unit,
        private val onHistoryChanged: () -> Unit,
        private val splitScreenAction: (String) -> Unit,
) : KoinComponent {
    private val config: ConfigManager by inject()
    private val dialogManager: DialogManager = DialogManager(context as Activity)

    private val recyclerView = binding.homeList2

    private var overViewTab: OverviewTab = OverviewTab.TabPreview

    private val lifecycleScope = (context as LifecycleOwner).lifecycleScope

    private val narrowLayoutManager = LinearLayoutManager(context).apply {
        reverseLayout = true
    }

    private val wideLayoutManager = GridLayoutManager(context, 2).apply {
        reverseLayout = true
    }

    init {
        initViews()
    }

    fun addTabPreview(view: View, index: Int) {
        binding.tabContainer.addView(
                view,
                index,
                LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
        )
    }

    fun removeTabView(view: View) {
        binding.tabContainer.removeView(view)
    }

    fun isVisible() = binding.root.visibility == VISIBLE
    fun show() {
        updateLayout()
        binding.root.visibility = VISIBLE
        openHomePage()
    }

    private fun updateLayout() {
        if (config.isToolbarOnTop) {
            binding.homeButtons.moveToTop()
            binding.overviewPreview.moveToBelowButtons()
            binding.homeList2.moveToBelowButtons()
        } else {
            binding.homeButtons.moveToBottom()
            binding.overviewPreview.moveToAboveButtons()
            binding.homeList2.moveToAboveButtons()
        }
        narrowLayoutManager.reverseLayout = !config.isToolbarOnTop
        wideLayoutManager.reverseLayout = !config.isToolbarOnTop
    }

    private fun View.moveToTop() {
        val constraintSet = ConstraintSet().apply {
            clone(binding.root)
            clear(id, ConstraintSet.BOTTOM)
            connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        }
        constraintSet.applyTo(binding.root)
    }

    private fun View.moveToBelowButtons() {
        val constraintSet = ConstraintSet().apply {
            clone(binding.root)
            clear(id, ConstraintSet.BOTTOM)
            connect(id, ConstraintSet.TOP, binding.homeButtons.id, ConstraintSet.BOTTOM)
        }
        constraintSet.applyTo(binding.root)
    }

    private fun View.moveToAboveButtons() {
        val constraintSet = ConstraintSet().apply {
            clone(binding.root)
            clear(id, ConstraintSet.TOP)
            connect(id, ConstraintSet.BOTTOM, binding.homeButtons.id, ConstraintSet.TOP)
        }
        constraintSet.applyTo(binding.root)
    }

    private fun View.moveToBottom() {
        val constraintSet = ConstraintSet().apply {
            clone(binding.root)
            clear(id, ConstraintSet.TOP)
            connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
        constraintSet.applyTo(binding.root)
    }

    private fun initViews() {

        binding.root.setOnClickListener { hide() }

        // allow scrolling in listView without closing the bottomSheetDialog
        recyclerView.layoutManager = narrowLayoutManager

        binding.openMenu.setOnClickListener { openSubMenu() }
        binding.openTabButton.setOnClickListener { openHomePage() }
        binding.openHistoryButton.setOnClickListener { openHistoryPage() }

        binding.buttonCloseOverview.setOnClickListener { hide() }
        showCurrentTabInOverview()

    }

    private fun openSubMenu() {
        val dialogView = DialogMenuOverviewBinding.inflate(LayoutInflater.from(context))
        val dialog = dialogManager.showOptionDialog(dialogView.root)
        with(dialogView) {
            tvDelete.setOnClickListener { dialog.dismissWithAction { deleteAllItems() } }
        }
    }

    private suspend fun getFolderName(): String {
        return TextInputDialog(
                context,
                context.getString(R.string.folder_name),
                context.getString(R.string.folder_name_description),
                ""
        ).show() ?: "New Folder"
    }

    private fun showCurrentTabInOverview() {
        when (config.overviewTab) {
            OverviewTab.TabPreview -> openHomePage()
            OverviewTab.History -> openHistoryPage()
        }
    }

    fun hide() {
        binding.root.visibility = GONE
    }

    private var adapter: RecordAdapter? = null
    fun openHistoryPage(amount: Int = 0) {
        updateLayout()
        binding.root.visibility = VISIBLE

        binding.overviewPreview.visibility = View.INVISIBLE
        recyclerView.visibility = VISIBLE
        toggleOverviewFocus(binding.openHistoryView)

        overViewTab = OverviewTab.History

        lifecycleScope.launch {
            val list = recordDb.listEntries(false, amount)
            adapter = RecordAdapter(
                    list.toMutableList(),
                    { position ->
                        val record = list[position]
                        gotoUrlAction(record.url)
                        if (record.type == RecordType.Bookmark) {
                            config.addRecentBookmark(Bookmark(record.title?:"no title", record.url))
                        }
                        hide()
                    },
                    { position ->
                        showHistoryContextMenu(
                                list[position].title ?: "",
                                list[position].url,
                                position
                        )
                    }
            )
            recyclerView.adapter = adapter
        }
    }

    private fun shouldShowWideList(): Boolean =
        ViewUnit.isLandscape(context) || ViewUnit.isTablet(context)

    private fun openHomePage() {
        updateLayout()
        binding.overviewPreview.visibility = VISIBLE
        recyclerView.visibility = GONE
        recyclerView.layoutManager = if (shouldShowWideList()) wideLayoutManager else narrowLayoutManager
        toggleOverviewFocus(binding.openTabView)
        overViewTab = OverviewTab.TabPreview
    }

    private fun toggleOverviewFocus(view: View) {
        when(view) {
            binding.openTabView -> {
                binding.openTabLayout.visibility = VISIBLE
                binding.openTabView.visibility = VISIBLE

                binding.openHistoryLayout.visibility = VISIBLE
                binding.newWindow.visibility = VISIBLE
                binding.tabPlusIncognito.visibility = VISIBLE
                binding.tabPlusBottom.visibility = VISIBLE
            }
            binding.openHistoryView -> {
                binding.openHistoryLayout.visibility = VISIBLE
                binding.openHistoryView.visibility = VISIBLE
                binding.newWindow.visibility = GONE
                binding.tabPlusIncognito.visibility = GONE
                binding.tabPlusBottom.visibility = GONE
            }
        }
        binding.openTabView.visibility = if (binding.openTabView == view) VISIBLE else View.INVISIBLE
        binding.openHistoryView.visibility = if (binding.openHistoryView == view) VISIBLE else View.INVISIBLE
    }


    private fun deleteAllItems() {
        dialogManager.showOkCancelDialog(
                messageResId = R.string.hint_database,
                okAction = {
                    when (overViewTab) {
                        OverviewTab.History -> {
                            BrowserUnit.clearHistory(context)
                            (recyclerView.adapter as RecordAdapter).clear()
                            hide()
                            onHistoryChanged()
                        }
                        else -> { }
                    }
                }
        )
    }

    private fun showHistoryContextMenu(
            title: String,
            url: String,
            location: Int
    ) {
        val dialogView = DialogMenuContextListBinding.inflate(LayoutInflater.from(context))
        val dialog = dialogManager.showOptionDialog(dialogView.root)

        dialogView.menuContextListEdit.visibility = GONE
        dialogView.menuContextListFav.setOnClickListener {
            dialog.dismissWithAction { config.favoriteUrl = url }
        }
        dialogView.menuContextLinkSc.setOnClickListener {
            dialog.dismissWithAction { HelperUnit.createShortcut(context, title, url, null) }
        }
        dialogView.menuContextListNewTab.setOnClickListener {
            dialog.dismissWithAction {
                addTabAction(context.getString(R.string.app_name), url, false)
                NinjaToast.show(context, context.getString(R.string.toast_new_tab_successful))

            }
        }
        dialogView.menuContextListSplitScreen.setOnClickListener {
            dialog.dismissWithAction { splitScreenAction(url) }
            hide()
        }
        dialogView.menuContextListNewTabOpen.setOnClickListener {
            dialog.dismissWithAction {
                addTabAction(context.getString(R.string.app_name), url, true)
                hide()
            }
        }
        dialogView.menuContextListDelete.setOnClickListener {
            dialog.dismissWithAction { deleteHistory(location) }
        }
    }

    private fun deleteHistory(location: Int) {
        val record = adapter?.getItemAt(location) ?: return
        RecordDb(context).apply {
            open(true)
            deleteHistoryItem(record)
            close()
        }
        adapter?.removeAt(location)
        onHistoryChanged()
    }
}

private fun Dialog.dismissWithAction(action: () -> Unit) {
    dismiss()
    action()
}

enum class OverviewTab {
    TabPreview, History
}