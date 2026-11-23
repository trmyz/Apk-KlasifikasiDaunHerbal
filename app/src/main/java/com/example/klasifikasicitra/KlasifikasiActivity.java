package com.example.klasifikasicitra;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.canhub.cropper.CropImageContract;
import com.canhub.cropper.CropImageContractOptions;
import com.canhub.cropper.CropImageOptions;
import com.canhub.cropper.CropImageView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class KlasifikasiActivity extends AppCompatActivity {

    private ImageView imgPreview;
    private LinearLayout layoutPlaceholder;
    private TextView badgeSiap;

    private Button btnCamera;
    private Button btnGallery;
    private Button btnKlasifikasi;

    private LinearLayout layoutEmptyResult;
    private LinearLayout layoutResultContent;
    private TextView txtResult;
    private TextView txtScientific;
    private TextView txtConfidence;
    private ProgressBar progressConfidence;

    private Uri imageUri;
    private Uri cameraImageUri;
    private File cameraTempFile;

    private ActivityResultLauncher<Intent> galleryLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> cameraPermissionLauncher;
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private ActivityResultLauncher<CropImageContractOptions> cropLauncher;

    private TFLiteClassifier tfliteClassifier;
    private DatabaseHelper databaseHelper;

    private static final int CONFIDENCE_THRESHOLD = 55;

    private static final String STATE_CAMERA_URI = "state_camera_uri";
    private static final String STATE_CAMERA_TEMP_PATH =
            "state_camera_temp_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_klasifikasi);

        restoreCameraState(savedInstanceState);

        bindViews();
        setupLaunchers();
        setupClickListeners();

        loadModel();
        databaseHelper = new DatabaseHelper(this);
    }

    private void bindViews() {
        imgPreview = findViewById(R.id.imgPreview);
        layoutPlaceholder = findViewById(R.id.layoutPlaceholder);
        badgeSiap = findViewById(R.id.badgeSiap);

        btnCamera = findViewById(R.id.btnCamera);
        btnGallery = findViewById(R.id.btnGallery);
        btnKlasifikasi = findViewById(R.id.btnKlasifikasi);

        layoutEmptyResult = findViewById(R.id.layoutEmptyResult);
        layoutResultContent = findViewById(R.id.layoutResultContent);
        txtResult = findViewById(R.id.txtResult);
        txtScientific = findViewById(R.id.txtScientific);
        txtConfidence = findViewById(R.id.txtConfidence);
        progressConfidence = findViewById(R.id.progressConfidence);
    }

    private void setupLaunchers() {

        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK
                            && result.getData() != null) {

                        Uri selectedUri = result.getData().getData();

                        if (selectedUri != null) {
                            startCrop(selectedUri);
                        }
                    }
                }
        );

        /*
         * Kamera menyimpan foto ke file sementara melalui FileProvider.
         * Setelah berhasil, foto disalin ke galeri melalui MediaStore.
         */
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {

                    /*
                     * Jangan hanya mengandalkan nilai success.
                     * Beberapa aplikasi kamera dapat mengembalikan false
                     * meskipun file foto sudah berhasil ditulis.
                     */
                    boolean photoExists = isCameraPhotoAvailable();

                    if (cameraImageUri != null && photoExists) {
                        try {
                            Uri galleryUri = copyImageToGallery(
                                    cameraImageUri,
                                    "camera_"
                            );

                            deleteCameraTempFile();
                            cameraImageUri = galleryUri;

                            Toast.makeText(
                                    this,
                                    "Foto kamera tersimpan di galeri",
                                    Toast.LENGTH_SHORT
                            ).show();

                            startCrop(galleryUri);

                        } catch (IOException error) {
                            deleteCameraTempFile();
                            cameraImageUri = null;

                            Toast.makeText(
                                    this,
                                    "Gagal menyimpan foto kamera: "
                                            + error.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        }

                    } else {
                        long fileSize = cameraTempFile != null
                                && cameraTempFile.exists()
                                ? cameraTempFile.length()
                                : 0L;

                        deleteCameraTempFile();
                        cameraImageUri = null;

                        Toast.makeText(
                                this,
                                "Foto kamera tidak tersimpan. "
                                        + "success=" + success
                                        + ", ukuran=" + fileSize
                                        + " byte",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
        );

        cropLauncher = registerForActivityResult(
                new CropImageContract(),
                result -> {
                    if (result.isSuccessful()) {

                        Uri temporaryEditedUri = result.getUriContent();

                        if (temporaryEditedUri != null) {
                            try {
                                imageUri = copyImageToGallery(
                                        temporaryEditedUri,
                                        "edited_"
                                );

                                onImageSelected();

                                Toast.makeText(
                                        this,
                                        "Hasil edit tersimpan di galeri",
                                        Toast.LENGTH_SHORT
                                ).show();

                            } catch (IOException error) {
                                Toast.makeText(
                                        this,
                                        "Gagal menyimpan hasil edit: "
                                                + error.getMessage(),
                                        Toast.LENGTH_LONG
                                ).show();
                            }
                        }

                    } else {
                        Exception error = result.getError();

                        Toast.makeText(
                                this,
                                "Edit gambar gagal: "
                                        + (error != null
                                        ? error.getMessage()
                                        : "Tidak diketahui"),
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        checkStorageAndOpenCamera();
                    } else {
                        Toast.makeText(
                                this,
                                "Izin kamera diperlukan",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        openCamera();
                    } else {
                        Toast.makeText(
                                this,
                                "Izin penyimpanan diperlukan",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );
    }

    private void setupClickListeners() {
        btnGallery.setOnClickListener(v -> openGallery());

        btnCamera.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED) {

                checkStorageAndOpenCamera();

            } else {
                cameraPermissionLauncher.launch(
                        Manifest.permission.CAMERA
                );
            }
        });

        btnKlasifikasi.setOnClickListener(v -> {
            if (imageUri == null) {
                Toast.makeText(
                        this,
                        "Silakan pilih gambar terlebih dahulu",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            runClassification();
        });
    }

    private void checkStorageAndOpenCamera() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != PackageManager.PERMISSION_GRANTED) {

            storagePermissionLauncher.launch(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            );
            return;
        }

        openCamera();
    }

    private void loadModel() {
        try {
            tfliteClassifier = new TFLiteClassifier(
                    this,
                    "model_daun.tflite",
                    "labels (1).txt"
            );
        } catch (Throwable error) {
            tfliteClassifier = null;

            Toast.makeText(
                    this,
                    "Gagal memuat model: " + error.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void openGallery() {
        Intent intent = new Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        );

        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private void restoreCameraState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            return;
        }

        String uriText = savedInstanceState.getString(
                STATE_CAMERA_URI
        );

        if (uriText != null && !uriText.isEmpty()) {
            cameraImageUri = Uri.parse(uriText);
        }

        String temporaryPath = savedInstanceState.getString(
                STATE_CAMERA_TEMP_PATH
        );

        if (temporaryPath != null && !temporaryPath.isEmpty()) {
            cameraTempFile = new File(temporaryPath);
        }
    }

    private boolean isCameraPhotoAvailable() {
        return cameraTempFile != null
                && cameraTempFile.exists()
                && cameraTempFile.isFile()
                && cameraTempFile.length() > 0L;
    }

    private void openCamera() {
        try {
            deleteCameraTempFile();

            cameraTempFile = createImageFile();

            cameraImageUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    cameraTempFile
            );

            cameraLauncher.launch(cameraImageUri);

        } catch (Exception error) {
            deleteCameraTempFile();
            cameraImageUri = null;

            Toast.makeText(
                    this,
                    "Gagal membuka kamera: "
                            + error.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private File createImageFile() throws IOException {
        String fileName =
                "camera_temp_" + System.currentTimeMillis();

        File storageDirectory =
                getExternalFilesDir(
                        Environment.DIRECTORY_PICTURES
                );

        if (storageDirectory == null) {
            throw new IOException(
                    "Folder penyimpanan kamera tidak tersedia"
            );
        }

        if (!storageDirectory.exists()
                && !storageDirectory.mkdirs()) {
            throw new IOException(
                    "Folder penyimpanan kamera gagal dibuat"
            );
        }

        return File.createTempFile(
                fileName,
                ".jpg",
                storageDirectory
        );
    }

    private void deleteCameraTempFile() {
        if (cameraTempFile != null
                && cameraTempFile.exists()) {
            cameraTempFile.delete();
        }

        cameraTempFile = null;
    }

    private void startCrop(Uri sourceUri) {
        CropImageOptions editOptions = new CropImageOptions();

        editOptions.allowRotation = true;
        editOptions.allowFlipping = true;
        editOptions.rotationDegrees = 90;

        editOptions.canChangeCropWindow = false;
        editOptions.centerMoveEnabled = false;
        editOptions.multiTouchEnabled = false;
        editOptions.showCropOverlay = false;
        editOptions.guidelines = CropImageView.Guidelines.OFF;
        editOptions.fixAspectRatio = false;
        editOptions.autoZoomEnabled = false;
        editOptions.maxZoom = 1;

        editOptions.activityTitle = "Penyesuaian Gambar";
        editOptions.cropMenuCropButtonTitle = "Simpan";

        editOptions.outputCompressFormat =
                Bitmap.CompressFormat.JPEG;
        editOptions.outputCompressQuality = 100;

        // Resize 224 x 224 tetap dilakukan di TFLiteClassifier.
        CropImageContractOptions contractOptions =
                new CropImageContractOptions(
                        sourceUri,
                        editOptions
                );

        cropLauncher.launch(contractOptions);
    }

    private Uri createGalleryImageUri(String filePrefix)
            throws IOException {

        String fileName = filePrefix
                + System.currentTimeMillis()
                + ".jpg";

        ContentValues values = new ContentValues();

        values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                fileName
        );

        values.put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/jpeg"
        );

        values.put(
                MediaStore.Images.Media.DATE_TAKEN,
                System.currentTimeMillis()
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES
                            + "/KlasifikasiDaun"
            );

            values.put(
                    MediaStore.Images.Media.IS_PENDING,
                    1
            );

        } else {
            File picturesDirectory =
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES
                    );

            File appDirectory = new File(
                    picturesDirectory,
                    "KlasifikasiDaun"
            );

            if (!appDirectory.exists()
                    && !appDirectory.mkdirs()) {
                throw new IOException(
                        "Folder galeri KlasifikasiDaun gagal dibuat"
                );
            }

            File outputFile = new File(
                    appDirectory,
                    fileName
            );

            values.put(
                    MediaStore.Images.Media.DATA,
                    outputFile.getAbsolutePath()
            );
        }

        Uri savedUri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
        );

        if (savedUri == null) {
            throw new IOException(
                    "MediaStore gagal membuat gambar"
            );
        }

        return savedUri;
    }

    private void publishGalleryImage(Uri galleryImageUri) {
        if (galleryImageUri == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Images.Media.IS_PENDING,
                    0
            );

            getContentResolver().update(
                    galleryImageUri,
                    values,
                    null,
                    null
            );
        }
    }

    private Uri copyImageToGallery(
            Uri sourceUri,
            String filePrefix
    ) throws IOException {

        Uri destinationUri =
                createGalleryImageUri(filePrefix);

        try (
                InputStream inputStream =
                        getContentResolver()
                                .openInputStream(sourceUri);

                OutputStream outputStream =
                        getContentResolver()
                                .openOutputStream(destinationUri)
        ) {
            if (inputStream == null) {
                throw new IOException(
                        "Gambar sumber tidak dapat dibaca"
                );
            }

            if (outputStream == null) {
                throw new IOException(
                        "File galeri tidak dapat ditulis"
                );
            }

            byte[] buffer = new byte[8192];
            int length;

            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(
                        buffer,
                        0,
                        length
                );
            }

            outputStream.flush();

        } catch (Exception error) {
            getContentResolver().delete(
                    destinationUri,
                    null,
                    null
            );

            if (error instanceof IOException) {
                throw (IOException) error;
            }

            throw new IOException(
                    "Gagal menyalin gambar ke galeri",
                    error
            );
        }

        publishGalleryImage(destinationUri);
        return destinationUri;
    }

    private void onImageSelected() {
        imgPreview.setImageURI(imageUri);
        imgPreview.setVisibility(View.VISIBLE);

        layoutPlaceholder.setVisibility(View.GONE);
        badgeSiap.setVisibility(View.VISIBLE);

        resetResult();
    }

    private void runClassification() {
        if (tfliteClassifier == null) {
            Toast.makeText(this, "Model belum berhasil dimuat", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            ClassifierResult result = tfliteClassifier.classify(this, imageUri);

            int confidencePercent = result.confidencePercent;
            float margin = result.margin;
            double normalizedEntropy = result.normalizedEntropy;

            // ✅ Gabungan tiga kondisi penolakan
            boolean isUnknown = confidencePercent < 55
                    || margin < 20.0f
                    || normalizedEntropy > 0.55;

            String label;
            String description;

            if (isUnknown) {
                label = "Gambar tidak dikenali";
                description = "";
            } else {
                label = result.label;
                description = "Hasil prediksi model MobileNetV2";
            }

            showResult(label, description, confidencePercent);
            saveHistory(label, confidencePercent);

        } catch (Exception error) {
            Toast.makeText(this, "Gagal melakukan klasifikasi: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showResult(
            String label,
            String description,
            int confidencePercent
    ) {
        layoutEmptyResult.setVisibility(View.GONE);
        layoutResultContent.setVisibility(View.VISIBLE);

        txtResult.setText(label);
        txtScientific.setText(description);

        txtConfidence.setText(
                String.format(
                        Locale.getDefault(),
                        "%d%%",
                        confidencePercent
                )
        );

        progressConfidence.setProgress(0);
        progressConfidence.setProgress(
                confidencePercent,
                true
        );
    }

    private void saveHistory(
            String label,
            int confidencePercent
    ) {
        if (databaseHelper == null) {
            databaseHelper = new DatabaseHelper(this);
        }

        String waktu = new SimpleDateFormat(
                "dd-MM-yyyy HH:mm:ss",
                Locale.getDefault()
        ).format(new Date());

        String imagePath = imageUri != null
                ? imageUri.toString()
                : "";

        boolean berhasil =
                databaseHelper.insertHistory(
                        label,
                        confidencePercent,
                        waktu,
                        imagePath
                );

        Toast.makeText(
                this,
                berhasil
                        ? "Riwayat berhasil disimpan"
                        : "Riwayat gagal disimpan",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void resetResult() {
        layoutResultContent.setVisibility(View.GONE);
        layoutEmptyResult.setVisibility(View.VISIBLE);
        progressConfidence.setProgress(0);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (cameraImageUri != null) {
            outState.putString(
                    STATE_CAMERA_URI,
                    cameraImageUri.toString()
            );
        }

        if (cameraTempFile != null) {
            outState.putString(
                    STATE_CAMERA_TEMP_PATH,
                    cameraTempFile.getAbsolutePath()
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (tfliteClassifier != null) {
            tfliteClassifier.close();
        }

        if (databaseHelper != null) {
            databaseHelper.close();
        }
    }
}