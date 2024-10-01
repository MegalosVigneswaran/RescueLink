package megalos.vicky.RescueLink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;
import org.vosk.android.RecognitionListener;

import java.io.IOException;

public class ForegroundService extends Service implements RecognitionListener {

    private static final String TAG = "ForegroundService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private SpeechService speechService;
    private Model model;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, getNotification());
        LibVosk.setLogLevel(LogLevel.INFO);
        initModel();
    }

    private void initModel() {
        StorageService.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    startRecognition();
                },
                (exception) -> Log.e(TAG, "Failed to unpack the model: " + exception.getMessage()));
    }

    private void startRecognition() {
        try {
            Recognizer recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this); // Pass this as the listener
        } catch (IOException e) {
            Log.e(TAG, "Error starting recognition: " + e.getMessage());
        }
    }

    private Notification getNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("Speech Recognition Service")
                .setContentText("Listening for audio input...")
                .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's notification icon
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't need to bind to this service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speechService != null) {
            speechService.stop();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        startService(restartServiceIntent);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onResult(String hypothesis) {
        Log.d(TAG, "Recognition result: " + hypothesis);
        checkForHelpRequest(hypothesis);
    }

    @Override
    public void onPartialResult(String hypothesis) {
        Log.d(TAG, "Partial recognition result: " + hypothesis);
    }

    @Override
    public void onFinalResult(String hypothesis) {
        Log.d(TAG, "Final recognition result: " + hypothesis);
        checkForHelpRequest(hypothesis);
    }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "Recognition error: " + e.getMessage());
    }

    @Override
    public void onTimeout() {
        Log.d(TAG, "Recognition timeout");
    }

    private void checkForHelpRequest(String hypothesis) {
        if (hypothesis.contains("help me")) {
            sendHelpNotification();
        }
    }

    private void sendHelpNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Notification notification = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Help Request")
                    .setContentText("Someone needs help!")
                    .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's notification icon
                    .setAutoCancel(true)
                    .build();
        }

        if (notificationManager != null) {
            notificationManager.notify(2, notification);
        }

        Log.d(TAG, "Help notification sent.");  // Log when help notification is sent
    }
}
