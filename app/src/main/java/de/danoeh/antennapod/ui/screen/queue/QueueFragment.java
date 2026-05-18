package de.danoeh.antennapod.ui.screen.queue;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.playback.SpeedChangedEvent;
import de.danoeh.antennapod.ui.screen.InboxFragment;
import de.danoeh.antennapod.ui.screen.SearchFragment;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import de.danoeh.antennapod.ui.view.FloatingSelectMenu;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import de.danoeh.antennapod.ui.common.ConfirmationDialog;
import de.danoeh.antennapod.ui.MenuItemUtils;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.screen.feed.ItemSortDialog;
import de.danoeh.antennapod.event.EpisodeDownloadEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.FeedUpdateRunningEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.event.QueueEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.ui.episodeslist.EpisodeMultiSelectActionHandler;
import de.danoeh.antennapod.ui.swipeactions.SwipeActions;
import de.danoeh.antennapod.ui.episodeslist.FeedItemMenuHandler;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.NamedQueue;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.view.EmptyViewHandler;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListRecyclerView;
import de.danoeh.antennapod.ui.view.LiftOnScrollListener;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemViewHolder;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Shows all items in the queue.
 */
public class QueueFragment extends Fragment implements MaterialToolbar.OnMenuItemClickListener,
        EpisodeItemListAdapter.OnSelectModeListener {
    public static final String TAG = "QueueFragment";
    private static final String KEY_UP_ARROW = "up_arrow";
    private static final String SCROLL_POSITION_KEY = "scroll_position";
    private static final String SCROLL_OFFSET_KEY = "scroll_offset";

    private TextView infoBar;
    private EpisodeItemListRecyclerView recyclerView;
    private QueueRecyclerAdapter recyclerAdapter;
    private EmptyViewHandler emptyView;
    private MaterialToolbar toolbar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private boolean displayUpArrow;

    private List<FeedItem> queue;

    private static final String PREFS = "QueueFragment";
    private static final String PREF_SHOW_LOCK_WARNING = "show_lock_warning";

    private Disposable disposable;
    private SwipeActions swipeActions;
    private SharedPreferences prefs;

    private FloatingSelectMenu floatingSelectMenu;
    private ProgressBar progressBar;
    private NamedQueue activeQueue;
    private List<NamedQueue> queues;
    private Map<Long, String> queueInfoLabels;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getActivity().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public void onStart() {
        super.onStart();
        loadItems();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        Pair<Integer, Integer> scrollPosition = recyclerView.getScrollPosition();
        prefs.edit().putInt(SCROLL_POSITION_KEY, scrollPosition.first)
                .putInt(SCROLL_OFFSET_KEY, scrollPosition.second).apply();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(QueueEvent event) {
        Log.d(TAG, "onEventMainThread() called with: " + "event = [" + event + "]");
        if (queue == null) {
            return;
        } else if (recyclerAdapter == null) {
            loadItems();
            return;
        }
        int position;
        switch (event.action) {
            case ADDED:
                queue.add(event.position, event.item);
                recyclerAdapter.notifyItemInserted(event.position);
                break;
            case SET_QUEUE:
            case SORTED: //Deliberate fall-through
                queue = event.items;
                recyclerAdapter.updateItems(event.items);
                break;
            case REMOVED:
            case IRREVERSIBLE_REMOVED:
                position = FeedItemEvent.indexOfItemWithId(queue, event.item.getId());
                queue.remove(position);
                recyclerAdapter.notifyItemRemoved(position);
                break;
            case CLEARED:
                queue.clear();
                recyclerAdapter.updateItems(queue);
                break;
            case MOVED:
                position = FeedItemEvent.indexOfItemWithId(queue, event.item.getId());
                queue.add(event.position, queue.remove(position));
                recyclerAdapter.notifyItemMoved(position, event.position);
                break;
            default:
                return;
        }
        recyclerAdapter.updateDragDropEnabled();
        refreshToolbarState();
        refreshInfoBar();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(FeedItemEvent event) {
        Log.d(TAG, "onEventMainThread() called with: " + "event = [" + event + "]");
        if (event.unreadStatusChanged && event.items.isEmpty()) {
            loadItems();
            refreshToolbarState();
            return;
        }
        if (queue == null) {
            return;
        } else if (recyclerAdapter == null) {
            loadItems();
            return;
        }
        for (int i = 0, size = event.items.size(); i < size; i++) {
            FeedItem item = event.items.get(i);
            int pos = FeedItemEvent.indexOfItemWithId(queue, item.getId());
            if (pos >= 0) {
                queue.remove(pos);
                queue.add(pos, item);
                recyclerAdapter.notifyItemChangedCompat(pos);
                refreshInfoBar();
            }
        }
        if (event.unreadStatusChanged) {
            refreshToolbarState();
        }
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEventMainThread(EpisodeDownloadEvent event) {
        if (queue == null) {
            return;
        }
        for (String downloadUrl : event.getUrls()) {
            int pos = EpisodeDownloadEvent.indexOfItemWithDownloadUrl(queue, downloadUrl);
            if (pos >= 0) {
                recyclerAdapter.notifyItemChangedCompat(pos);
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        if (recyclerAdapter != null) {
            for (int i = 0; i < recyclerAdapter.getItemCount(); i++) {
                EpisodeItemViewHolder holder = (EpisodeItemViewHolder)
                        recyclerView.findViewHolderForAdapterPosition(i);
                if (holder != null && holder.isPlayingItem()) {
                    holder.notifyPlaybackPositionUpdated(event);
                    break;
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        loadItems();
        refreshToolbarState();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onKeyUp(KeyEvent event) {
        if (!isAdded() || !isVisible() || !isMenuVisible()) {
            return;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_T:
                recyclerView.smoothScrollToPosition(0);
                break;
            case KeyEvent.KEYCODE_B:
                recyclerView.smoothScrollToPosition(recyclerAdapter.getItemCount() - 1);
                break;
            default:
                break;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (recyclerAdapter != null) {
            recyclerAdapter.endSelectMode();
        }
        recyclerAdapter = null;
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(null);
            toolbar.setOnLongClickListener(null);
        }
    }

    private void refreshToolbarState() {
        boolean keepSorted = UserPreferences.isQueueKeepSorted();
        toolbar.getMenu().findItem(R.id.queue_lock).setChecked(UserPreferences.isQueueLocked());
        toolbar.getMenu().findItem(R.id.queue_lock).setVisible(!keepSorted);
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEventMainThread(FeedUpdateRunningEvent event) {
        swipeRefreshLayout.setRefreshing(event.isFeedUpdateRunning);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void updateSpeed(SpeedChangedEvent event) {
        refreshInfoBar();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.queue_lock) {
            toggleQueueLock();
            return true;
        } else if (itemId == R.id.queue_sort) {
            new QueueSortDialog().show(getChildFragmentManager().beginTransaction(), "SortDialog");
            return true;
        } else if (itemId == R.id.manage_queues) {
            showQueueManagementDialog();
            return true;
        } else if (itemId == R.id.refresh_item) {
            FeedUpdateManager.getInstance().runOnceOrAsk(requireContext());
            return true;
        } else if (itemId == R.id.clear_queue) {
            // make sure the user really wants to clear the queue
            ConfirmationDialog conDialog = new ConfirmationDialog(getActivity(),
                    R.string.clear_queue_label,
                    R.string.clear_queue_confirmation_msg) {

                @Override
                public void onConfirmButtonPressed(
                        DialogInterface dialog) {
                    dialog.dismiss();
                    DBWriter.clearQueue();
                }
            };
            conDialog.createNewDialog().show();
            return true;
        } else if (itemId == R.id.action_search) {
            ((MainActivity) getActivity()).loadChildFragment(
                    SearchFragment.newInstance(new FeedItemFilter(FeedItemFilter.QUEUED,
                            FeedItemFilter.INCLUDE_ALL_FEED_STATES)));
            return true;
        }
        return false;
    }

    private void toggleQueueLock() {
        boolean isLocked = UserPreferences.isQueueLocked();
        if (isLocked) {
            setQueueLocked(false);
        } else {
            boolean shouldShowLockWarning = prefs.getBoolean(PREF_SHOW_LOCK_WARNING, true);
            if (!shouldShowLockWarning) {
                setQueueLocked(true);
            } else {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext());
                builder.setTitle(R.string.lock_queue);
                builder.setMessage(R.string.queue_lock_warning);

                View view = View.inflate(getContext(), R.layout.checkbox_do_not_show_again, null);
                CheckBox checkDoNotShowAgain = view.findViewById(R.id.checkbox_do_not_show_again);
                builder.setView(view);

                builder.setPositiveButton(R.string.lock_queue, (dialog, which) -> {
                    prefs.edit().putBoolean(PREF_SHOW_LOCK_WARNING, !checkDoNotShowAgain.isChecked()).apply();
                    setQueueLocked(true);
                });
                builder.setNegativeButton(R.string.cancel_label, null);
                builder.show();
            }
        }
    }

    private void setQueueLocked(boolean locked) {
        UserPreferences.setQueueLocked(locked);
        refreshToolbarState();
        if (recyclerAdapter != null) {
            recyclerAdapter.updateDragDropEnabled();
        }
        if (queue.isEmpty()) {
            if (locked) {
                EventBus.getDefault().post(new MessageEvent(getString(R.string.queue_locked)));
            } else {
                EventBus.getDefault().post(new MessageEvent(getString(R.string.queue_unlocked)));
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        Log.d(TAG, "onContextItemSelected() called with: " + "item = [" + item + "]");
        if (!isVisible() || recyclerAdapter == null) {
            return false;
        }
        FeedItem selectedItem = recyclerAdapter.getLongPressedItem();
        if (selectedItem == null) {
            Log.i(TAG, "Selected item was null, ignoring selection");
            return super.onContextItemSelected(item);
        }

        int position = FeedItemEvent.indexOfItemWithId(queue, selectedItem.getId());
        if (position < 0) {
            Log.i(TAG, "Selected item no longer exist, ignoring selection");
            return super.onContextItemSelected(item);
        }
        if (recyclerAdapter.onContextItemSelected(item)) {
            return true;
        }

        final int itemId = item.getItemId();
        if (!recyclerAdapter.inActionMode()) {
            if (itemId == R.id.move_to_top_item) {
                queue.add(0, queue.remove(position));
                recyclerAdapter.notifyItemMoved(position, 0);
                DBWriter.moveQueueItemsToTop(Collections.singletonList(selectedItem));
                return true;
            } else if (itemId == R.id.move_to_bottom_item) {
                queue.add(queue.remove(position));
                recyclerAdapter.notifyItemMoved(position, queue.size() - 1);
                DBWriter.moveQueueItemsToBottom(Collections.singletonList(selectedItem));
                return true;
            } else if (itemId == R.id.send_to_queue_item) {
                showSendToQueueDialog(Collections.singletonList(selectedItem), null);
                return true;
            } else if (itemId == R.id.set_default_queue_for_show_item) {
                toggleDefaultQueueForShow(selectedItem);
                return true;
            }
        }
        return FeedItemMenuHandler.onMenuItemClicked(this, item.getItemId(), selectedItem);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        View root = inflater.inflate(R.layout.queue_fragment, container, false);
        toolbar = root.findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this);
        toolbar.setOnLongClickListener(v -> {
            recyclerView.scrollToPosition(5);
            recyclerView.post(() -> recyclerView.smoothScrollToPosition(0));
            return false;
        });
        displayUpArrow = getParentFragmentManager().getBackStackEntryCount() != 0;
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW);
        }
        ((MainActivity) getActivity()).setupToolbarToggle(toolbar, displayUpArrow);
        toolbar.inflateMenu(R.menu.queue);
        refreshToolbarState();
        progressBar = root.findViewById(R.id.progressBar);
        progressBar.setVisibility(View.VISIBLE);

        infoBar = root.findViewById(R.id.info_bar);
        boolean largePadding = displayUpArrow || !UserPreferences.isBottomNavigationEnabled();
        int paddingHorizontal = (int) (getResources().getDisplayMetrics().density * (largePadding ? 60 : 16));
        infoBar.setPadding(paddingHorizontal, 0, paddingHorizontal, 0);

        recyclerView = root.findViewById(R.id.recyclerView);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        recyclerView.setRecycledViewPool(((MainActivity) getActivity()).getRecycledViewPool());
        registerForContextMenu(recyclerView);
        recyclerView.addOnScrollListener(new LiftOnScrollListener(root.findViewById(R.id.appbar)));

        swipeActions = new QueueSwipeActions();
        swipeActions.setFilter(new FeedItemFilter(FeedItemFilter.QUEUED));
        swipeActions.attachTo(recyclerView);

        recyclerAdapter = new QueueRecyclerAdapter((MainActivity) getActivity(), swipeActions) {
            @Override
            public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
                super.onCreateContextMenu(menu, v, menuInfo);
                FeedItem longPressedItem = getLongPressedItem();
                if (longPressedItem != null && activeQueue != null) {
                    MenuItem defaultQueueItem = menu.findItem(R.id.set_default_queue_for_show_item);
                    if (defaultQueueItem != null) {
                        boolean clearsDefault = UserPreferences.getFeedDefaultQueue(longPressedItem.getFeedId()) == activeQueue.getId();
                        defaultQueueItem.setTitle(clearsDefault
                                ? R.string.clear_default_queue_for_show_label
                                : R.string.set_default_queue_for_show_label);
                    }
                }
                MenuItemUtils.setOnClickListeners(menu, QueueFragment.this::onContextItemSelected);
            }

            @Override
            protected void onSelectedItemsUpdated() {
                super.onSelectedItemsUpdated();
                Menu menu = floatingSelectMenu.getMenu();
                List<FeedItem> selectedItems = getSelectedItems();
                FeedItemMenuHandler.onPrepareMenu(floatingSelectMenu.getMenu(), getSelectedItems(),
                        R.id.add_to_queue_item, R.id.remove_inbox_item);

                Pair<Boolean, Boolean> canMove = canMove(queue, selectedItems);
                menu.findItem(R.id.move_to_top_item).setVisible(canMove.first);
                menu.findItem(R.id.move_to_bottom_item).setVisible(canMove.second);
                menu.findItem(R.id.send_to_queue_item).setVisible(queues != null && queues.size() > 1 && !selectedItems.isEmpty());

                floatingSelectMenu.updateItemVisibility();
            }
        };
        recyclerAdapter.setOnSelectModeListener(this);
        recyclerView.setAdapter(recyclerAdapter);

        swipeRefreshLayout = root.findViewById(R.id.swipeRefresh);
        swipeRefreshLayout.setDistanceToTriggerSync(getResources().getInteger(R.integer.swipe_refresh_distance));
        swipeRefreshLayout.setOnRefreshListener(() -> FeedUpdateManager.getInstance().runOnceOrAsk(requireContext()));

        emptyView = new EmptyViewHandler(getContext());
        emptyView.attachToRecyclerView(recyclerView);
        emptyView.setIcon(R.drawable.ic_playlist_play);
        emptyView.setTitle(R.string.no_items_header_label);
        emptyView.setMessage(R.string.no_items_label);
        emptyView.updateAdapter(recyclerAdapter);

        floatingSelectMenu = root.findViewById(R.id.floatingSelectMenu);
        floatingSelectMenu.inflate(R.menu.episodes_apply_action_speeddial);
        floatingSelectMenu.setOnMenuItemClickListener(menuItem -> {
            if (recyclerAdapter.getSelectedCount() == 0) {
                EventBus.getDefault().post(new MessageEvent(getString(R.string.no_items_selected_message)));
                return false;
            }
            if (menuItem.getItemId() == R.id.send_to_queue_item) {
                showSendToQueueDialog(recyclerAdapter.getSelectedItems(), recyclerAdapter::endSelectMode);
                return true;
            }
            new EpisodeMultiSelectActionHandler(getActivity(), menuItem.getItemId())
                    .handleAction(recyclerAdapter.getSelectedItems());
            recyclerAdapter.endSelectMode();
            return true;
        });
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow);
        super.onSaveInstanceState(outState);
    }

    private void refreshInfoBar() {
        infoBar.setText(getQueueInfoLabel(queue));

        if (recyclerAdapter.inActionMode()) {
            infoBar.setVisibility(View.INVISIBLE);
        } else {
            infoBar.setVisibility(View.VISIBLE);
        }
    }

    private void loadItems() {
        Log.d(TAG, "loadItems()");
        if (disposable != null) {
            disposable.dispose();
        }
        if (queue == null) {
            emptyView.hide();
        }
        disposable = Observable.fromCallable(() -> {
            boolean displayGoToInboxButton = DBReader.getTotalEpisodeCount(new FeedItemFilter(FeedItemFilter.NEW)) > 0;
            List<FeedItem> activeQueueItems = DBReader.getQueue();
            NamedQueue activeQueue = DBReader.getActiveQueue();
            List<NamedQueue> queues = DBReader.getQueues();
            Map<Long, String> queueInfoLabels = buildQueueInfoLabels(activeQueue, queues, activeQueueItems);
            return new QueueLoadData(activeQueueItems, displayGoToInboxButton,
                    activeQueue, queues, queueInfoLabels);
        })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(data -> {
                    final boolean restoreScrollPosition = queue == null || queue.isEmpty();
                    queue = data.queue;
                    activeQueue = data.activeQueue;
                    queues = data.queues;
                    queueInfoLabels = data.queueInfoLabels;
                    if (activeQueue != null) {
                        toolbar.setTitle(activeQueue.getName());
                    }
                    if (data.displayGoToInboxButton) {
                        emptyView.setMessage(R.string.no_queue_items_inbox_has_items_label);
                        emptyView.setButtonText(R.string.no_queue_items_inbox_has_items_button_label);
                        emptyView.setButtonVisibility(View.VISIBLE);
                        emptyView.setButtonOnClickListener(v -> ((MainActivity) getActivity())
                                .loadChildFragment(new InboxFragment()));
                    }
                    progressBar.setVisibility(View.GONE);
                    recyclerAdapter.setDummyViews(0);
                    recyclerAdapter.updateItems(queue);
                    if (restoreScrollPosition) {
                        Pair<Integer, Integer> scrollPosition = new Pair<>(
                                prefs.getInt(SCROLL_POSITION_KEY, 0), prefs.getInt(SCROLL_OFFSET_KEY, 0));
                        recyclerView.restoreScrollPosition(scrollPosition);
                    }
                    refreshInfoBar();
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void showSwitchQueueDialog() {
        if (queues == null || queues.isEmpty()) {
            return;
        }
        String[] names = new String[queues.size()];
        int checkedItem = 0;
        for (int i = 0; i < queues.size(); i++) {
            names[i] = queues.get(i).getName();
            if (activeQueue != null && queues.get(i).getId() == activeQueue.getId()) {
                checkedItem = i;
            }
        }
        final int[] selected = {checkedItem};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.switch_queue)
                .setSingleChoiceItems(names, checkedItem, (dialog, which) -> selected[0] = which)
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    long selectedQueueId = queues.get(selected[0]).getId();
                    runQueueTask(DBWriter.switchQueue(selectedQueueId));
                })
                .show();
    }

    private void showQueueManagementDialog() {
        if (queues == null || queues.isEmpty()) {
            return;
        }
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.queue_management_dialog, null);
        ScrollView scrollView = dialogView.findViewById(R.id.queue_management_scroll);
        int screenHeightPx = getResources().getDisplayMetrics().heightPixels;
        int minListHeightPx = (int) (screenHeightPx * 0.30f);
        int preferredListHeightPx = (int) (screenHeightPx * 0.45f);
        int reservedDialogChromePx = (int) (getResources().getDisplayMetrics().density * 220);
        int maxHeightPx = Math.min(preferredListHeightPx,
                Math.max(minListHeightPx, screenHeightPx - reservedDialogChromePx));
        ViewGroup.LayoutParams scrollParams = scrollView.getLayoutParams();
        scrollParams.height = maxHeightPx;
        scrollView.setLayoutParams(scrollParams);
        LinearLayout listContainer = dialogView.findViewById(R.id.queue_management_list);
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.manage_queues)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel_label, null)
                .create();
        populateQueueManagementList(listContainer, dialog);
        EditText addQueueInput = dialogView.findViewById(R.id.queue_management_add_input);
        ImageButton addQueueButton = dialogView.findViewById(R.id.queue_management_add_confirm);
        addQueueButton.setOnClickListener(v -> {
            String queueName = addQueueInput.getText().toString().trim();
            if (queueName.isEmpty()) {
                return;
            }
            Observable.fromCallable(() -> {
                        DBWriter.createQueue(queueName).get();
                        NamedQueue latestActiveQueue = DBReader.getActiveQueue();
                        List<NamedQueue> latestQueues = DBReader.getQueues();
                        List<FeedItem> latestActiveQueueItems = DBReader.getQueue();
                        Map<Long, String> latestQueueInfoLabels = buildQueueInfoLabels(
                                latestActiveQueue, latestQueues, latestActiveQueueItems);
                        return new QueueManagementData(latestActiveQueue, latestQueues, latestQueueInfoLabels);
                    })
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(updatedData -> {
                        activeQueue = updatedData.activeQueue;
                        queues = updatedData.queues;
                        queueInfoLabels = updatedData.queueInfoLabels;
                        addQueueInput.setText("");
                        populateQueueManagementList(listContainer, dialog);
                    }, error -> Log.e(TAG, Log.getStackTraceString(error)));
        });
        dialog.show();
    }

    private void populateQueueManagementList(@NonNull LinearLayout listContainer, @NonNull AlertDialog dialog) {
        listContainer.removeAllViews();
        for (NamedQueue queueOption : queues) {
            View itemView = LayoutInflater.from(requireContext()).inflate(R.layout.queue_management_item, listContainer, false);
            TextView nameView = itemView.findViewById(R.id.queue_management_name);
            TextView subtitleView = itemView.findViewById(R.id.queue_management_subtitle);
            TextView currentLabelView = itemView.findViewById(R.id.queue_management_current_label);
            View openArea = itemView.findViewById(R.id.queue_management_open_area);
            ImageButton deleteButton = itemView.findViewById(R.id.queue_management_delete);
            nameView.setText(queueOption.getName());
            boolean isActive = activeQueue != null && activeQueue.getId() == queueOption.getId();
            String queueInfoLabel = queueInfoLabels != null ? queueInfoLabels.get(queueOption.getId()) : null;
            if (queueInfoLabel == null) {
                queueInfoLabel = getQueueInfoLabel(Collections.emptyList());
            }
            subtitleView.setText(queueInfoLabel);
            subtitleView.setVisibility(View.VISIBLE);
            currentLabelView.setVisibility(isActive ? View.VISIBLE : View.GONE);
            openArea.setOnClickListener(v -> {
                dialog.dismiss();
                if (!isActive) {
                    runQueueTask(DBWriter.switchQueue(queueOption.getId()));
                }
            });
            boolean canDelete = queues.size() > 1;
            deleteButton.setVisibility(canDelete ? View.VISIBLE : View.INVISIBLE);
            if (canDelete) {
                deleteButton.setOnClickListener(v -> {
                    dialog.dismiss();
                    showDeleteQueueDialog(queueOption);
                });
            }
            listContainer.addView(itemView);
        }
    }

    private void showCreateQueueDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint(R.string.queue_name);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.create_queue_title)
                .setView(input)
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.create_queue, (dialog, which) -> {
                    String queueName = input.getText().toString().trim();
                    if (!queueName.isEmpty()) {
                        runQueueTask(DBWriter.createQueue(queueName));
                    }
                })
                .show();
    }

    private void showRenameQueueDialog() {
        if (activeQueue == null) {
            return;
        }
        final EditText input = new EditText(requireContext());
        input.setText(activeQueue.getName());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rename_queue_title)
                .setView(input)
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.rename_queue, (dialog, which) -> {
                    String queueName = input.getText().toString().trim();
                    if (!queueName.isEmpty()) {
                        runQueueTask(DBWriter.renameQueue(activeQueue.getId(), queueName));
                    }
                })
                .show();
    }

    private void showDeleteQueueDialog(@NonNull NamedQueue queueToDelete) {
        if (queues != null && queues.size() <= 1) {
            EventBus.getDefault().post(new MessageEvent(getString(R.string.cannot_delete_last_queue)));
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_queue)
                .setMessage(getString(R.string.delete_queue_confirmation_msg, queueToDelete.getName()))
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.delete_queue, (dialog, which) -> runQueueTask(DBWriter.deleteQueue(queueToDelete.getId())))
                .show();
    }

    private void toggleDefaultQueueForShow(FeedItem selectedItem) {
        if (activeQueue == null) {
            return;
        }
        if (UserPreferences.getFeedDefaultQueue(selectedItem.getFeedId()) == activeQueue.getId()) {
            UserPreferences.clearFeedDefaultQueue(selectedItem.getFeedId());
            EventBus.getDefault().post(new MessageEvent(getString(R.string.show_default_queue_cleared)));
        } else {
            UserPreferences.setFeedDefaultQueue(selectedItem.getFeedId(), activeQueue.getId());
            EventBus.getDefault().post(new MessageEvent(getString(R.string.show_default_queue_set)));
        }
    }

    private void showSendToQueueDialog(List<FeedItem> selectedItems,  Runnable onComplete) {
        if (activeQueue == null || queues == null || queues.size() <= 1) {
            EventBus.getDefault().post(new MessageEvent(getString(R.string.no_other_queues_available)));
            return;
        }
        List<NamedQueue> targetQueues = new java.util.ArrayList<>();
        for (NamedQueue queueOption : queues) {
            if (queueOption.getId() != activeQueue.getId()) {
                targetQueues.add(queueOption);
            }
        }
        if (targetQueues.isEmpty()) {
            EventBus.getDefault().post(new MessageEvent(getString(R.string.no_other_queues_available)));
            return;
        }
        String[] names = new String[targetQueues.size()];
        for (int i = 0; i < targetQueues.size(); i++) {
            names[i] = targetQueues.get(i).getName();
        }
        final int[] selected = {0};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.send_to_queue_label)
                .setSingleChoiceItems(names, 0, (dialog, which) -> selected[0] = which)
                .setNegativeButton(R.string.cancel_label, null)
                .setPositiveButton(R.string.confirm_label, (dialog, which) ->
                        runQueueTask(DBWriter.moveQueueItemsToQueue(selectedItems, targetQueues.get(selected[0]).getId()), onComplete))
                .show();
    }

    private void runQueueTask(Future<?> task) {
        runQueueTask(task, null);
    }

    private void runQueueTask(Future<?> task, @Nullable Runnable onSuccess) {
        Observable.fromCallable(() -> {
                    task.get();
                    return true;
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(success -> {
                    loadItems();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private String getQueueInfoLabel(@NonNull List<FeedItem> queueItems) {
        long timeLeft = 0;
        for (FeedItem item : queueItems) {
            float playbackSpeed = 1;
            if (UserPreferences.timeRespectsSpeed()) {
                playbackSpeed = PlaybackSpeedUtils.getCurrentPlaybackSpeed(item.getMedia());
            }
            if (item.getMedia() != null) {
                long itemTimeLeft = item.getMedia().getDuration() - item.getMedia().getPosition();
                timeLeft += (long) (itemTimeLeft / playbackSpeed);
            }
        }
        String episodes = getResources().getQuantityString(R.plurals.num_episodes, queueItems.size(), queueItems.size());
        String time = Converter.getDurationStringLocalized(getResources(), timeLeft, false);
        return getString(R.string.queue_time_left_label, episodes, time);
    }

    private Map<Long, String> buildQueueInfoLabels(@Nullable NamedQueue activeQueueOption,
                                                    @NonNull List<NamedQueue> allQueues,
                                                    @NonNull List<FeedItem> activeQueueItems) {
        Map<Long, String> labels = new HashMap<>();
        for (NamedQueue queueOption : allQueues) {
            List<FeedItem> queueItems = activeQueueOption != null && activeQueueOption.getId() == queueOption.getId()
                    ? activeQueueItems
                    : DBReader.getQueue(queueOption.getId());
            labels.put(queueOption.getId(), getQueueInfoLabel(queueItems));
        }
        return labels;
    }

    private static class QueueLoadData {
        private final List<FeedItem> queue;
        private final boolean displayGoToInboxButton;
        private final NamedQueue activeQueue;
        private final List<NamedQueue> queues;
        private final Map<Long, String> queueInfoLabels;

        private QueueLoadData(List<FeedItem> queue,
                              boolean displayGoToInboxButton,
                              NamedQueue activeQueue,
                              List<NamedQueue> queues,
                              Map<Long, String> queueInfoLabels) {
            this.queue = queue;
            this.displayGoToInboxButton = displayGoToInboxButton;
            this.activeQueue = activeQueue;
            this.queues = queues;
            this.queueInfoLabels = queueInfoLabels;
        }
    }

    private static class QueueManagementData {
        private final NamedQueue activeQueue;
        private final List<NamedQueue> queues;
        private final Map<Long, String> queueInfoLabels;

        private QueueManagementData(@Nullable NamedQueue activeQueue,
                                    @NonNull List<NamedQueue> queues,
                                    @NonNull Map<Long, String> queueInfoLabels) {
            this.activeQueue = activeQueue;
            this.queues = queues;
            this.queueInfoLabels = queueInfoLabels;
        }
    }

    @Override
    public void onStartSelectMode() {
        swipeActions.detach();
        floatingSelectMenu.setVisibility(View.VISIBLE);
        recyclerView.setPadding(recyclerView.getPaddingLeft(), recyclerView.getPaddingTop(),
                recyclerView.getPaddingRight(),
                (int) getResources().getDimension(R.dimen.floating_select_menu_height));
        refreshToolbarState();
        refreshInfoBar();
    }

    @Override
    public void onEndSelectMode() {
        floatingSelectMenu.setVisibility(View.GONE);
        recyclerView.setPadding(recyclerView.getPaddingLeft(), recyclerView.getPaddingTop(),
                recyclerView.getPaddingRight(), 0);
        infoBar.setVisibility(View.VISIBLE);
        swipeActions.attachTo(recyclerView);
        refreshInfoBar();
    }

    public static class QueueSortDialog extends ItemSortDialog {
        boolean turnedOffKeepSortedForRandom = false;

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            if (UserPreferences.isQueueKeepSorted()) {
                sortOrder = UserPreferences.getQueueKeepSortedOrder();
            }
            final View view = super.onCreateView(inflater, container, savedInstanceState);
            viewBinding.keepSortedCheckbox.setVisibility(View.VISIBLE);
            viewBinding.keepSortedCheckbox.setChecked(UserPreferences.isQueueKeepSorted());
            // Disable until something gets selected
            viewBinding.keepSortedCheckbox.setEnabled(UserPreferences.isQueueKeepSorted());
            return view;
        }

        @Override
        protected void onAddItem(int title, SortOrder ascending, SortOrder descending, boolean ascendingIsDefault) {
            if (ascending == SortOrder.EPISODE_FILENAME_A_Z || ascending == SortOrder.SIZE_SMALL_LARGE) {
                return;
            }
            if (ascending == SortOrder.DATE_OLD_NEW || ascending == SortOrder.SMART_SHUFFLE_OLD_NEW) {
                ascendingIsDefault = true;
            }
            super.onAddItem(title, ascending, descending, ascendingIsDefault);
        }

        @Override
        protected void onSelectionChanged() {
            super.onSelectionChanged();
            if (sortOrder == SortOrder.RANDOM) {
                turnedOffKeepSortedForRandom |= viewBinding.keepSortedCheckbox.isChecked();
                viewBinding.keepSortedCheckbox.setChecked(false);
                viewBinding.keepSortedCheckbox.setEnabled(false);
            } else {
                if (turnedOffKeepSortedForRandom) {
                    viewBinding.keepSortedCheckbox.setChecked(true);
                    turnedOffKeepSortedForRandom = false;
                }
                viewBinding.keepSortedCheckbox.setEnabled(true);
            }
            UserPreferences.setQueueKeepSorted(viewBinding.keepSortedCheckbox.isChecked());
            UserPreferences.setQueueKeepSortedOrder(sortOrder);
            DBWriter.reorderQueue(sortOrder, true);
        }
    }

    /**
     * This method checks if the selected items are allowed to be moved to the top, bottom or both of the Queue.
     * @param queue The FeedItems currently in the Queue.
     * @param selectedItems The FeedItems for which the check is performed.
     * @return A Pair of booleans where
     *           [0] is true if moving to the top is allowed, false otherwise.
     *           [1] is true if moving to the bottom is allowed, false otherwise.
     * */
    public static Pair<Boolean, Boolean> canMove(List<FeedItem> queue, List<FeedItem> selectedItems) {
        int queueSize = queue.size();
        int selectedSize = selectedItems.size();
        // No manual reordering allowed or reordering would be a no-op.
        if (selectedItems.isEmpty() || queue.isEmpty() || UserPreferences.isQueueLocked()
                || UserPreferences.isQueueKeepSorted() || selectedSize == queueSize) {
            return new Pair<>(false, false);
        }
        boolean isFirstItemSelected = selectedItems.get(0).getId() == queue.get(0).getId();
        boolean isLastItemSelected = selectedItems.get(selectedSize - 1).getId() == queue.get(queueSize - 1).getId();
        // If only one item is selected and its already at the top of the list, disable option to move item to the top.
        // If the item is already at the bottom of the list, disable the option to move it to the bottom.
        if (selectedSize == 1) {
            return new Pair<>(!isFirstItemSelected, !isLastItemSelected);
        }
        // If contiguous from the top, moving items to the top is disabled, as they are already there.
        if (isFirstItemSelected && selectedItems.equals(queue.subList(0, selectedSize))) {
            return new Pair<>(false, true);
        }
        // If contiguous from the bottom, moving items to the bottom is disabled, as they are already there.
        if (isLastItemSelected && selectedItems.equals(queue.subList(queueSize - selectedSize, queueSize))) {
            return new Pair<>(true, false);
        }
        return new Pair<>(true, true);
    }

    private class QueueSwipeActions extends SwipeActions {

        // Position tracking whilst dragging
        int dragFrom = -1;
        int dragTo = -1;

        public QueueSwipeActions() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, QueueFragment.this, TAG);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int fromPosition = viewHolder.getBindingAdapterPosition();
            int toPosition = target.getBindingAdapterPosition();

            // Update tracked position
            if (dragFrom == -1) {
                dragFrom =  fromPosition;
            }
            dragTo = toPosition;

            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            Log.d(TAG, "move(" + from + ", " + to + ") in memory");
            if (queue == null || from >= queue.size() || to >= queue.size() || from < 0 || to < 0) {
                return false;
            }
            queue.add(to, queue.remove(from));
            recyclerAdapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            if (disposable != null) {
                disposable.dispose();
            }

            //SwipeActions
            super.onSwiped(viewHolder, direction);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            // Check if drag finished
            if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                reallyMoved(dragFrom, dragTo);
            }

            dragFrom = dragTo = -1;
        }

        private void reallyMoved(int from, int to) {
            // Write drag operation to database
            Log.d(TAG, "Write to database move(" + from + ", " + to + ")");
            DBWriter.moveQueueItem(from, to, true);
        }

    }
}
