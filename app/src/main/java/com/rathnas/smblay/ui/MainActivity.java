package com.rathnas.smblay.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import android.media.MediaPlayer;

import com.rathnas.smblay.R;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MainActivity extends AppCompatActivity {
    private TextView mediaInfo;
    private TextView textHour;
    private TextView textMinute;
    private TextView textSecond;
    private Handler handler;
    private Runnable timeUpdater;
    private SharedPreferences securePreferences;
    private List<String> mediaFiles = new ArrayList<>();
    private int currentFileIndex = 0;
    ExecutorService executor = Executors.newFixedThreadPool(5);
    private MediaPlayer mediaPlayer;
    MediaMetadataRetriever mediaMetadataRetriever;

    private static final String USERNAME = "change_your_username";
    private static final String PASSWORD = "change_your_password";
    private static final String SERVER = "192.168.10.111";
    private static final String PORT = "445";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        securePreferences = getSharedPreferences("secure_settings", Context.MODE_PRIVATE);

        mediaInfo = findViewById(R.id.mediaInfo);
        textHour = findViewById(R.id.textHour);
        textMinute = findViewById(R.id.textMinute);
        textSecond = findViewById(R.id.textSecond);
        mediaPlayer = new MediaPlayer();

        initClock();

        initSmb(() -> {
            Collections.shuffle(mediaFiles);
            setMediaStatusToLoading(0);
            executor.execute(this::playNextMedia);
        });
    }

    private void initClock() {
        handler = new Handler();
        handler.post(new Runnable() {
            @Override
            public void run() {
                Date now = new Date();
                SimpleDateFormat hourFormat = new SimpleDateFormat("HH", Locale.getDefault());
                SimpleDateFormat minuteFormat = new SimpleDateFormat("mm", Locale.getDefault());
                SimpleDateFormat secondFormat = new SimpleDateFormat("ss", Locale.getDefault());
                textHour.setText(hourFormat.format(now));
                textMinute.setText(minuteFormat.format(now));
                textSecond.setText(secondFormat.format(now));

                if (mediaMetadataRetriever != null) {
                    String mediaStatus = "[" + currentFileIndex + "/" + mediaFiles.size() + "] " +
                            cleanup(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)) + " - " +
                            cleanup(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)) + " - " +
                            cleanup(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)) + " - " +
                            formatTime(mediaPlayer.getCurrentPosition()) + "/" + formatTime(mediaPlayer.getDuration());
                    mediaInfo.setText(mediaStatus);
                }
                handler.postDelayed(this, 1000);
            }
        });
    }

    /**
     * Formats milliseconds to HH:MM:SS or MM:SS
     */
    private String formatTime(int inputMillis) {
        int seconds = inputMillis / 1000;
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        seconds = seconds % 60;
        return hours > 0 ? String.format("%02d:%02d:%02d", hours, minutes, seconds) : String.format("%02d:%02d", minutes, seconds);
    }

    /**
     * Strips off masstamilan.com from s and return. case insensitive
     *
     * @param input
     * @return
     */
    private String cleanup(String input) {
        if (input == null) {
            return "";
        }

        String cleaned = input
                // Match mass?tamilan with optional space/special chars between
                .replaceAll("(?i)mass[\\s._-]*tamilan", "")
                // Match common domain patterns
                .replaceAll("(?i)\\.[a-z]+(\\.[a-z]+)?", "")
                // Remove any leftover special characters at the ends
                .replaceAll("^[\\s._-]+|[\\s._-]+$", "")
                .trim();

        return cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned;
    }

    private void initSmb(Runnable onComplete) {
        try {
            String server = securePreferences.getString("server_name", SERVER);
            String port = securePreferences.getString("server_port", PORT);
            String username = securePreferences.getString("username", USERNAME);
            String password = securePreferences.getString("password", PASSWORD);

            String path = securePreferences.getString("path", "/share/public/music/");
            mediaFiles.clear();
            executor.execute(() -> {
                scanDirectory(path, server, port, username, password);
                if (onComplete != null) {
                    System.out.println("T0, onComplete with: " + mediaFiles.size());
                    new Handler(Looper.getMainLooper()).post(onComplete);
                }
            });
        } catch (Exception e) {
            log.error("Error connecting to SMB share", e);
        }
    }

    private void playNextMedia() {
        try {
            String username = securePreferences.getString("username", USERNAME);
            String password = securePreferences.getString("password", PASSWORD);
            int currentIndex = getCurrentIndexAndIncrement();

            String smbUrl = mediaFiles.get(currentIndex);
            CIFSContext baseContext = SingletonContext.getInstance();
            CIFSContext authedContext = baseContext.withCredentials(new NtlmPasswordAuthenticator(username, password));
            SmbFile smbFile = new SmbFile(smbUrl, authedContext);
            if (!smbFile.exists()) {
                return;
            }

            String cacheFileName = "cache_" + smbFile.getName();
            File tempFile = new File(getCacheDir(), cacheFileName);
            if (tempFile.exists()) {
                System.out.println("T5.1, reusing: " + smbFile.getName());
            } else {
                tempFile = File.createTempFile(cacheFileName, "", getCacheDir());
                tempFile.deleteOnExit();
            }
            String path1 = tempFile.getPath();
            try (SmbFileInputStream smbInputStream = new SmbFileInputStream(smbFile);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = smbInputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
                runOnUiThread(() -> {
                    try {
                        if (mediaPlayer == null) {
                            mediaPlayer = new MediaPlayer();
                        } else {
                            mediaPlayer.reset();
                        }
                        mediaMetadataRetriever = new MediaMetadataRetriever();
                        mediaMetadataRetriever.setDataSource(path1);

                        mediaPlayer.setDataSource(path1);
                        mediaPlayer.setOnPreparedListener(MediaPlayer::start);
                        mediaPlayer.prepareAsync();
                        mediaPlayer.setOnCompletionListener(mp -> {
                            int currentIndex2 = getCurrentIndexAndIncrement();
                            securePreferences.edit().putString("currentIndex", String.valueOf(currentIndex2 + 1)).apply();
                            setMediaStatusToLoading(currentIndex2);
                            executor.execute(this::playNextMedia);
                        });
                    } catch (Exception e) {
                        System.out.println("T5.5, error: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                System.out.println("T5.6, error: " + smbFile.getName());
            }
        } catch (Throwable e) {
            System.out.println("T5.7, error: " + e.getMessage());
        }
    }

    private void setMediaStatusToLoading(int currentIndex2) {
        String mediaStatus = "[" + (currentIndex2 + 1) + "/" + mediaFiles.size() + "] loading...";
        mediaInfo.setText(mediaStatus);
    }

    private int getCurrentIndexAndIncrement() {
        int currentIndex = Integer.parseInt(securePreferences.getString("currentIndex", "0"));
        securePreferences.edit().putString("currentIndex", String.valueOf(currentIndex + 1)).apply();
        if (currentIndex >= mediaFiles.size()) {
            currentIndex = 0;
        }
        currentFileIndex = currentIndex;
        return currentIndex;
    }

    private void scanDirectory(String smbPath, String server, String port, String username, String password) {
        try {
            // if (mediaFiles.size() > 15) return; // TODO: REMOVE THIS TEST CODE
            String serverAndPort = "smb://" + server + ":" + port;
            String smbUrl = serverAndPort + smbPath;
            CIFSContext baseContext = SingletonContext.getInstance();
            CIFSContext authedContext = baseContext.withCredentials(new NtlmPasswordAuthenticator(username, password));
            SmbFile smbDir = new SmbFile(smbUrl, authedContext);
            if (!smbDir.exists() || !smbDir.isDirectory()) {
                return;
            }
            SmbFile[] files = smbDir.listFiles();
            if (files == null) {
                return;
            }
            for (SmbFile file : files) {
                if (file.isDirectory()) {
                    scanDirectory(file.getPath().substring(serverAndPort.length()), server, port, username, password);
                } else if (isMediaFile(file.getName())) {
                    mediaFiles.add(file.getPath());
                }
            }
        } catch (Exception e) {
            log.error("Error scanning directory: {}", smbPath, e);
        }
    }

    private boolean isMediaFile(String fileName) {
        String[] mediaExtensions = {".mp3", ".mp4", ".wav", ".m4a"};
        for (String ext : mediaExtensions) {
            if (fileName.toLowerCase().endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private String getFileName(String filePath) {
        return filePath.substring(filePath.lastIndexOf("/") + 1);
    }

    private void hideSystemUI() {
        View decorView = this.getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY // Enables immersive mode
                        | View.SYSTEM_UI_FLAG_FULLSCREEN // Hides the status bar
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // Hides the navigation bar
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    private void showSystemUI() {
        View decorView = this.getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (handler != null && timeUpdater != null) {
            handler.removeCallbacks(timeUpdater);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        hideSystemUI();
    }

    @Override
    public void onPause() {
        super.onPause();
        showSystemUI();
    }
}




/*import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;*/

//    private LibVLC libVLC;
//    private MediaPlayer mediaPlayer;

        /*if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.detachViews();
            mediaPlayer.release();
        }

        if (libVLC != null) {
            libVLC.release();
        }*/


    /*private void initPlayer() {
        libVLC = new LibVLC(this);
        mediaPlayer = new MediaPlayer(libVLC);

        VLCVideoLayout videoLayout = findViewById(R.id.videoLayout);
        mediaPlayer.attachViews(videoLayout, null, false, false);

        if (!mediaFiles.isEmpty()) {
            String mediaUrl = mediaFiles.get(0);
            log.info("Playing media file: {}", mediaUrl);
            Media media = new Media(libVLC, mediaUrl);
            mediaPlayer.setMedia(media);
            mediaPlayer.play();
        }
    }*/

    /*private void initPlayer() {
        try {
            ExoPlayer exoPlayer = new ExoPlayer.Builder(this).build();
            PlayerView playerView = findViewById(R.id.playerView);
            playerView.setPlayer(exoPlayer);
            for (String mediaFile : mediaFiles) {
                MediaItem mediaItem = MediaItem.fromUri(mediaFile);
                exoPlayer.addMediaItem(mediaItem);
            }
            exoPlayer.prepare();
            if (!mediaFiles.isEmpty()) {
                exoPlayer.play();
            } else {
                log.warn("No media files found to play.");
            }
        } catch (Exception e) {
            log.error("Error initializing player", e);
        }
    }*/
