/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.customize;

import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;

import dagger.Binds;
import dagger.Module;

/**
 * Dagger module for the Nothing-OS-style QS customizer.
 *
 * <p>The {@link NothingQSCustomizerAdapter} implements {@link TileStateListener} so it can
 * directly receive tile data from {@link TileQueryHelper}.  We bind it here so that when
 * {@link QSCustomizerController} injects a {@link NothingQSCustomizerAdapter} it gets the
 * same scoped instance that {@link TileQueryHelper} notifies.
 *
 * <p><b>Migration note:</b> If your existing module already binds {@code TileAdapter} as
 * {@code TileStateListener}, remove that binding and replace it with this module.
 * The old {@link TileAdapter} class can be kept for reference or deleted; it is no longer
 * used by the customizer UI.
 */
@Module
public abstract class QSCustomizeModule {

    /**
     * Bind {@link NothingQSCustomizerAdapter} as the {@link TileStateListener} that
     * {@link TileQueryHelper} will notify when tile metadata is ready.
     */
    @Binds
    abstract TileStateListener bindTileStateListener(NothingQSCustomizerAdapter impl);
}
