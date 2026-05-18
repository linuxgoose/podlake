package de.danoeh.antennapod.ui.screen.subscriptions;

import android.content.Context;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.NamedQueue;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.screen.feed.RemoveFeedDialog;
import de.danoeh.antennapod.ui.screen.feed.RenameFeedDialog;
import de.danoeh.antennapod.ui.screen.feed.preferences.TagSettingsDialog;
import de.danoeh.antennapod.ui.share.ShareUtils;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.greenrobot.eventbus.EventBus;

import java.util.Collections;
import java.util.List;

/**
 * Handles interactions with the FeedItemMenu.
 */
public abstract class FeedMenuHandler {
    public static boolean onPrepareMenu(Menu menu, List<Feed> selectedItems) {
        if (menu == null || selectedItems == null || selectedItems.isEmpty() || selectedItems.get(0) == null) {
            return false;
        }
        boolean allSubscribed = true;
        boolean allArchived = true;
        for (Feed feed : selectedItems) {
            if (feed.getState() != Feed.STATE_SUBSCRIBED) {
                allSubscribed = false;
            }
            if (feed.getState() != Feed.STATE_ARCHIVED) {
                allArchived = false;
            }
        }
        setItemVisibility(menu, R.id.remove_all_inbox_item, allSubscribed);
        setItemVisibility(menu, R.id.remove_archive_feed, !allArchived && allSubscribed);
        setItemVisibility(menu, R.id.remove_restore_feed, allArchived);
        boolean singleNonLocalFeedSelected = selectedItems.size() == 1 && !selectedItems.get(0).isLocalFeed();
        setItemVisibility(menu, R.id.share_feed, singleNonLocalFeedSelected);
        setItemVisibility(menu, R.id.set_default_queue_for_show_item, singleNonLocalFeedSelected);
        if (singleNonLocalFeedSelected) {
            long selectedQueueId = UserPreferences.getFeedDefaultQueue(selectedItems.get(0).getId());
            setItemTitle(menu, R.id.set_default_queue_for_show_item,
                    selectedQueueId > 0 ? R.string.clear_default_queue_for_show_label
                            : R.string.set_default_queue_for_show_label);
        }
        return true;
    }

    private static void setItemTitle(Menu menu, int menuId, int titleRes) {
        MenuItem item = menu.findItem(menuId);
        if (item != null) {
            item.setTitle(titleRes);
        }
    }

    private static void setItemVisibility(Menu menu, int menuId, boolean visibility) {
        MenuItem item = menu.findItem(menuId);
        if (item != null) {
            item.setVisible(visibility);
        }
    }

    public static boolean onMenuItemClicked(@NonNull Fragment fragment, int menuItemId,
                                            @NonNull Feed selectedFeed) {
        @NonNull Context context = fragment.requireContext();
        if (menuItemId == R.id.rename_folder_item) {
            new RenameFeedDialog(fragment.getActivity(), selectedFeed).show();
        } else if (menuItemId == R.id.remove_all_inbox_item) {
            new FeedMultiSelectActionHandler(fragment.getActivity(), Collections.singletonList(selectedFeed))
                    .handleAction(R.id.remove_all_inbox_item);
        } else if (menuItemId == R.id.edit_tags) {
            TagSettingsDialog.newInstance(Collections.singletonList(selectedFeed.getPreferences()))
                    .show(fragment.getChildFragmentManager(), TagSettingsDialog.TAG);
        } else if (menuItemId == R.id.remove_archive_feed || menuItemId == R.id.remove_restore_feed) {
            new RemoveFeedDialog(Collections.singletonList(selectedFeed))
                    .show(fragment.getChildFragmentManager(), null);
        } else if (menuItemId == R.id.share_feed) {
            ShareUtils.shareFeedLink(context, selectedFeed);
        } else if (menuItemId == R.id.set_default_queue_for_show_item) {
            showDefaultQueueDialog(fragment, selectedFeed);
        } else {
            return false;
        }
        return true;
    }

    private static void showDefaultQueueDialog(@NonNull Fragment fragment, @NonNull Feed selectedFeed) {
        Observable.fromCallable(DBReader::getQueues)
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(queues -> {
                    if (!fragment.isAdded()) {
                        return;
                    }
                    if (queues.isEmpty()) {
                        EventBus.getDefault().post(new MessageEvent(fragment.getString(R.string.no_other_queues_available)));
                        return;
                    }
                    String[] queueNames = new String[queues.size() + 1];
                    queueNames[0] = fragment.getString(R.string.no_default_queue_label);
                    long selectedQueueId = UserPreferences.getFeedDefaultQueue(selectedFeed.getId());
                    int checkedItem = 0;
                    for (int i = 0; i < queues.size(); i++) {
                        NamedQueue queue = queues.get(i);
                        queueNames[i + 1] = queue.getName();
                        if (queue.getId() == selectedQueueId) {
                            checkedItem = i + 1;
                        }
                    }
                    final int[] selected = {checkedItem};
                    new MaterialAlertDialogBuilder(fragment.requireContext())
                            .setTitle(R.string.set_default_queue_for_show_label)
                            .setSingleChoiceItems(queueNames, checkedItem, (dialog, which) -> selected[0] = which)
                            .setNegativeButton(R.string.cancel_label, null)
                            .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                                if (selected[0] == 0) {
                                    UserPreferences.clearFeedDefaultQueue(selectedFeed.getId());
                                    EventBus.getDefault().post(new MessageEvent(fragment.getString(R.string.show_default_queue_cleared)));
                                } else {
                                    NamedQueue selectedQueue = queues.get(selected[0] - 1);
                                    UserPreferences.setFeedDefaultQueue(selectedFeed.getId(), selectedQueue.getId());
                                    EventBus.getDefault().post(new MessageEvent(
                                            fragment.getString(R.string.show_default_queue_set_to, selectedQueue.getName())));
                                }
                            })
                            .show();
                }, error -> EventBus.getDefault().post(new MessageEvent(error.getMessage())));
    }
}
