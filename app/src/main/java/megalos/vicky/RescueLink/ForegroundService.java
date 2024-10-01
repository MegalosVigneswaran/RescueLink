package megalos.vicky.RescueLink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.location.Location;
import android.media.Image;
import android.os.Handler;
import android.util.Pair;
import android.util.Size;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ForegroundService extends LifecycleService implements RecognitionListener {

    private static final String TAG = "ForegroundService";
    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private SpeechService speechService;
    private Model model;
    private LocationRequest locationRequest;
    private FusedLocationProviderClient fusedLocationClient;
    private DatabaseReference databaseRef;
    private Handler handler;
    private Runnable locationUpdater;
    private FaceDetector detector;
    private Interpreter tfLite;
    private boolean developerMode = false;
    private float distance = 1.0f;
    private boolean flipX = false;
    private int cam_face = CameraSelector.LENS_FACING_BACK; // Default Back Camera
    private int index = 0;
    private int[] intValues;
    private int inputSize = 112; // Input size for model
    private boolean isModelQuantized = false;
    private float[][] embeedings;
    private float IMAGE_MEAN = 128.0f;
    private float IMAGE_STD = 128.0f;
    private int OUTPUT_SIZE = 192;
    double prelong = 0;
    double prelat = 0;// Output size of model
    private static int SELECT_PICTURE = 1;
    private ProcessCameraProvider cameraProvider;
    private static final int MY_CAMERA_REQUEST_CODE = 100;
    private String modelFile = "mobile_face_net.tflite"; // model name
    private HashMap<String, SimilarityClassifier.Recognition> registered = new HashMap<>();

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
        super.onBind(intent);
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
        super.onStartCommand(intent, flags, startId);
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
            StartFaceRecognition();
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

    void StartFaceRecognition(){
        registered = readFromSP(); // Load saved faces from memory when app starts
        Log.d("FaceRecognition", "Registered faces loaded: " + registered.size());
        SharedPreferences sharedPref = getSharedPreferences("Distance", Context.MODE_PRIVATE);
        distance = sharedPref.getFloat("distance", 1.00f);

        try {
            tfLite = new Interpreter(loadModelFile(modelFile));
            Log.d("FaceRecognition", "Model loaded successfully");
        } catch (IOException e) {
            Log.e("FaceRecognition", "Error loading model: " + e.getMessage());
            e.printStackTrace();
        }

        FaceDetectorOptions highAccuracyOpts =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode (FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .build();
        detector = FaceDetection.getClient(highAccuracyOpts);

        startLocationUpdates();
        cameraBind();
    }

    private void startLocationUpdates() {

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        databaseRef = FirebaseDatabase.getInstance().getReference("emergency/"+loadUsernameFromProfileJson()+"/position");

        handler = new Handler();
        locationUpdater = new Runnable() {
            @SuppressLint("MissingPermission")
            @Override
            public void run() {
                fusedLocationClient.getLastLocation()
                        .addOnSuccessListener(new OnSuccessListener<Location>() {
                            @Override
                            public void onSuccess(Location location) {
                                if (location != null) {
                                    double latitude = location.getLatitude();
                                    double longitude = location.getLongitude();

                                    if(prelong != longitude && prelat != latitude){
                                        databaseRef.child("latitude").setValue(latitude);
                                        databaseRef.child("longitude").setValue(longitude);
                                        prelat = latitude;
                                        prelong = longitude;
                                        Log.d("LocationUpdate", "Location updated: Lat: " + latitude + ", Lon: " + longitude);
                                    }
                                }else{
                                    Log.d("LocationUpdate","Location is off");
                                }
                            }
                        });

                handler.postDelayed(this, 2000);
            }
        };
        handler.post(locationUpdater);
    }

    public void stopLocationUpdates() {
        if (handler != null && locationUpdater != null) {
            handler.removeCallbacks(locationUpdater);
        }
    }

    private void cameraBind() {

        Log.d("FaceRecognition", "cameraBind() called");

        ProcessCameraProvider.getInstance(this).addListener(() -> {

            try {

                cameraProvider = ProcessCameraProvider.getInstance(this).get();

                Log.d("FaceRecognition", "Camera provider bound successfully");


                // Use 'this' since LifecycleService is now a LifecycleOwner

                bindPreview(cameraProvider);

            } catch (ExecutionException | InterruptedException e) {

                Log.e("FaceRecognition", "Error binding camera provider: " + e.getMessage());

            }

        }, ContextCompat.getMainExecutor(this));

    }

    private void bindPreview(@NonNull ProcessCameraProvider cameraProvider) {
        Log.d("FaceRecognition", "bindPreview() called");

        // Load the username from profile.json
        String username = loadUsernameFromProfileJson();  // Method defined later

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(cam_face)
                .build();

        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

        Executor executor = Executors.newSingleThreadExecutor();
        imageAnalysis.setAnalyzer(executor, new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                Log.d("FaceRecognition", "analyze() called");

                @SuppressLint({"UnsafeExperimentalUsageError", "UnsafeOptInUsageError"})
                Image mediaImage = imageProxy.getImage();

                if (mediaImage != null) {
                    // Create the InputImage from mediaImage
                    InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());

                    Task<List<Face>> result =
                            detector.process(image)
                                    .addOnSuccessListener(faces -> {
                                        Log.d("FaceRecognition", "Faces detected: " + faces.size());
                                        if (faces.size() != 0) {
                                            for (Face face : faces) {
                                                Bitmap frame_bmp = toBitmap(mediaImage);
                                                int rot = imageProxy.getImageInfo().getRotationDegrees();

                                                // Adjust orientation of Face
                                                Bitmap frame_bmp1 = rotateBitmap(frame_bmp, rot, false, false);

                                                RectF boundingBox = new RectF(face.getBoundingBox());

                                                // Crop out bounding box from whole Bitmap(image)
                                                Bitmap cropped_face = getCropBitmapByCPU(frame_bmp1, boundingBox);

                                                if (flipX)
                                                    cropped_face = rotateBitmap(cropped_face, 0, flipX, false);

                                                Bitmap scaled = getResizedBitmap(cropped_face, 112, 112);

                                                if (!recognizeImage(scaled)) {
                                                    // Register new face
                                                    SimilarityClassifier.Recognition resul = new SimilarityClassifier.Recognition(
                                                            "0", "", -1f);
                                                    resul.setExtra(embeedings);
                                                    registered.put(Integer.toString(index), resul);

                                                    Toast.makeText(ForegroundService.this, "New face registered. The face ID is " + Integer.toString(index), Toast.LENGTH_LONG).show();

                                                    convertAndUploadImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());

                                                    index++;
                                                }
                                            }
                                        }
                                    })
                                    .addOnFailureListener(e -> Log.e("FaceRecognition", "Error detecting faces: " + e.getMessage()))
                                    .addOnCompleteListener(task -> {
                                        Log.d("FaceRecognition", "Face detection completed");
                                        imageProxy.close();
                                    });
                }
            }
        });

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
        } catch ( Exception e) {
            Log.e("FaceRecognition", "Error binding camera: " + e.getMessage());
        }
    }

    public void convertAndUploadImage(Image mediaImage, int rotationDegrees) {
        // Convert MediaImage to Bitmap
        Bitmap originalBitmap = Cbitmap(mediaImage); // Convert the image

        // Get a reference to Firebase Storage
        FirebaseStorage storage = FirebaseStorage.getInstance();
        StorageReference storageRef = storage.getReference();

        // Create a unique file name
        String fileName = loadUsernameFromProfileJson()+ "/" + System.currentTimeMillis() + ".jpg"; // Change the path as needed
        StorageReference imageRef = storageRef.child(fileName);

        // Convert Bitmap to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        originalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos); // Adjust quality as needed
        byte[] data = baos.toByteArray();

        // Upload the image
        UploadTask uploadTask = imageRef.putBytes(data);
        uploadTask.addOnSuccessListener(taskSnapshot -> {
            // Get the download URL
            imageRef.getDownloadUrl().addOnSuccessListener(uri -> {
                // Handle the download URL here
                String downloadUrl = uri.toString();
                // You can save the download URL to your database or use it as needed
                System.out.println("Image uploaded successfully: " + downloadUrl);
            });
        }).addOnFailureListener(exception -> {
            // Handle unsuccessful uploads
            System.err.println("Upload failed: " + exception.getMessage());
        });
    }

    private Bitmap Cbitmap(Image mediaImage) {

        Log.d("FaceRecognition", "Original Image resolution: " + mediaImage.getWidth() + "x" + mediaImage.getHeight());


        byte[] nv21 = YUV_420_888toNV21(mediaImage);


        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, mediaImage.getWidth(), mediaImage.getHeight(), null);


        ByteArrayOutputStream out = new ByteArrayOutputStream();

        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);


        byte[] imageBytes = out.toByteArray();


        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);


        if (bitmap != null) {

            Log.d("FaceRecognition", "Converted Bitmap resolution: " + bitmap.getWidth() + "x" + bitmap.getHeight());

        } else {

            Log.e("FaceRecognition", "Failed to convert Image to Bitmap.");

        }


        return bitmap;

    }

    private String loadUsernameFromProfileJson() {
        try {
            FileInputStream fis = openFileInput("profile.json");
            byte[] buffer = new byte[fis.available()];
            fis.read(buffer);
            fis.close();

            String jsonString = new String(buffer, "UTF-8");
            JSONObject jsonObject = new JSONObject(jsonString);

            // Get the username from the JSON object
            return jsonObject.getString("username");
        } catch (Exception e) {
            Log.e("FaceRecognition", "Error reading profile.json: " + e.getMessage());
            return "unknown_user";  // Fallback if an error occurs
        }
    }

    private MappedByteBuffer loadModelFile(String MODEL_FILE) throws IOException {
        Log.d("FaceRecognition", "loadModelFile() called");
        AssetFileDescriptor fileDescriptor = getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public boolean recognizeImage(final Bitmap bitmap) {
        Log.d("FaceRecognition", "recognizeImage() called");
        boolean isfound = false;

        ByteBuffer imgData = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4);

        imgData.order(ByteOrder.nativeOrder());

        intValues = new int[inputSize * inputSize];

        // get pixel values from Bitmap to normalize
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        imgData.rewind();

        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                int pixelValue = intValues[i * inputSize + j];
                if (isModelQuantized) {
                    // Quantized model
                    imgData.put((byte) ((pixelValue >> 16) & 0xFF));
                    imgData.put((byte) ((pixelValue >> 8) & 0xFF));
                    imgData.put((byte) (pixelValue & 0xFF));
                } else { // Float model
                    imgData.putFloat ((((pixelValue >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat((((pixelValue >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat(((pixelValue & 0xFF) - IMAGE_MEAN) / IMAGE_STD);

                }
            }
        }

        // imgData is input to our model
        Object[] inputArray = {imgData};

        Map<Integer, Object> outputMap = new HashMap<>();

        embeedings = new float[1][OUTPUT_SIZE]; // output of model will be stored in this variable

        outputMap.put(0, embeedings);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap); // Run model


        float distance_local = Float.MAX_VALUE;
        String id = "0";
        String label = "?";

        if (registered.size() > 0) {

            final List<Pair<String, Float>> nearest = findNearest(embeedings[0]);// Find 2 closest matching face

            if (nearest.get(0) != null) {
                distance_local = nearest.get(0).second;
                if (distance_local < distance)
                    isfound = true;
                else
                    isfound = false;
            }
        }
        return isfound;
    }

    private List<Pair<String, Float>> findNearest(float[] emb) {
        Log.d("FaceRecognition", "findNearest() called");
        List<Pair<String, Float>> neighbour_list = new ArrayList<Pair<String, Float>>();
        Pair<String, Float> ret = null; // to get closest match
        Pair<String, Float> prev_ret = null; // to get second closest match
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : registered.entrySet()) {

            final String name = entry.getKey();
            final float[] knownEmb = ((float[][]) entry.getValue().getExtra())[0];

            float distance = 0;
            for (int i = 0; i < emb.length; i++) {
                float diff = emb[i] - knownEmb[i];
                distance += diff * diff;
            }
            distance = (float) Math.sqrt(distance);
            if (ret == null || distance < ret.second) {
                prev_ret = ret;
                ret = new Pair<>(name, distance);
            }
        }
        if (prev_ret == null) prev_ret = ret;
        neighbour_list.add(ret);
        neighbour_list.add(prev_ret);

        return neighbour_list;

    }

    public Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        Log.d("FaceRecognition", "getResizedBitmap() called");
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
        bm.recycle();
        return resizedBitmap;
    }

    private static Bitmap getCropBitmapByCPU(Bitmap source, RectF cropRectF) {
        Log.d("FaceRecognition", "getCropBitmapByCPU() called");
        Bitmap resultBitmap = Bitmap.createBitmap((int) cropRectF.width(),
                (int) cropRectF.height(), Bitmap.Config.ARGB_8888);
        Canvas cavas = new Canvas(resultBitmap);

        // draw background
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        paint.setColor(Color.WHITE);
        cavas.drawRect(
                new RectF(0, 0, cropRectF.width(), cropRectF.height()),
                paint);

        Matrix matrix = new Matrix();
        matrix.postTranslate(-cropRectF.left, -cropRectF.top);

        cavas.drawBitmap(source, matrix, paint);

        if (source != null && !source.isRecycled()) {
            source.recycle();
        }

        return resultBitmap;
    }

    private static Bitmap rotateBitmap(Bitmap bitmap, int rotationDegrees, boolean flipX, boolean flipY) {
        Log.d("Face Recognition", "rotateBitmap() called");
        Matrix matrix = new Matrix();

        // Rotate the image back to straight.
        matrix.postRotate(rotationDegrees);

        // Mirror the image along the X or Y axis.
        matrix.postScale(flipX ? -1.0f : 1.0f, flipY ? - 1.0f : 1.0f);
        Bitmap rotatedBitmap =
                Bitmap. createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        // Recycle the old bitmap if it has changed.
        if (rotatedBitmap != bitmap) {
            bitmap.recycle();
        }
        return rotatedBitmap;
    }

    private static byte[] YUV_420_888toNV21(Image image) {
        Log.d("FaceRecognition", "YUV_420_888toNV21() called");
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;

        byte[] nv21 = new byte[ySize + uvSize * 2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

        int rowStride = image.getPlanes()[0].getRowStride();
        assert (image.getPlanes()[0].getPixelStride() == 1);

        int pos = 0;

        if (rowStride == width) { // likely
            yBuffer.get(nv21, 0, ySize);
            pos += ySize;
        } else {
            long yBufferPos = -rowStride; // not an actual position
            for (; pos < ySize; pos += width) {
                yBufferPos += rowStride;
                yBuffer.position((int) yBufferPos);
                yBuffer.get(nv21, pos, width);
            }
        }

        rowStride = image.getPlanes()[2].getRowStride();
        int pixelStride = image.getPlanes()[2].getPixelStride();

        assert (rowStride == image.getPlanes()[1].getRowStride());
        assert (pixelStride == image.getPlanes()[1].getPixelStride());

        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            byte savePixel = vBuffer.get(1);
            try {
                vBuffer.put(1, (byte) ~savePixel);
                if (uBuffer.get(0) == (byte) ~savePixel) {
                    vBuffer.put(1, savePixel);
                    vBuffer.position(0);
                    uBuffer.position(0);
                    vBuffer.get(nv21, ySize, 1);
                    uBuffer.get(nv21, ySize + 1, uBuffer.remaining());

                    return nv21; // shortcut
                }
            } catch (ReadOnlyBufferException ex) {
                // unfortunately, we cannot check if vBuffer and uBuffer overlap
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel);
        }

        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int vuPos = col * pixelStride + row * rowStride;
                nv21[pos++] = vBuffer.get(vuPos);
                nv21[pos++] = uBuffer.get(vuPos);
            }
        }

        return nv21;
    }

    private Bitmap toBitmap(Image image) {
        Log.d("FaceRecognition", "toBitmap() called");
        byte[] nv21 = YUV_420_888toNV21(image);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);

        byte[] imageBytes = out.toByteArray();

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }

    private HashMap<String, SimilarityClassifier.Recognition> readFromSP() {
        Log.d("FaceRecognition", "readFromSP() called");
        SharedPreferences sharedPreferences = getSharedPreferences("HashMap", Context.MODE_PRIVATE);
        String defValue = new Gson().toJson(new HashMap<String, SimilarityClassifier.Recognition>());
        String json = sharedPreferences.getString("map", defValue);
        TypeToken<HashMap<String, SimilarityClassifier.Recognition>> token = new TypeToken<HashMap<String, SimilarityClassifier.Recognition>>() {
        };
        HashMap<String, SimilarityClassifier.Recognition> retrievedMap = new Gson().fromJson(json, token.getType());
        for (Map.Entry<String, SimilarityClassifier.Recognition> entry : retrievedMap.entrySet()) {
            float[][] output = new float[1][OUTPUT_SIZE];
            ArrayList arrayList = (ArrayList) entry.getValue().getExtra();
            arrayList = (ArrayList) arrayList.get(0);
            for (int counter = 0; counter < arrayList.size(); counter++) {
                output[0][counter] = ((Double) arrayList.get(counter)).floatValue();
            }
            entry.getValue().setExtra(output);

        }
        return retrievedMap;
    }

}
