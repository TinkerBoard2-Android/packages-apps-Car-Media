/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.car.media;

import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.car.media.browse.BrowseAdapter;
import com.android.car.media.browse.ContentForwardStrategy;
import com.android.car.media.common.GridSpacingItemDecoration;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.MediaSource;
import com.android.car.media.common.PlaybackControls;
import com.android.car.media.common.PlaybackModel;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

import androidx.car.widget.PagedListView;

/**
 * A {@link Fragment} that implements both the playback and the content forward browsing experience.
 * It observes a {@link PlaybackModel} and updates its information depending on the currently
 * playing media source through the {@link android.media.session.MediaSession} API.
 */
public class PlaybackFragment extends Fragment implements BrowseAdapter.Observer {
    private static final String TAG = "PlaybackFragment";
    private static final DateFormat TIME_FORMAT = new SimpleDateFormat("m:ss", Locale.US);

    private PlaybackModel mModel;
    private CrossfadeImageView mAlbumBackground;
    private PlaybackControls mPlaybackControls;
    private ImageView mAlbumArt;
    private TextView mTitle;
    private TextView mTime;
    private TextView mSubtitle;
    private SeekBar mSeekbar;
    private PagedListView mBrowseList;
    private int mBackgroundRawImageSize;
    private float mBackgroundBlurRadius;
    private float mBackgroundBlurScale;
    private MediaSource mMediaSource;
    private BrowseAdapter mBrowseAdapter;
    private ViewGroup mMetadataContainer;
    private MediaItemMetadata mCurrentMetadata;
    private PlaybackModel.PlaybackObserver mPlaybackObserver = new PlaybackModel.PlaybackObserver() {
        @Override
        public void onPlaybackStateChanged() {
            updateState();
        }

        @Override
        public void onSourceChanged() {
            updateState();
            updateMetadata();
            updateAccentColor();
            updateBrowse();
        }

        @Override
        public void onMetadataChanged() {
            updateMetadata();
        }
    };
    private MediaSource.Observer mMediaSourceObserver = new MediaSource.Observer() {
        @Override
        protected void onBrowseConnected(boolean success) {
            PlaybackFragment.this.onBrowseConnected(success);
        }

        @Override
        protected void onBrowseDisconnected() {
            PlaybackFragment.this.onBrowseDisconnected();
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, final ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_playback, container, false);
        mModel = new PlaybackModel(getContext());
        mAlbumBackground = view.findViewById(R.id.album_background);
        mPlaybackControls = view.findViewById(R.id.playback_controls);
        mPlaybackControls.setModel(mModel);
        ViewGroup playbackContainer = view.findViewById(R.id.playback_container);
        mPlaybackControls.setAnimationViewGroup(playbackContainer);
        mAlbumArt = view.findViewById(R.id.album_art);
        mTitle = view.findViewById(R.id.title);
        mSubtitle = view.findViewById(R.id.subtitle);
        mSeekbar = view.findViewById(R.id.seek_bar);
        mTime = view.findViewById(R.id.time);
        mBrowseList = view.findViewById(R.id.browse_list);
        mMetadataContainer = view.findViewById(R.id.metadata_container);
        GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), 4);
        RecyclerView recyclerView = mBrowseList.getRecyclerView();
        recyclerView.setLayoutManager(gridLayoutManager);
        recyclerView.setVerticalFadingEdgeEnabled(true);
        recyclerView.setFadingEdgeLength(getResources()
                .getDimensionPixelSize(R.dimen.car_padding_4));
        recyclerView.addItemDecoration(new GridSpacingItemDecoration(getResources()
                .getDimensionPixelSize(R.dimen.car_padding_4)));
        TypedValue outValue = new TypedValue();
        getResources().getValue(R.dimen.playback_background_blur_radius, outValue, true);
        mBackgroundBlurRadius = outValue.getFloat();
        getResources().getValue(R.dimen.playback_background_blur_scale, outValue, true);
        mBackgroundBlurScale = outValue.getFloat();
        mBackgroundRawImageSize = getContext().getResources().getDimensionPixelSize(
                R.dimen.playback_background_raw_image_size);
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        mModel.registerObserver(mPlaybackObserver);
        if (mMediaSource != null) {
            mMediaSource.subscribe(mMediaSourceObserver);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mModel.unregisterObserver(mPlaybackObserver);
        if (mMediaSource != null) {
            mMediaSource.unsubscribe(mMediaSourceObserver);
        }
        if (mBrowseAdapter != null) {
            mBrowseAdapter.stop();
        }
    }

    private void updateState() {
        updateProgress();

        if (mModel.isPlaying()) {
            mSeekbar.post(mSeekBarRunnable);
        } else {
            mSeekbar.removeCallbacks(mSeekBarRunnable);
        }
        mBrowseList.setVisibility(mModel.hasQueue() ? View.VISIBLE : View.GONE);

        if (mBrowseAdapter != null) {
            mBrowseAdapter.setQueue(mModel.getQueue(), mModel.getQueueTitle());
        }
    }

    private void updateMetadata() {
        MediaItemMetadata metadata = mModel.getMetadata();
        if (Objects.equals(mCurrentMetadata, metadata)) {
            return;
        }
        mCurrentMetadata = metadata;
        mTitle.setText(metadata != null ? metadata.getTitle() : null);
        mSubtitle.setText(metadata != null ? metadata.getSubtitle() : null);
        MediaItemMetadata.updateImageView(getContext(), metadata, mAlbumArt, 0);
        if (metadata != null) {
            metadata.getAlbumArt(getContext(),
                    mBackgroundRawImageSize,
                    mBackgroundRawImageSize,
                    false)
                    .thenAccept(this::setBackgroundImage);
        } else {
            mAlbumBackground.setImageBitmap(null, true);
        }
    }

    private void setBackgroundImage(Bitmap bitmap) {
        // TODO(b/77551865): Implement image blurring once the following issue is solved:
        // b/77551557
        // bitmap = ImageUtils.blur(getContext(), bitmap, mBackgroundBlurScale,
        //        mBackgroundBlurRadius);
        mAlbumBackground.setImageBitmap(bitmap, true);
    }

    private void updateAccentColor() {
        int defaultColor = getResources().getColor(android.R.color.background_dark, null);
        MediaSource mediaSource = mModel.getMediaSource();
        int color = mediaSource == null ? defaultColor : mediaSource.getAccentColor(defaultColor);
        mSeekbar.getProgressDrawable().setColorFilter(color, PorterDuff.Mode.SRC_IN);
    }

    private static final long SEEK_BAR_UPDATE_TIME_INTERVAL_MS = 500;

    private final Runnable mSeekBarRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mModel.isPlaying()) {
                return;
            }
            updateProgress();
            mSeekbar.postDelayed(this, SEEK_BAR_UPDATE_TIME_INTERVAL_MS);

        }
    };

    private void updateProgress() {
        long maxProgress = mModel.getMaxProgress();
        int visibility = maxProgress > 0 ? View.VISIBLE : View.INVISIBLE;
        String time = String.format("%s / %s",
                TIME_FORMAT.format(new Date(mModel.getProgress())),
                TIME_FORMAT.format(new Date(maxProgress)));
        mTime.setVisibility(visibility);
        mTime.setText(time);
        mSeekbar.setVisibility(visibility);
        mSeekbar.setMax((int) mModel.getMaxProgress());
        mSeekbar.setProgress((int) mModel.getProgress());
    }

    /**
     * Collapses the playback controls.
     */
    public void closeOverflowMenu() {
        mPlaybackControls.close();
    }

    /**
     * Updates the information on the media source being browsed.
     */
    public void updateBrowse() {
        MediaSource newSource = getCurrentMediaSource();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Updating browse: new source "
                    + (newSource != null ? newSource.getPackageName() : null)
                    + ", current source: "
                    + (mMediaSource != null ? mMediaSource.getPackageName() : null)
                    + ", currently playing: "
                    + (mModel.getMediaSource() != null
                        ? mModel.getMediaSource().getPackageName() : null));
        }

        // Visibility might change both because the browsed source changed or because the
        // source being played changed.
        if (Objects.equals(newSource, mModel.getMediaSource())) {
            // We are playing: show everything
            mAlbumBackground.setVisibility(View.VISIBLE);
            mPlaybackControls.setVisibility(View.VISIBLE);
            mAlbumArt.setVisibility(View.VISIBLE);
            mTitle.setVisibility(View.VISIBLE);
            mTime.setVisibility(View.VISIBLE);
            mSubtitle.setVisibility(View.VISIBLE);
            mSeekbar.setVisibility(View.VISIBLE);
            mMetadataContainer.setVisibility(View.VISIBLE);
        } else {
            // Hide playback
            mAlbumBackground.setVisibility(View.INVISIBLE);
            mPlaybackControls.setVisibility(View.GONE);
            mAlbumArt.setVisibility(View.VISIBLE);
            mTitle.setVisibility(View.GONE);
            mTime.setVisibility(View.GONE);
            mSubtitle.setVisibility(View.GONE);
            mSeekbar.setVisibility(View.GONE);
            mMetadataContainer.setVisibility(View.GONE);
        }

        if (Objects.equals(mMediaSource, newSource)) {
            // Browse information hasn't changed. Nothing to do.
            return;
        }

        if (mMediaSource != null) {
            mMediaSource.unsubscribe(mMediaSourceObserver);
        }
        mMediaSource = newSource;
        if (newSource == null) return;
        mMediaSource.subscribe(mMediaSourceObserver);

        MediaManager.getInstance(getContext())
                .setMediaClientComponent(mMediaSource.getBrowseServiceComponentName());
    }

    private MediaSource getCurrentMediaSource() {
        if (getActivity() == null || getActivity().getIntent() == null
                || !getActivity().getIntent().hasExtra(
                MediaManager.KEY_MEDIA_PACKAGE)) {
            return mModel.getMediaSource();
        } else {
            String packageName = getActivity().getIntent().getStringExtra(
                    MediaManager.KEY_MEDIA_PACKAGE);
            return new MediaSource(getContext(), packageName);
        }
    }

    private void onBrowseConnected(boolean success) {
        if (mBrowseAdapter != null) {
            mBrowseAdapter.stop();
            mBrowseAdapter = null;
        }
        if (!success) {
            mBrowseList.setVisibility(View.GONE);
            // TODO(b/77647430) implement intermediate states.
            return;
        } else {
            mBrowseList.setVisibility(View.VISIBLE);
        }
        mBrowseAdapter = new BrowseAdapter(getContext(), mMediaSource, null,
                ContentForwardStrategy.DEFAULT_STRATEGY);
        mBrowseList.setAdapter(mBrowseAdapter);
        mBrowseAdapter.registerObserver(this);
        mBrowseAdapter.start();
    }

    private void onBrowseDisconnected() {
        mBrowseAdapter.stop();
    }

    @Override
    public void onDirty() {
        mBrowseAdapter.update();
        if (mBrowseAdapter.getItemCount() > 0) {
            mBrowseList.setVisibility(View.VISIBLE);
        } else {
            mBrowseList.setVisibility(View.GONE);
            // TODO(b/77647430) implement intermediate states.
        }
    }

    @Override
    public void onPlayableItemClicked(MediaItemMetadata item) {
        mModel.onStop();
        mMediaSource.getPlaybackModel().onPlayItem(item.getId());
        getActivity().setIntent(null);
    }

    @Override
    public void onBrowseableItemClicked(MediaItemMetadata item) {
        // TODO(b/77527398): Drill down in the navigation.
    }

    @Override
    public void onMoreButtonClicked(MediaItemMetadata item) {
        // TODO(b/77527398): Drill down in the navigation
    }

    @Override
    public void onQueueTitleClicked() {
        // TODO(b/77527398): Show full queue
    }

    @Override
    public void onQueueItemClicked(MediaItemMetadata item) {
        mModel.onSkipToQueueItem(item.getQueueId());
    }
}