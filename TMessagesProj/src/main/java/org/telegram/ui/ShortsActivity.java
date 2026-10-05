package org.telegram.ui;

import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ShortsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int PAGE_LIMIT = 50;

    private RecyclerView listView;
    private LinearLayoutManager layoutManager;
    private ShortsAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<MessageObject> videos = new ArrayList<>();
    private final Map<String, Boolean> requestedFiles = new HashMap<>();
    private int requestId = -1;
    private int activePosition = RecyclerView.NO_POSITION;
    private boolean destroyed;

    public ShortsActivity() {
        super();
        setHasOwnBackground(true);
    }

    @Nullable
    @Override
    public View createView(@NonNull android.content.Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        listView = new RecyclerView(context);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        listView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listView.setItemAnimator(null);
        listView.setHasFixedSize(true);

        adapter = new ShortsAdapter();
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private int lastState = RecyclerView.SCROLL_STATE_IDLE;

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                lastState = newState;
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    activateSnappedItem();
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    pauseVisiblePlayers();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (lastState == RecyclerView.SCROLL_STATE_IDLE) {
                    activateSnappedItem();
                }
            }
        });

        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView title = new TextView(context);
        title.setText("Shorts");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        root.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, AndroidUtilities.statusBarHeight + 4, 0, 0));

        setFragmentView(root);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);

        loadVideos();
        return root;
    }

    private void loadVideos() {
        if (requestId != -1 || destroyed) {
            return;
        }

        TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.flags |= 1;
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterVideo();
        req.limit = PAGE_LIMIT;
        req.offset_id = 0;
        req.offset_rate = 0;
        req.offset_peer = new TLRPC.TL_inputPeerEmpty();

        requestId = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
            requestId = -1;
            if (destroyed || error != null || !(response instanceof TLRPC.messages_Messages)) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (!destroyed && videos.isEmpty()) {
                        Toast.makeText(getParentActivity(), "Unable to load Shorts from Telegram", Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
            LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
            LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
            for (TLRPC.User user : result.users) {
                users.put(user.id, user);
            }
            for (TLRPC.Chat chat : result.chats) {
                chats.put(chat.id, chat);
            }

            ArrayList<MessageObject> loaded = new ArrayList<>();
            for (TLRPC.Message message : result.messages) {
                if (message == null || message.media == null) {
                    continue;
                }
                MessageObject object = new MessageObject(currentAccount, message, users, chats, false, true);
                TLRPC.Document document = getDocument(object);
                if (document != null && MessageObject.isVideoDocument(document)) {
                    loaded.add(object);
                }
            }

            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) {
                    return;
                }
                videos.clear();
                videos.addAll(loaded);
                adapter.notifyDataSetChanged();
                if (!videos.isEmpty()) {
                    activePosition = 0;
                    listView.post(() -> {
                        activatePosition(0);
                        preloadAround(0);
                    });
                }
            });
        });
    }

    private static TLRPC.Document getDocument(MessageObject object) {
        if (object == null || object.messageOwner == null || object.messageOwner.media == null) {
            return null;
        }
        if (object.messageOwner.media instanceof TLRPC.TL_messageMediaDocument) {
            return ((TLRPC.TL_messageMediaDocument) object.messageOwner.media).document;
        }
        return null;
    }

    private void activateSnappedItem() {
        if (listView == null || videos.isEmpty()) {
            return;
        }
        int position = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION) {
            position = layoutManager.findFirstVisibleItemPosition();
        }
        if (position != RecyclerView.NO_POSITION) {
            activatePosition(position);
        }
    }

    private void activatePosition(int position) {
        if (position < 0 || position >= videos.size()) {
            return;
        }
        activePosition = position;

        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            RecyclerView.ViewHolder holder = listView.getChildViewHolder(child);
            if (holder instanceof ShortsViewHolder) {
                ShortsViewHolder videoHolder = (ShortsViewHolder) holder;
                if (videoHolder.getBindingAdapterPosition() == position) {
                    videoHolder.play();
                } else {
                    videoHolder.pause();
                }
            }
        }
        preloadAround(position);
    }

    private void pauseVisiblePlayers() {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            RecyclerView.ViewHolder holder = listView.getChildViewHolder(child);
            if (holder instanceof ShortsViewHolder) {
                ((ShortsViewHolder) holder).pause();
            }
        }
    }

    private void preloadAround(int position) {
        for (int i = Math.max(0, position - 1); i <= Math.min(videos.size() - 1, position + 2); i++) {
            ensureFileAvailable(videos.get(i));
        }
    }

    private void ensureFileAvailable(MessageObject object) {
        TLRPC.Document document = getDocument(object);
        if (document == null) {
            return;
        }
        File file = FileLoader.getInstance(currentAccount).getPathToAttach(document);
        if (file != null && file.exists()) {
            return;
        }

        String name = FileLoader.getAttachFileName(document);
        if (requestedFiles.put(name, true) == null) {
            FileLoader.getInstance(currentAccount).loadFile(document, object, FileLoader.PRIORITY_HIGH, 0);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (destroyed || id != NotificationCenter.fileLoaded || listView == null) {
            return;
        }
        String path = args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
        if (TextUtils.isEmpty(path)) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            RecyclerView.ViewHolder holder = listView.getChildViewHolder(child);
            if (holder instanceof ShortsViewHolder) {
                ((ShortsViewHolder) holder).onFileLoaded(path);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!destroyed && activePosition != RecyclerView.NO_POSITION) {
            activatePosition(activePosition);
        }
    }

    @Override
    public void onPause() {
        pauseVisiblePlayers();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (requestId != -1) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(requestId, true);
            requestId = -1;
        }
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        pauseVisiblePlayers();
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                RecyclerView.ViewHolder holder = listView.getChildViewHolder(child);
                if (holder instanceof ShortsViewHolder) {
                    ((ShortsViewHolder) holder).release();
                }
            }
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.scrollToPosition(0);
            listView.post(() -> activatePosition(0));
        }
    }

    private class ShortsAdapter extends RecyclerView.Adapter<ShortsViewHolder> {
        @NonNull
        @Override
        public ShortsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ShortsViewHolder(new ShortsCell(parent.getContext()));
        }

        @Override
        public void onBindViewHolder(@NonNull ShortsViewHolder holder, int position) {
            holder.bind(videos.get(position));
        }

        @Override
        public void onViewRecycled(@NonNull ShortsViewHolder holder) {
            holder.release();
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return videos.size();
        }
    }

    private class ShortsViewHolder extends RecyclerView.ViewHolder {
        private final ShortsCell cell;

        ShortsViewHolder(@NonNull ShortsCell itemView) {
            super(itemView);
            cell = itemView;
        }

        void bind(MessageObject message) {
            cell.bind(message);
        }

        void play() {
            cell.play();
        }

        void pause() {
            cell.pause();
        }

        void onFileLoaded(String path) {
            cell.onFileLoaded(path);
        }

        void release() {
            cell.release();
        }
    }

    private class ShortsCell extends FrameLayout {
        private final TextureView textureView;
        private final ProgressBar buffering;
        private final ProgressBar progress;
        private final TextView creator;
        private final TextView caption;
        private final TextView mute;
        private final TextView like;
        private final VideoPlayer player;
        private MessageObject message;
        private TLRPC.Document document;
        private String expectedFile;
        private boolean prepared;
        private boolean liked;
        private boolean muted;

        private final Runnable progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (message != null) {
                    long duration = player.getDuration();
                    long position = player.getCurrentPosition();
                    if (duration > 0) {
                        progress.setProgress((int) Math.min(1000, Math.max(0, position * 1000L / duration)));
                    }
                }
                if (isAttachedToWindow() && !destroyed) {
                    postDelayed(this, 200);
                }
            }
        };

        ShortsCell(@NonNull android.content.Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            textureView = new TextureView(context);
            textureView.setBackgroundColor(Color.BLACK);
            addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            buffering = new ProgressBar(context);
            addView(buffering, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

            FrameLayout bottomShade = new FrameLayout(context);
            bottomShade.setBackgroundColor(0x55000000);
            addView(bottomShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 230, Gravity.BOTTOM));

            creator = new TextView(context);
            creator.setTextColor(Color.WHITE);
            creator.setTextSize(16);
            creator.setTypeface(AndroidUtilities.bold());
            creator.setMaxLines(1);
            creator.setEllipsize(TextUtils.TruncateAt.END);
            bottomShade.addView(creator, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 32, Gravity.BOTTOM, 16, 0, 92, 142));

            caption = new TextView(context);
            caption.setTextColor(Color.WHITE);
            caption.setTextSize(14);
            caption.setMaxLines(3);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            bottomShade.addView(caption, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 72, Gravity.BOTTOM, 16, 0, 92, 74));

            like = actionButton(context, "♡", "Like");
            like.setOnClickListener(v -> {
                liked = !liked;
                like.setText(liked ? "♥" : "♡");
            });
            bottomShade.addView(like, LayoutHelper.createFrame(58, 58, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 10, 126));

            TextView comments = actionButton(context, "◌", "Comments");
            bottomShade.addView(comments, LayoutHelper.createFrame(58, 58, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 10, 70));

            TextView share = actionButton(context, "↗", "Share");
            share.setOnClickListener(v -> shareCurrent());
            bottomShade.addView(share, LayoutHelper.createFrame(58, 58, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 10, 14));

            mute = actionButton(context, "◉", "Mute");
            mute.setOnClickListener(v -> {
                muted = !muted;
                player.setMute(muted);
                mute.setText(muted ? "⊘" : "◉");
            });
            addView(mute, LayoutHelper.createFrame(58, 58, Gravity.TOP | Gravity.RIGHT, 0, AndroidUtilities.statusBarHeight + 46, 8, 0));

            progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            addView(progress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 3, Gravity.BOTTOM));

            player = new VideoPlayer(true, false);
            player.setTextureView(textureView);
            player.setLooping(true);
            player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    buffering.setVisibility(playbackState == 2 ? VISIBLE : GONE);
                }

                @Override
                public void onError(VideoPlayer player, Exception e) {
                    buffering.setVisibility(GONE);
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int rotation, float ratio) {
                    applyCrop(width, height, ratio, rotation);
                }

                @Override
                public void onRenderedFirstFrame() {
                    buffering.setVisibility(GONE);
                }
            });
        }

        private TextView actionButton(android.content.Context context, String glyph, String description) {
            TextView view = new TextView(context);
            view.setText(glyph);
            view.setTextColor(Color.WHITE);
            view.setTextSize(30);
            view.setGravity(Gravity.CENTER);
            view.setContentDescription(description);
            return view;
        }

        void bind(MessageObject value) {
            if (message != value) {
                player.pause();
                if (prepared) {
                    player.releasePlayer(false);
                }
                prepared = false;
                liked = false;
                muted = false;
                like.setText("♡");
                mute.setText("◉");
            }

            message = value;
            document = getDocument(value);
            expectedFile = document != null ? FileLoader.getAttachFileName(document) : null;
            creator.setText(getCreator(value));
            CharSequence text = value != null && value.messageOwner != null ? value.messageOwner.message : "";
            caption.setText(TextUtils.isEmpty(text) ? "" : text);

            File file = document != null ? FileLoader.getInstance(currentAccount).getPathToAttach(document) : null;
            if (file != null && file.exists()) {
                prepare(file);
            } else {
                buffering.setVisibility(VISIBLE);
                ensureFileAvailable(value);
            }
        }

        private void prepare(File file) {
            if (prepared || document == null || file == null || !file.exists()) {
                return;
            }
            prepared = true;
            player.setTextureView(textureView);
            player.preparePlayer(Uri.fromFile(file), "other");
            if (activePosition == getBindingAdapterPosition()) {
                player.play();
                handler.removeCallbacks(progressRunnable);
                handler.post(progressRunnable);
            }
        }

        void onFileLoaded(String path) {
            if (expectedFile == null || document == null || !expectedFile.equals(path)) {
                return;
            }
            prepare(FileLoader.getInstance(currentAccount).getPathToAttach(document));
        }

        void play() {
            if (!prepared) {
                if (message != null) {
                    ensureFileAvailable(message);
                }
                return;
            }
            player.play();
            handler.removeCallbacks(progressRunnable);
            handler.post(progressRunnable);
        }

        void pause() {
            player.pause();
            handler.removeCallbacks(progressRunnable);
        }

        void release() {
            handler.removeCallbacks(progressRunnable);
            player.pause();
            if (prepared) {
                player.releasePlayer(false);
            }
            prepared = false;
        }

        private void applyCrop(int width, int height, float ratio, int rotation) {
            if (width <= 0 || height <= 0 || textureView.getWidth() <= 0 || textureView.getHeight() <= 0) {
                return;
            }
            float videoWidth = width * (ratio <= 0 ? 1f : ratio);
            float videoHeight = height;
            if (rotation == 90 || rotation == 270) {
                float temp = videoWidth;
                videoWidth = videoHeight;
                videoHeight = temp;
            }

            float scale = Math.max(textureView.getWidth() / videoWidth, textureView.getHeight() / videoHeight);
            float scaledWidth = videoWidth * scale;
            float scaledHeight = videoHeight * scale;
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate((textureView.getWidth() - scaledWidth) / 2f, (textureView.getHeight() - scaledHeight) / 2f);
            textureView.setTransform(matrix);
        }

        private void shareCurrent() {
            if (message == null) {
                return;
            }
            long dialogId = message.getDialogId();
            String username = null;
            if (dialogId < 0) {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                if (chat != null) {
                    username = chat.username;
                }
            }
            String text = "Watch this video";
            if (!TextUtils.isEmpty(username)) {
                text += "\nhttps://t.me/" + username + "/" + message.getId();
            }
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_TEXT, text);
            getContext().startActivity(android.content.Intent.createChooser(intent, "Share Shorts"));
        }

        private String getCreator(MessageObject value) {
            if (value == null) {
                return "";
            }
            long dialogId = value.getDialogId();
            if (dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                return user != null ? UserObject.getUserName(user) : "";
            }
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            return chat != null ? chat.title : "";
        }
    }
}
