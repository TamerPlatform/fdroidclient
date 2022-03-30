package org.fdroid.fdroid.views.apps;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.fdroid.fdroid.AppUpdateStatusManager;
import org.fdroid.fdroid.AppUpdateStatusManager.AppUpdateStatus;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.installer.ApkCache;
import org.fdroid.fdroid.installer.InstallManagerService;
import org.fdroid.fdroid.installer.Installer;
import org.fdroid.fdroid.installer.InstallerFactory;
import org.fdroid.fdroid.views.AppDetailsActivity;
import org.fdroid.fdroid.views.updates.UpdatesAdapter;

import java.io.File;
import java.util.Iterator;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Supports the following layouts:
 * <ul>
 * <li>app_list_item (see {@link StandardAppListItemController}</li>
 * <li>updateable_app_list_status_item (see
 * {@link org.fdroid.fdroid.views.updates.items.AppStatusListItemController}</li>
 * <li>updateable_app_list_item (see
 * {@link org.fdroid.fdroid.views.updates.items.UpdateableAppListItemController}</li>
 * <li>installed_app_list_item (see {@link StandardAppListItemController}</li>
 * </ul>
 * <p>
 * The state of the UI is defined in a dumb {@link AppListItemState} class, then applied to the UI
 * in the {@link #updateAppStatus(App, AppUpdateStatus)}  method.
 */
public abstract class AppListItemController extends RecyclerView.ViewHolder {

    private static final String TAG = "AppListItemController";

    private static Preferences prefs;

    protected final AppCompatActivity activity;

    @NonNull
    private final ImageView icon;

    @NonNull
    private final TextView name;

    @Nullable
    private final ImageView installButton;

    @Nullable
    private final TextView status;

    @Nullable
    private final TextView secondaryStatus;

    @Nullable
    private final ProgressBar progressBar;

    @Nullable
    private final ImageButton cancelButton;

    /**
     * Will operate as the "Download is complete, click to (install|update)" button, as well as the
     * "Installed successfully, click to run" button.
     */
    @Nullable
    private final Button actionButton;

    @Nullable
    private final Button secondaryButton;

    @Nullable
    private final CheckBox checkBox;

    @Nullable
    private App currentApp;
    @Nullable
    private Apk currentApk;

    @Nullable
    private AppUpdateStatus currentStatus;

    @TargetApi(21)
    public AppListItemController(final AppCompatActivity activity, View itemView) {
        super(itemView);
        this.activity = activity;
        if (prefs == null) {
            prefs = Preferences.get();
        }

        installButton = (ImageView) itemView.findViewById(R.id.install);
        if (installButton != null) {
            installButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onActionButtonPressed(currentApp, currentApk);
                }
            });

            if (Build.VERSION.SDK_INT >= 21) {
                installButton.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        float density = activity.getResources().getDisplayMetrics().density;

                        // This is a bit hacky/hardcoded/too-specific to the particular icons we're using.
                        // This is because the default "download & install" and "downloaded & ready to install"
                        // icons are smaller than the "downloading progress" button. Hence, we can't just use
                        // the width/height of the view to calculate the outline size.
                        int xPadding = (int) (8 * density);
                        int yPadding = (int) (9 * density);
                        int right = installButton.getWidth() - xPadding;
                        int bottom = installButton.getHeight() - yPadding;
                        outline.setOval(xPadding, yPadding, right, bottom);
                    }
                });
            }
        }

        icon = (ImageView) itemView.findViewById(R.id.icon);
        name = (TextView) itemView.findViewById(R.id.app_name);
        status = (TextView) itemView.findViewById(R.id.status);
        secondaryStatus = (TextView) itemView.findViewById(R.id.secondary_status);
        progressBar = (ProgressBar) itemView.findViewById(R.id.progress_bar);
        cancelButton = (ImageButton) itemView.findViewById(R.id.cancel_button);
        actionButton = (Button) itemView.findViewById(R.id.action_button);
        secondaryButton = (Button) itemView.findViewById(R.id.secondary_button);
        checkBox = itemView.findViewById(R.id.checkbox);

        if (actionButton != null) {
            actionButton.setEnabled(true);
            actionButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    actionButton.setEnabled(false);
                    onActionButtonPressed(currentApp, currentApk);
                }
            });
        }

        if (secondaryButton != null) {
            secondaryButton.setOnClickListener(onSecondaryButtonClicked);
        }

        if (cancelButton != null) {
            cancelButton.setOnClickListener(onCancelDownload);
        }

        itemView.setOnClickListener(onAppClicked);
    }

    @Nullable
    protected final AppUpdateStatus getCurrentStatus() {
        return currentStatus;
    }

    public void bindModel(@NonNull App app, Apk apk, @Nullable AppUpdateStatus s) {
        currentApp = app;
        if (apk == null) throw new IllegalStateException(); // TODO remove at the end and make Apk @NonNull
        currentApk = apk;

        if (actionButton != null) actionButton.setEnabled(true);

        Utils.setIconFromRepoOrPM(app, icon, activity);

        AppUpdateStatus status = s;
        if (status == null) {
            // Figures out the current install/update/download/etc status for the app we are viewing.
            // Then, asks the view to update itself to reflect this status.
            Iterator<AppUpdateStatus> statuses =
                    AppUpdateStatusManager.getInstance(activity).getByPackageName(app.packageName).iterator();
            if (statuses.hasNext()) {
                status = statuses.next();
            }
        }
        updateAppStatus(app, status);

        final LocalBroadcastManager broadcastManager =
                LocalBroadcastManager.getInstance(activity.getApplicationContext());
        broadcastManager.unregisterReceiver(onStatusChanged);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_ADDED);
        intentFilter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_REMOVED);
        intentFilter.addAction(AppUpdateStatusManager.BROADCAST_APPSTATUS_CHANGED);
        broadcastManager.registerReceiver(onStatusChanged, intentFilter);
    }

    /**
     * To be overridden if required
     */
    public boolean canDismiss() {
        return false;
    }

    /**
     * If able, forwards the request onto {@link #onDismissApp(App, UpdatesAdapter)}.
     * This mainly exists to keep the API consistent, in that the {@link App} is threaded through to the relevant
     * method with a guarantee that it is not null, rather than every method having to check if it is null or not.
     */
    public final void onDismiss(UpdatesAdapter adapter) {
        if (currentApp != null && canDismiss()) {
            onDismissApp(currentApp, adapter);
        }
    }

    /**
     * Override to respond to the user swiping an app to dismiss it from the list.
     *
     * @param app            The app that was swiped away
     * @param updatesAdapter The adapter. Can be used for refreshing the adapter with adapter.refreshStatuses().
     * @see #canDismiss() This must also be overridden and should return true.
     */
    protected void onDismissApp(@NonNull App app, UpdatesAdapter updatesAdapter) {
    }

    /**
     * Updates both the progress bar and the circular install button (which
     * shows progress around the outside of the circle). Also updates the app
     * label to indicate that the app is being downloaded.
     * <p>
     * Queries the current state via {@link #getCurrentViewState(App, AppUpdateStatus)}
     * and then updates the relevant widgets depending on that state.
     * <p>
     * Should contain little to no business logic, this all belongs to
     * {@link #getCurrentViewState(App, AppUpdateStatus)}.
     *
     * @see AppListItemState
     * @see #getCurrentViewState(App, AppUpdateStatus)
     */
    private void updateAppStatus(@NonNull App app, @Nullable AppUpdateStatus appStatus) {
        currentStatus = appStatus;

        AppListItemState viewState = getCurrentViewState(app, appStatus);

        name.setText(viewState.getMainText());

        if (actionButton != null) {
            if (viewState.shouldShowActionButton()) {
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setEnabled(true);
                actionButton.setText(viewState.getActionButtonText());
            } else {
                actionButton.setVisibility(View.GONE);
            }
        }

        if (secondaryButton != null) {
            if (viewState.shouldShowSecondaryButton()) {
                secondaryButton.setVisibility(View.VISIBLE);
                secondaryButton.setEnabled(true);
                secondaryButton.setText(viewState.getSecondaryButtonText());
            } else {
                secondaryButton.setVisibility(View.GONE);
            }
        }

        if (progressBar != null) {
            if (viewState.showProgress()) {
                progressBar.setVisibility(View.VISIBLE);
                if (viewState.isProgressIndeterminate()) {
                    progressBar.setIndeterminate(true);
                } else {
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(viewState.getProgressMax());
                    progressBar.setProgress(viewState.getProgressCurrent());
                }
            } else {
                progressBar.setVisibility(View.GONE);
            }
        }

        if (cancelButton != null) {
            if (viewState.showProgress()) {
                cancelButton.setVisibility(View.VISIBLE);
            } else {
                cancelButton.setVisibility(View.GONE);
            }
        }

        if (installButton != null) {
            if (viewState.shouldShowActionButton()) {
                installButton.setVisibility(View.GONE);
            } else if (viewState.showProgress()) {
                installButton.setEnabled(false);
                installButton.setVisibility(View.VISIBLE);
                installButton.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_download_progress));
                int progressAsDegrees = viewState.getProgressMax() <= 0 ? 0 :
                        (int) (((float) viewState.getProgressCurrent() / viewState.getProgressMax()) * 360);
                installButton.setImageLevel(progressAsDegrees);
            } else if (viewState.shouldShowInstall()) {
                installButton.setEnabled(true);
                installButton.setVisibility(View.VISIBLE);
                installButton.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.ic_download));
            } else {
                installButton.setVisibility(View.GONE);
            }
        }

        if (status != null) {
            CharSequence statusText = viewState.getStatusText();
            if (statusText == null) {
                status.setVisibility(View.GONE);
            } else {
                status.setVisibility(View.VISIBLE);
                status.setText(statusText);
            }
        }

        if (secondaryStatus != null) {
            CharSequence statusText = viewState.getSecondaryStatusText();
            if (statusText == null) {
                secondaryStatus.setVisibility(View.GONE);
            } else {
                secondaryStatus.setVisibility(View.VISIBLE);
                secondaryStatus.setText(statusText);
            }
        }

        if (checkBox != null) {
            if (viewState.shouldShowCheckBox()) {
                itemView.setOnClickListener(selectInstalledAppListener);
                checkBox.setChecked(viewState.isCheckBoxChecked());
                checkBox.setVisibility(View.VISIBLE);
                status.setVisibility(View.GONE);
                secondaryStatus.setVisibility(View.GONE);
            } else {
                checkBox.setVisibility(View.GONE);
            }
        }
    }

    @NonNull
    protected AppListItemState getCurrentViewState(@NonNull App app, @Nullable AppUpdateStatus appStatus) {
        if (appStatus == null) {
            return getViewStateDefault(app);
        } else {
            switch (appStatus.status) {
                case ReadyToInstall:
                    return getViewStateReadyToInstall(app);

                case PendingInstall:
                case Downloading:
                    return getViewStateDownloading(app, appStatus);

                case Installing:
                    return getViewStateInstalling(app);

                case Installed:
                    return getViewStateInstalled(app);

                default:
                    return getViewStateDefault(app);
            }
        }
    }

    protected AppListItemState getViewStateInstalling(@NonNull App app) {
        CharSequence mainText = activity.getString(
                R.string.app_list__name__downloading_in_progress, app.name);

        return new AppListItemState(app)
                .setMainText(mainText)
                .showActionButton(null)
                .setStatusText(activity.getString(R.string.notification_content_single_installing, app.name));
    }

    protected AppListItemState getViewStateInstalled(@NonNull App app) {
        CharSequence mainText = activity.getString(
                R.string.app_list__name__successfully_installed, app.name);

        AppListItemState state = new AppListItemState(app)
                .setMainText(mainText)
                .setStatusText(activity.getString(R.string.notification_content_single_installed));

        if (activity.getPackageManager().getLaunchIntentForPackage(app.packageName) != null) {
            Utils.debugLog(TAG, "Not showing 'Open' button for " + app.packageName + " because no intent.");
            state.showActionButton(activity.getString(R.string.menu_launch));
        }

        return state;
    }

    protected AppListItemState getViewStateDownloading(@NonNull App app, @NonNull AppUpdateStatus currentStatus) {
        CharSequence mainText = activity.getString(
                R.string.app_list__name__downloading_in_progress, app.name);

        return new AppListItemState(app)
                .setMainText(mainText)
                .setProgress(Utils.bytesToKb(currentStatus.progressCurrent),
                        Utils.bytesToKb(currentStatus.progressMax));
    }

    protected AppListItemState getViewStateReadyToInstall(@NonNull App app) {
        int actionButtonLabel = app.isInstalled(activity.getApplicationContext())
                ? R.string.app__install_downloaded_update
                : R.string.menu_install;

        return new AppListItemState(app)
                .setMainText(app.name)
                .showActionButton(activity.getString(actionButtonLabel))
                .setStatusText(activity.getString(R.string.app_list_download_ready));
    }

    protected AppListItemState getViewStateDefault(@NonNull App app) {
        return new AppListItemState(app);
    }

    /* =================================================================
     * Various listeners for each different click/broadcast that we need
     * to respond to.
     * =================================================================
     */

    @SuppressWarnings("FieldCanBeLocal")
    private final View.OnClickListener onAppClicked = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (currentApp == null) {
                return;
            }

            Intent intent = new Intent(activity, AppDetailsActivity.class);
            intent.putExtra(AppDetailsActivity.EXTRA_APPID, currentApp.packageName);
            String transitionAppIcon = activity.getString(R.string.transition_app_item_icon);
            Pair<View, String> iconTransitionPair = Pair.create((View) icon, transitionAppIcon);
            // unchecked since the right type is passed as 2nd varargs arg: Pair<View, String>
            @SuppressWarnings("unchecked")
            Bundle bundle = ActivityOptionsCompat
                    .makeSceneTransitionAnimation(activity, iconTransitionPair).toBundle();
            ContextCompat.startActivity(activity, intent, bundle);
        }
    };

    private final BroadcastReceiver onStatusChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            AppUpdateStatus newStatus = intent.getParcelableExtra(AppUpdateStatusManager.EXTRA_STATUS);

            if (currentApp == null
                    || !TextUtils.equals(newStatus.app.packageName, currentApp.packageName)
                    || (installButton == null && progressBar == null)) {
                return;
            }

            updateAppStatus(currentApp, newStatus);
        }
    };

    @SuppressWarnings("FieldCanBeLocal")
    private final View.OnClickListener onSecondaryButtonClicked = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (currentApp == null) {
                return;
            }
            if (secondaryButton != null) secondaryButton.setEnabled(false);
            onSecondaryButtonPressed(currentApp);
        }
    };

    protected void onActionButtonPressed(App app, Apk apk) {
        if (app == null || apk == null) {
            return;
        }

        // When the button says "Open", then launch the app.
        if (currentStatus != null && currentStatus.status == AppUpdateStatusManager.Status.Installed) {
            Intent intent = activity.getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (intent != null) {
                activity.startActivity(intent);

                // Once it is explicitly launched by the user, then we can pretty much forget about
                // any sort of notification that the app was successfully installed. It should be
                // apparent to the user because they just launched it.
                AppUpdateStatusManager.getInstance(activity).removeApk(currentStatus.getCanonicalUrl());
            }
            return;
        }

        if (currentStatus != null && currentStatus.status == AppUpdateStatusManager.Status.ReadyToInstall) {
            String canonicalUrl = currentStatus.apk.getCanonicalUrl();
            File apkFilePath = ApkCache.getApkDownloadPath(activity, canonicalUrl);
            Utils.debugLog(TAG, "skip download, we have already downloaded " + currentStatus.apk.getCanonicalUrl() +
                    " to " + apkFilePath);

            final LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(activity);
            final BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    broadcastManager.unregisterReceiver(this);

                    if (Installer.ACTION_INSTALL_USER_INTERACTION.equals(intent.getAction())) {
                        PendingIntent pendingIntent =
                                intent.getParcelableExtra(Installer.EXTRA_USER_INTERACTION_PI);
                        try {
                            pendingIntent.send();
                        } catch (PendingIntent.CanceledException ignored) {
                        }
                    }
                }
            };

            Uri canonicalUri = Uri.parse(canonicalUrl);
            broadcastManager.registerReceiver(receiver, Installer.getInstallIntentFilter(canonicalUri));
            Installer installer = InstallerFactory.create(activity, currentStatus.apk);
            installer.installPackage(Uri.parse(apkFilePath.toURI().toString()), canonicalUri);
        } else {
            InstallManagerService.queue(activity, app, apk);
        }
    }

    /**
     * To be overridden by subclasses if desired
     */
    protected void onSecondaryButtonPressed(@NonNull App app) {
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final View.OnClickListener onCancelDownload = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            cancelDownload();
        }
    };

    protected final void cancelDownload() {
        if (currentStatus == null || currentStatus.status != AppUpdateStatusManager.Status.Downloading) {
            return;
        }

        InstallManagerService.cancel(activity, currentStatus.getCanonicalUrl());
    }

    private final View.OnClickListener selectInstalledAppListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Set<String> wipeSet = prefs.getPanicTmpSelectedSet();
            checkBox.toggle();
            if (checkBox.isChecked()) {
                wipeSet.add(currentApp.packageName);
            } else {
                wipeSet.remove(currentApp.packageName);
            }
            prefs.setPanicTmpSelectedSet(wipeSet);
        }
    };
}
