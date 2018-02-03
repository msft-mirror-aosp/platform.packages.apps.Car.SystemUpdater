/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import android.util.Log;

import androidx.car.widget.PagedListView;
import androidx.car.widget.ListItem;
import androidx.car.widget.ListItemAdapter;
import androidx.car.widget.ListItemProvider;
import androidx.car.widget.TextListItem;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;

/**
* Display a list of files and directories.
*/
public class DeviceListFragment extends Fragment {

    private static final String TAG = "DeviceListFragment";
    private static final String UPDATE_FILE_SUFFIX = ".zip";

    private final Stack<File> mFileStack = new Stack<>();
    private StorageManager mStorageManager;
    private SystemUpdater mSystemUpdater;
    private List<File> mListItems;
    private ListItemAdapter mAdapter;
    private FileItemProvider mItemProvider;

    private final StorageEventListener mListener = new StorageEventListener() {
        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, String.format(
                        "onVolumeMetadataChanged %d %d %s", oldState, newState, vol.toString()));
            }
            showMountedVolumes();
        }
    };

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mSystemUpdater = (SystemUpdater) context;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();
        mItemProvider = new FileItemProvider(context);

        mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (mStorageManager == null) {
            if (Log.isLoggable(TAG, Log.WARN)) {
                Log.w(TAG, "Failed to get StorageManager");
            }
            Toast.makeText(context, R.string.cannot_access_storage, Toast.LENGTH_LONG).show();
            return;
        }
        showMountedVolumes();
    }

   @Override
   public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
           Bundle savedInstanceState) {
        mAdapter = new ListItemAdapter(getContext(), mItemProvider);
        return inflater.inflate(R.layout.folder_list, container, false);
   }

   @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        PagedListView folderListView = (PagedListView) view.findViewById(R.id.folder_list);
        folderListView.setMaxPages(PagedListView.ItemCap.UNLIMITED);
        folderListView.setAdapter(mAdapter);
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
                .setOnClickListener(v -> onBackPressed());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mStorageManager != null) {
            mStorageManager.registerListener(mListener);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mStorageManager != null) {
            mStorageManager.unregisterListener(mListener);
        }
    }

    /** Display the mounted volumes on this device. */
    private void showMountedVolumes() {
        if (mStorageManager == null) {
            return;
        }
        final List<VolumeInfo> vols = mStorageManager.getVolumes();
        ArrayList<File> volumes = new ArrayList<>(vols.size());
        for (VolumeInfo vol : vols) {
            File path = vol.getPathForUser(getActivity().getUserId());
            if (vol.getState() == VolumeInfo.STATE_MOUNTED && path != null) {
                volumes.add(path);
            }
        }
        setFileList(volumes);
    }

    /** Set the list of files shown on the screen. */
    private void setFileList(List<File> files) {
        mListItems = files;
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    /** Handle user selection of a file. */
    private void onFileSelected(File file) {
        if (isUpdateFile(file)) {
            mSystemUpdater.applyUpdate(file);
        } else if (file.isDirectory()) {
            showFolderContent(file);
            mFileStack.push(file);
        } else {
            Toast.makeText(getContext(), R.string.invalid_file_type, Toast.LENGTH_LONG).show();
        }
    }

    /** Handle user pressing the back button. */
    private void onBackPressed() {
        if (mFileStack.empty()) {
            getActivity().onBackPressed();
            return;
        }
        mFileStack.pop();
        if (!mFileStack.empty()) {
            // Show the list of files contained in the top of the stack.
            showFolderContent(mFileStack.peek());
        } else {
            // When the stack is empty, display the volumes and reset the title.
            showMountedVolumes();
        }
    }

    /** Display the content at the provided {@code location}. */
    private void showFolderContent(File folder) {
        if (!folder.isDirectory()) {
            // This should not happen.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Cannot show contents of a file.");
            }
            return;
        }

        // Retrieve the list of files and update the displayed list.
        new AsyncTask<File, Void, File[]>() {
            @Override
            protected File[] doInBackground(File... file) {
                return file[0].listFiles();
            }

            @Override
            protected void onPostExecute(File[] results) {
                super.onPostExecute(results);
                if (results == null) {
                    results = new File[0];
                }
                setFileList(Arrays.asList(results));
            }
        }.execute(folder);
    }

    /** A list item provider to display the list of files on this fragment. */
    private class FileItemProvider extends ListItemProvider {
        private final Context mContext;

        FileItemProvider(Context context) {
            mContext = context;
        }

        @Override
        public ListItem get(int position) {
            if (position < 0 || position >= mListItems.size()) {
                return null;
            }
            TextListItem item = new TextListItem(mContext);
            File file = mListItems.get(position);
            if (file != null) {
                item.setTitle(file.getAbsolutePath());
                item.setOnClickListener(v -> onFileSelected(file));
            } else {
                item.setTitle(getString(R.string.unknown_file));
            }
            return item;
        }

        @Override
        public int size() {
            return mListItems.size();
        }
    }

    /** Returns true if a file is considered to contain a system update. */
    private static boolean isUpdateFile(File file) {
        return file.getName().endsWith(UPDATE_FILE_SUFFIX);
    }

    /** Used to request installation of an update. */
    interface SystemUpdater {
        /** Attempt to apply an update to the device contained in the {@code file}. */
        void applyUpdate(File file);
    }
}
