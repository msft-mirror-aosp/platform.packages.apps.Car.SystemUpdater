/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.systemupdater;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.IOException;

/** Display update state and progress. */
public class UpdateLayoutFragment extends Fragment {
    private static final String TAG = "UpdateLayoutFragment";
    private static final String EXTRA_UPDATE_FILE = "extra_update_file";
    private static final int PERCENT_MAX = 100;
    private static final String REBOOT_REASON = "reboot-ab-update";

    private ProgressBar mProgressBar;
    private TextView mContentTitle;
    private TextView mContentInfo;
    private TextView mContentDetails;
    private File mUpdateFile;
    private Button mSystemUpdateToolbarAction;
    private PowerManager mPowerManager;
    private final UpdateVerifier mPackageVerifier = new UpdateVerifier();
    private final UpdateEngine mUpdateEngine = new UpdateEngine();

    private final CarUpdateEngineCallback mCarUpdateEngineCallback = new CarUpdateEngineCallback();

    /** Create a {@link DeviceListFragment}. */
    public static UpdateLayoutFragment getInstance(File file) {
        UpdateLayoutFragment fragment = new UpdateLayoutFragment();
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_UPDATE_FILE, file.getAbsolutePath());
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUpdateFile = new File(getArguments().getString(EXTRA_UPDATE_FILE));
        mPowerManager = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.system_update_auto_content, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        mContentTitle = view.findViewById(R.id.system_update_auto_content_title);
        mContentInfo = view.findViewById(R.id.system_update_auto_content_info);
        mContentDetails = view.findViewById(R.id.system_update_auto_content_details);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        AppCompatActivity activity = (AppCompatActivity) getActivity();

        ActionBar actionBar = activity.getSupportActionBar();
        actionBar.setCustomView(R.layout.action_bar_with_button);
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        activity.findViewById(R.id.action_bar_icon_container)
                .setOnClickListener(v -> activity.onBackPressed());

        mProgressBar = (ProgressBar) activity.findViewById(R.id.progress_bar);

        mSystemUpdateToolbarAction = activity.findViewById(R.id.system_update_auto_toolbar_action);
        mProgressBar.setIndeterminate(true);
        mProgressBar.setVisibility(View.VISIBLE);
        showStatus(R.string.verify_in_progress);

        mPackageVerifier.execute(mUpdateFile);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mPackageVerifier != null) {
            mPackageVerifier.cancel(true);
        }
    }

    /** Update the status information. */
    private void showStatus(@StringRes int status) {
        mContentTitle.setText(status);
    }

    /** Show the install now button. */
    private void showInstallNow(UpdateParser.ParsedUpdate update) {
        mContentTitle.setText(R.string.install_ready);
        mContentInfo.append(getString(R.string.update_file_name, mUpdateFile.getName()));
        mContentInfo.append(System.getProperty("line.separator"));
        mContentInfo.append(getString(R.string.update_file_size));
        mContentInfo.append(Formatter.formatFileSize(getContext(), mUpdateFile.length()));
        mContentDetails.setText(null);
        mSystemUpdateToolbarAction.setOnClickListener(v -> installUpdate(update));
        mSystemUpdateToolbarAction.setText(R.string.install_now);
        mSystemUpdateToolbarAction.setVisibility(View.VISIBLE);
    }

    /** Reboot the system. */
    private void rebootNow() {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Log.i(TAG, "Rebooting Now.");
        }
        mPowerManager.reboot(REBOOT_REASON);
    }

    /** Attempt to install the update that is copied to the device. */
    private void installUpdate(UpdateParser.ParsedUpdate parsedUpdate) {
        mProgressBar.setIndeterminate(false);
        mProgressBar.setVisibility(View.VISIBLE);
        mProgressBar.setMax(PERCENT_MAX);
        mSystemUpdateToolbarAction.setVisibility(View.GONE);
        showStatus(R.string.install_in_progress);

        mUpdateEngine.bind(mCarUpdateEngineCallback,
                new Handler(getContext().getMainLooper()));
        mUpdateEngine.applyPayload(
                parsedUpdate.mUrl, parsedUpdate.mOffset, parsedUpdate.mSize, parsedUpdate.mProps);

    }

    /** Attempt to verify the update and extract information needed for installation. */
    private class UpdateVerifier extends AsyncTask<File, Void, UpdateParser.ParsedUpdate> {

        @Override
        protected UpdateParser.ParsedUpdate doInBackground(File... files) {
            Preconditions.checkArgument(files.length > 0, "No file specified");
            File file = files[0];
            try {
                return UpdateParser.parse(file);
            } catch (IOException e) {
                Log.e(TAG, String.format("For file %s", file), e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(UpdateParser.ParsedUpdate result) {
            mProgressBar.setVisibility(View.GONE);
            if (result == null) {
                showStatus(R.string.verify_failure);
                return;
            }
            if (!result.isValid()) {
                showStatus(R.string.verify_failure);
                Log.e(TAG, String.format("Failed verification %s", result));
                return;
            }
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, result.toString());
            }

            showInstallNow(result);
        }
    }

    /** Handles events from the UpdateEngine. */
    public class CarUpdateEngineCallback extends UpdateEngineCallback {

        @Override
        public void onStatusUpdate(int status, float percent) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, String.format("onStatusUpdate %d, Percent %.2f", status, percent));
            }
            switch (status) {
                case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT:
                    rebootNow();
                    break;
                case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                    mProgressBar.setProgress((int) (percent * 100));
                    break;
                default:
                    // noop
            }
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            Log.w(TAG, String.format("onPayloadApplicationComplete %d", errorCode));
            showStatus(errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS
                    ? R.string.install_success
                    : R.string.install_failed);
            mProgressBar.setVisibility(View.GONE);
            mSystemUpdateToolbarAction.setVisibility(View.GONE);
        }
    }
}
