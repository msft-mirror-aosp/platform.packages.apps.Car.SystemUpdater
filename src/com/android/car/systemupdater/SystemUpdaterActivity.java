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

import static com.android.car.systemupdater.UpdateLayoutFragment.EXTRA_RESUME_UPDATE;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;

import com.android.car.ui.core.CarUi;
import com.android.car.ui.toolbar.Toolbar;
import com.android.car.ui.toolbar.ToolbarController;

import java.io.File;

/**
 * Apply a system update using an ota package on internal or external storage.
 */
public class SystemUpdaterActivity extends AppCompatActivity
        implements DeviceListFragment.SystemUpdater {

    private static final String FRAGMENT_TAG = "FRAGMENT_TAG";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        ToolbarController toolbar = CarUi.requireToolbar(this);
        toolbar.setTitle(getString(R.string.title));
        toolbar.setState(Toolbar.State.SUBPAGE);

        if (savedInstanceState == null) {
            Bundle intentExtras = getIntent().getExtras();
            if (intentExtras != null && intentExtras.getBoolean(EXTRA_RESUME_UPDATE)) {
                UpdateLayoutFragment fragment = UpdateLayoutFragment.newResumedInstance();
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.device_container, fragment, FRAGMENT_TAG)
                        .commitNow();
            } else {
                DeviceListFragment fragment = new DeviceListFragment();
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.device_container, fragment, FRAGMENT_TAG)
                        .commitNow();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            UpFragment upFragment =
                    (UpFragment) getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG);
            if (!upFragment.goUp()) {
                onBackPressed();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void applyUpdate(File file) {
        UpdateLayoutFragment fragment = UpdateLayoutFragment.getInstance(file);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.device_container, fragment, FRAGMENT_TAG)
                .addToBackStack(null)
                .commit();
    }
}
