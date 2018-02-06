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

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RecoverySystem;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

/** Display update state and progress. */
public class UpdateLayoutFragment extends Fragment {
    private static final String TAG = "UpdateLayoutFragment";
    private static final int COPY_BUF_SIZE = 0x10000; // 64k
    private static final String EXTRA_UPDATE_FILE = "extra_update_file";
    private static final String UPDATE_FILE_NAME = "update.zip";

    private ProgressBar mProgressBar;
    private TextView mContentTitle;
    private TextView mContentInfo;
    private TextView mContentDetails;
    private File mUpdateFile;
    private Button mSystemUpdateToolbarAction;
    private PackageVerifier mPackageVerifier;
    private CopyFile mCopyFile;
    private InstallUpdate mInstallUpdate;

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

        mPackageVerifier = new PackageVerifier();
        mPackageVerifier.execute(mUpdateFile);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mCopyFile != null) {
            mCopyFile.cancel(true);
        }
        if (mInstallUpdate != null) {
            mInstallUpdate.cancel(true);
        }
        if (mPackageVerifier != null) {
            mPackageVerifier.cancel(true);
        }
    }

    private void showStatus(@StringRes int status) {
        mContentTitle.setText(status);
    }

    /** Show the install now button. */
    private void showInstallNow(File update) {
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

    /** Attempt to install the update that is copied to the device. */
    private void installUpdate(File update) {
        mInstallUpdate = new InstallUpdate();
        mInstallUpdate.execute(update);
    }

    /** Attempt to verify the package. */
    private class PackageVerifier extends AsyncTask<File, Void, File> {

        @Override
        public void onPreExecute() {
            mProgressBar.setIndeterminate(true);
            mProgressBar.setVisibility(View.VISIBLE);
            showStatus(R.string.verify_in_progress);
        }

        @Override
        protected File doInBackground(File... files) {
            File file = files[0];
            try {
                RecoverySystem.verifyPackage(file, null, null);
                return file;
            } catch (GeneralSecurityException | IOException e) {
                Log.e(TAG, String.format("While verifying package: %s", file), e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(File result) {
            mProgressBar.setVisibility(View.GONE);
            if (result == null) {
                showStatus(R.string.verify_failure);
                return;
            }

            mCopyFile = new CopyFile();
            mCopyFile.execute(result);
        }
    }

    /** Copy the update file to the data partition so it can be installed. */
    private class CopyFile extends AsyncTask<File, Integer, File> {
        private final File mCacheDir;

        CopyFile() {
            mCacheDir = getContext().getCacheDir();
        }

        @Override
        public void onPreExecute() {
            showStatus(R.string.copy_in_progress);
            mProgressBar.setIndeterminate(false);
            mProgressBar.setVisibility(View.VISIBLE);
            mProgressBar.setMax((int)(mUpdateFile.length() / COPY_BUF_SIZE));
        }

        @Override
        protected File doInBackground(File... files) {
            final File file = files[0];
            if (mCacheDir.getFreeSpace() < file.length()) {
                Log.e(TAG, "Not enough cache space!");
                return null;
            }
            final File dest = new File(mCacheDir, UPDATE_FILE_NAME);
            try {
                copy(file, dest);
                return dest;
            } catch (IOException e) {
                Log.e(TAG, "Error when copying file to cache", e);
                dest.delete();
                return null;
            }
        }

        @Override
        protected void onPostExecute(File result) {
            mProgressBar.setVisibility(View.GONE);
            if (result == null) {
                // Copy failed
                showStatus(R.string.copy_failure);
                return;
            }

            showInstallNow(result);
        }

        protected void onProgressUpdate(Integer... progress) {
            mProgressBar.incrementProgressBy(progress[0]);
        }

        /** Copy a file from {@code src} to {@code dest}. */
        private void copy(File src, File dst) throws IOException {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                final byte[] buf = new byte[COPY_BUF_SIZE];
                int len;
                int count = 0;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                    publishProgress(++count);
                }
            }
        }
    }

    /** Attempt to install the package. */
    private class InstallUpdate extends AsyncTask<File, Void, Boolean> {

        @Override
        public void onPreExecute() {
            mProgressBar.setIndeterminate(true);
            mProgressBar.setVisibility(View.VISIBLE);
            mSystemUpdateToolbarAction.setVisibility(View.GONE);
            showStatus(R.string.install_in_progress);
        }

        @Override
        protected Boolean doInBackground(File... files) {
            File file = files[0];
            try {
                RecoverySystem.installPackage(getContext(), file);
                return true;
            } catch (IOException e) {
                Log.e(TAG, "While installing the update package", e);
                return false;
            }
        }

        @Override
        protected void onPostExecute(Boolean result) {
            mProgressBar.setVisibility(View.GONE);
            mSystemUpdateToolbarAction.setVisibility(View.GONE);

            showStatus(result ? R.string.install_success : R.string.install_failed);
        }
    }
}
