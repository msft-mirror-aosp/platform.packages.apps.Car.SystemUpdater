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

import static com.android.internal.util.Preconditions.checkNotNull;

import android.app.Fragment;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.StringRes;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.car.widget.PagedListView;
import androidx.car.widget.ListItem;
import androidx.car.widget.ListItemAdapter;
import androidx.car.widget.ListItemProvider;
import androidx.car.widget.TextListItem;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Stack;

/**
* Display a list of files and directories.
*/
public class DeviceListFragment extends Fragment {

    public static final String EXTRA_TITLE_ID = "extra_title_id";
    public static final String EXTRA_VOLUMES = "extra_volumes";

    private static final String TAG = "DeviceListFragment";
    private static final String UPDATE_FILE_SUFFIX = ".zip";

    private final Stack<File> mFileStack = new Stack<>();
    private SystemUpdaterActivity mActivity;
    private List<File> mVolumes;
    private List<File> mListItems;
    private ListItemAdapter mAdapter;
    private FileItemProvider mItemProvider;
    @StringRes
    private int mTitleId;

    /** Create a {@link DeviceListFragment}. */
    public static DeviceListFragment getInstance(ArrayList<String> volumes) {
        checkNotNull(volumes, "volumes cannot be null");
        DeviceListFragment fragment = new DeviceListFragment();
        Bundle bundle = new Bundle();
        bundle.putInt(EXTRA_TITLE_ID, R.string.title);
        bundle.putStringArrayList(EXTRA_VOLUMES, volumes);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        mActivity = (SystemUpdaterActivity) getActivity();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mItemProvider = new FileItemProvider(getContext());
        mTitleId = getArguments().getInt(EXTRA_TITLE_ID);
        List<String> initialFiles = getArguments().getStringArrayList(EXTRA_VOLUMES);
        mVolumes = new ArrayList<>(initialFiles.size());
        for (String path : initialFiles) {
            mVolumes.add(new File(path));
        }
        setFileList(mVolumes);
    }

   @Override
   public View onCreateView(LayoutInflater inflater, ViewGroup container,
           Bundle savedInstanceState) {
        mAdapter = new ListItemAdapter(getContext(), mItemProvider);
        return inflater.inflate(R.layout.folder_list, container, false);
   }

   @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        PagedListView folderListView = (PagedListView) view.findViewById(R.id.folder_list);
        folderListView.setMaxPages(PagedListView.ItemCap.UNLIMITED);
        folderListView.setAdapter(mAdapter);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        ActionBar actionBar = ((AppCompatActivity) mActivity).getSupportActionBar();
        actionBar.setCustomView(R.layout.action_bar_with_button);
        actionBar.setDisplayShowCustomEnabled(true);
        actionBar.setDisplayShowTitleEnabled(false);
        mActivity.findViewById(R.id.action_bar_icon_container)
                .setOnClickListener(v -> onBackPressed());
        TextView titleView = mActivity.findViewById(R.id.title);
        titleView.setText(mTitleId);
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
            mActivity.applyUpdate(file);
        } else if (file.isDirectory()) {
            showFolderContent(file);
            mFileStack.push(file);
        } else {
            Toast.makeText(mActivity, R.string.invalid_file_type, Toast.LENGTH_LONG).show();
        }
    }

    /** Handle user pressing the back button. */
    private void onBackPressed() {
        if (mFileStack.empty()) {
            mActivity.onBackPressed();
            return;
        }
        mFileStack.pop();
        if (!mFileStack.empty()) {
            // Show the list of files contained in the top of the stack.
            showFolderContent(mFileStack.peek());
        } else {
            // When the stack is empty, display the volumes and reset the title.
            setFileList(mVolumes);
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
}
