package com.example.klasifikasicitra;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TFLiteClassifier {

    private static final String TAG = "TFLiteClassifier";

    /*
     * true  = setiap klasifikasi menyimpan exact input 224 x 224
     *         ke galeri untuk dibandingkan dengan notebook.
     * false = nonaktifkan setelah proses pengujian selesai agar
     *         galeri tidak dipenuhi file debug.
     */
    private static final boolean SAVE_EXACT_INPUT = true;

    private Interpreter interpreter;
    private final List<String> labels;

    private final int inputWidth;
    private final int inputHeight;
    private final int inputChannels;
    private final int outputSize;

    public TFLiteClassifier(
            Context context,
            String modelName,
            String labelName
    ) throws IOException {

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);

        interpreter = new Interpreter(
                loadModelFile(context, modelName),
                options
        );

        labels = loadLabels(context, labelName);

        int[] inputShape = interpreter
                .getInputTensor(0)
                .shape();

        DataType inputDataType = interpreter
                .getInputTensor(0)
                .dataType();

        if (inputShape.length != 4) {
            throw new IllegalStateException(
                    "Bentuk input model tidak valid: "
                            + arrayToString(inputShape)
            );
        }

        inputHeight = inputShape[1];
        inputWidth = inputShape[2];
        inputChannels = inputShape[3];

        if (inputChannels != 3) {
            throw new IllegalStateException(
                    "Model harus menerima citra RGB 3 channel. "
                            + "Channel ditemukan: "
                            + inputChannels
            );
        }

        if (inputDataType != DataType.FLOAT32) {
            throw new IllegalStateException(
                    "Kode ini membutuhkan model FLOAT32. "
                            + "Tipe input ditemukan: "
                            + inputDataType
            );
        }

        int[] outputShape = interpreter
                .getOutputTensor(0)
                .shape();

        DataType outputDataType = interpreter
                .getOutputTensor(0)
                .dataType();

        if (outputShape.length != 2) {
            throw new IllegalStateException(
                    "Bentuk output model tidak valid: "
                            + arrayToString(outputShape)
            );
        }

        outputSize = outputShape[1];

        if (outputDataType != DataType.FLOAT32) {
            throw new IllegalStateException(
                    "Kode ini membutuhkan output FLOAT32. "
                            + "Tipe output ditemukan: "
                            + outputDataType
            );
        }

        if (labels.size() != outputSize) {
            throw new IllegalStateException(
                    "Jumlah label tidak sama dengan output model. "
                            + "Jumlah label: "
                            + labels.size()
                            + ", output model: "
                            + outputSize
            );
        }

        Log.d(
                TAG,
                "Model berhasil dimuat. Input: "
                        + arrayToString(inputShape)
                        + ", Output: "
                        + arrayToString(outputShape)
                        + ", Tipe input: "
                        + inputDataType
        );

        try {
            Log.d(
                    TAG,
                    "SHA256_MODEL_ANDROID = "
                            + calculateAssetSha256(
                            context,
                            modelName
                    )
            );
        } catch (Exception error) {
            Log.e(
                    TAG,
                    "Hash model tidak berhasil dihitung.",
                    error
            );
        }

        for (int i = 0; i < labels.size(); i++) {
            Log.d(
                    TAG,
                    "Label index " + i + ": " + labels.get(i)
            );
        }
    }

    private MappedByteBuffer loadModelFile(
            Context context,
            String modelName
    ) throws IOException {

        try (
                AssetFileDescriptor assetFileDescriptor =
                        context.getAssets().openFd(modelName);

                FileInputStream inputStream =
                        new FileInputStream(
                                assetFileDescriptor.getFileDescriptor()
                        );

                FileChannel fileChannel = inputStream.getChannel()
        ) {
            return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.getStartOffset(),
                    assetFileDescriptor.getDeclaredLength()
            );
        }
    }

    private List<String> loadLabels(
            Context context,
            String labelName
    ) throws IOException {

        List<String> labelList = new ArrayList<>();

        try (
                InputStream inputStream =
                        context.getAssets().open(labelName);

                InputStreamReader inputStreamReader =
                        new InputStreamReader(inputStream);

                BufferedReader reader =
                        new BufferedReader(inputStreamReader)
        ) {
            String line;

            while ((line = reader.readLine()) != null) {
                String cleanLabel = line.trim();

                if (!cleanLabel.isEmpty()) {
                    labelList.add(cleanLabel);
                }
            }
        }

        if (labelList.isEmpty()) {
            throw new IOException(
                    "File label kosong: " + labelName
            );
        }

        return labelList;
    }

    public ClassifierResult classify(
            Context context,
            Uri imageUri
    ) throws IOException {

        if (interpreter == null) {
            throw new IllegalStateException(
                    "Interpreter sudah ditutup."
            );
        }

        if (imageUri == null) {
            throw new IllegalArgumentException(
                    "URI gambar tidak boleh null."
            );
        }

        Bitmap correctedBitmap = null;
        Bitmap resizedBitmap = null;

        try {
            correctedBitmap = uriToCorrectedBitmap(
                    context,
                    imageUri
            );

            resizedBitmap = Bitmap.createScaledBitmap(
                    correctedBitmap,
                    inputWidth,
                    inputHeight,
                    true
            );

            Log.d(
                    TAG,
                    "Ukuran exact input Android = "
                            + resizedBitmap.getWidth()
                            + "x"
                            + resizedBitmap.getHeight()
            );

            if (SAVE_EXACT_INPUT) {
                try {
                    Uri exactInputUri = saveExactModelInput(
                            context,
                            resizedBitmap
                    );

                    Log.d(
                            TAG,
                            "EXACT_INPUT_URI = " + exactInputUri
                    );

                } catch (Exception error) {
                    /*
                     * Gagal menyimpan gambar debug tidak boleh
                     * menggagalkan proses klasifikasi.
                     */
                    Log.e(
                            TAG,
                            "Exact input gagal disimpan.",
                            error
                    );
                }
            }

            ByteBuffer inputBuffer =
                    convertBitmapToByteBuffer(resizedBitmap);

            try {
                Log.d(
                        TAG,
                        "SHA256_INPUT_BUFFER = "
                                + calculateByteBufferSha256(inputBuffer)
                );
            } catch (Exception error) {
                Log.e(
                        TAG,
                        "Hash input tensor gagal dihitung.",
                        error
                );
            }

            float[][] output =
                    new float[1][outputSize];

            inputBuffer.rewind();
            interpreter.run(inputBuffer, output);

            logAllPredictions(output[0]);

            int maxIndex = findMaximumIndex(output[0]);
            float maxConfidence = output[0][maxIndex];
            String predictedLabel = labels.get(maxIndex);

            int confidencePercent = Math.round(maxConfidence * 100f);

// ✅ Hitung margin (selisih top-1 dan top-2)
            float secondConfidence = 0f;
            for (int i = 0; i < output[0].length; i++) {
                if (i != maxIndex && output[0][i] > secondConfidence) {
                    secondConfidence = output[0][i];
                }
            }
            float margin = (maxConfidence - secondConfidence) * 100f;

            // ✅ Hitung normalized entropy
            double entropy = 0.0;
            for (float p : output[0]) {
                if (p > 0) entropy -= p * Math.log(p);
            }
            double normalizedEntropy = entropy / Math.log(outputSize);

            Log.d(TAG, String.format(Locale.US,
                    "HASIL_AKHIR index=%d, label=%s, confidence=%.4f%%, margin=%.4f%%, entropy=%.6f",
                    maxIndex, predictedLabel, maxConfidence * 100f, margin, normalizedEntropy));

            return new ClassifierResult(
                    predictedLabel,
                    confidencePercent,
                    margin,
                    normalizedEntropy
            );

        } finally {
            if (resizedBitmap != null
                    && resizedBitmap != correctedBitmap
                    && !resizedBitmap.isRecycled()) {
                resizedBitmap.recycle();
            }

            if (correctedBitmap != null
                    && !correctedBitmap.isRecycled()) {
                correctedBitmap.recycle();
            }
        }
    }

    private Bitmap uriToCorrectedBitmap(
            Context context,
            Uri imageUri
    ) throws IOException {

        Bitmap bitmap;

        try (
                InputStream imageStream =
                        context.getContentResolver()
                                .openInputStream(imageUri)
        ) {
            if (imageStream == null) {
                throw new IOException(
                        "Gagal membuka gambar dari URI."
                );
            }

            bitmap = BitmapFactory.decodeStream(imageStream);
        }

        if (bitmap == null) {
            throw new IOException(
                    "Gambar gagal dibaca atau format tidak valid."
            );
        }

        int orientation =
                ExifInterface.ORIENTATION_NORMAL;

        try (
                InputStream exifStream =
                        context.getContentResolver()
                                .openInputStream(imageUri)
        ) {
            if (exifStream != null) {
                ExifInterface exifInterface =
                        new ExifInterface(exifStream);

                orientation =
                        exifInterface.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL
                        );
            }
        } catch (Exception error) {
            Log.w(
                    TAG,
                    "Metadata EXIF tidak dapat dibaca.",
                    error
            );
        }

        Log.d(
                TAG,
                "EXIF orientation = " + orientation
        );

        return applyExifOrientation(
                bitmap,
                orientation
        );
    }

    private Bitmap applyExifOrientation(
            Bitmap bitmap,
            int orientation
    ) {

        Matrix matrix = new Matrix();

        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;

            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;

            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;

            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                return bitmap;
        }

        Bitmap correctedBitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                matrix,
                true
        );

        if (correctedBitmap != bitmap) {
            bitmap.recycle();
        }

        return correctedBitmap;
    }

    private ByteBuffer convertBitmapToByteBuffer(
            Bitmap bitmap
    ) {

        ByteBuffer byteBuffer =
                ByteBuffer.allocateDirect(
                        4
                                * inputWidth
                                * inputHeight
                                * inputChannels
                );

        byteBuffer.order(ByteOrder.nativeOrder());

        int[] pixels =
                new int[inputWidth * inputHeight];

        bitmap.getPixels(
                pixels,
                0,
                inputWidth,
                0,
                0,
                inputWidth,
                inputHeight
        );

        float minimum = 255f;
        float maximum = 0f;
        double total = 0.0;
        long valueCount = 0L;

        for (int pixel : pixels) {
            float red =
                    (pixel >> 16) & 0xFF;

            float green =
                    (pixel >> 8) & 0xFF;

            float blue =
                    pixel & 0xFF;

            /*
             * Urutan tensor adalah RGB dan nilainya tetap 0-255.
             * Jangan dibagi 255 dan jangan menjalankan normalisasi
             * -1 sampai 1 di sini karena model sudah memiliki
             * layer Rescaling internal.
             */
            byteBuffer.putFloat(red);
            byteBuffer.putFloat(green);
            byteBuffer.putFloat(blue);

            minimum = Math.min(
                    minimum,
                    Math.min(red, Math.min(green, blue))
            );

            maximum = Math.max(
                    maximum,
                    Math.max(red, Math.max(green, blue))
            );

            total += red + green + blue;
            valueCount += 3L;
        }

        byteBuffer.rewind();

        Log.d(
                TAG,
                String.format(
                        Locale.US,
                        "INPUT_TENSOR shape=[1,%d,%d,%d], "
                                + "dtype=FLOAT32, min=%.1f, max=%.1f, "
                                + "mean=%.6f",
                        inputHeight,
                        inputWidth,
                        inputChannels,
                        minimum,
                        maximum,
                        total / valueCount
                )
        );

        return byteBuffer;
    }

    private int findMaximumIndex(float[] probabilities) {

        if (probabilities == null
                || probabilities.length == 0) {
            throw new IllegalArgumentException(
                    "Output probabilitas kosong."
            );
        }

        int maxIndex = 0;
        float maxValue = probabilities[0];

        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > maxValue) {
                maxValue = probabilities[i];
                maxIndex = i;
            }
        }

        return maxIndex;
    }

    private void logAllPredictions(
            float[] probabilities
    ) {

        for (int i = 0; i < probabilities.length; i++) {
            Log.d(
                    TAG,
                    String.format(
                            Locale.US,
                            "OUTPUT[%d] %s = %.8f | %.4f%%",
                            i,
                            labels.get(i),
                            probabilities[i],
                            probabilities[i] * 100f
                    )
            );
        }
    }

    private Uri saveExactModelInput(
            Context context,
            Bitmap bitmap
    ) throws IOException {

        String fileName =
                "model_input_"
                        + System.currentTimeMillis()
                        + ".png";

        if (Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.Q) {

            ContentValues values = new ContentValues();

            values.put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    fileName
            );

            values.put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/png"
            );

            values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES
                            + "/KlasifikasiDaun/ModelInput"
            );

            values.put(
                    MediaStore.Images.Media.IS_PENDING,
                    1
            );

            Uri savedUri = context
                    .getContentResolver()
                    .insert(
                            MediaStore.Images.Media
                                    .EXTERNAL_CONTENT_URI,
                            values
                    );

            if (savedUri == null) {
                throw new IOException(
                        "Gagal membuat file exact input."
                );
            }

            try (
                    OutputStream outputStream =
                            context.getContentResolver()
                                    .openOutputStream(savedUri)
            ) {
                if (outputStream == null) {
                    throw new IOException(
                            "Exact input tidak dapat ditulis."
                    );
                }

                boolean success = bitmap.compress(
                        Bitmap.CompressFormat.PNG,
                        100,
                        outputStream
                );

                if (!success) {
                    throw new IOException(
                            "Bitmap gagal disimpan sebagai PNG."
                    );
                }

                outputStream.flush();

            } catch (Exception error) {
                context.getContentResolver().delete(
                        savedUri,
                        null,
                        null
                );

                throw new IOException(
                        "Exact input gagal disimpan.",
                        error
                );
            }

            ContentValues completedValues =
                    new ContentValues();

            completedValues.put(
                    MediaStore.Images.Media.IS_PENDING,
                    0
            );

            context.getContentResolver().update(
                    savedUri,
                    completedValues,
                    null,
                    null
            );

            return savedUri;
        }

        return saveExactInputBeforeAndroidQ(
                context,
                bitmap,
                fileName
        );
    }

    @SuppressWarnings("deprecation")
    private Uri saveExactInputBeforeAndroidQ(
            Context context,
            Bitmap bitmap,
            String fileName
    ) throws IOException {

        File picturesDirectory =
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES
                );

        File modelInputDirectory = new File(
                picturesDirectory,
                "KlasifikasiDaun/ModelInput"
        );

        if (!modelInputDirectory.exists()
                && !modelInputDirectory.mkdirs()) {
            throw new IOException(
                    "Folder ModelInput gagal dibuat."
            );
        }

        File outputFile = new File(
                modelInputDirectory,
                fileName
        );

        try (
                OutputStream outputStream =
                        new FileOutputStream(outputFile)
        ) {
            boolean success = bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    outputStream
            );

            if (!success) {
                throw new IOException(
                        "Bitmap gagal disimpan sebagai PNG."
                );
            }

            outputStream.flush();
        }

        MediaScannerConnection.scanFile(
                context,
                new String[]{outputFile.getAbsolutePath()},
                new String[]{"image/png"},
                null
        );

        return Uri.fromFile(outputFile);
    }

    private String calculateAssetSha256(
            Context context,
            String assetName
    ) throws Exception {

        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");

        try (
                InputStream inputStream =
                        context.getAssets().open(assetName)
        ) {
            byte[] buffer = new byte[8192];
            int length;

            while ((length = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
        }

        return bytesToHex(digest.digest());
    }

    private String calculateByteBufferSha256(
            ByteBuffer sourceBuffer
    ) throws Exception {

        MessageDigest digest =
                MessageDigest.getInstance("SHA-256");

        ByteBuffer duplicate = sourceBuffer.duplicate();
        duplicate.rewind();

        byte[] chunk = new byte[8192];

        while (duplicate.hasRemaining()) {
            int length = Math.min(
                    duplicate.remaining(),
                    chunk.length
            );

            duplicate.get(chunk, 0, length);
            digest.update(chunk, 0, length);
        }

        return bytesToHex(digest.digest());
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();

        for (byte value : bytes) {
            result.append(
                    String.format(
                            Locale.US,
                            "%02x",
                            value & 0xFF
                    )
            );
        }

        return result.toString();
    }

    private String arrayToString(int[] array) {
        StringBuilder result = new StringBuilder("[");

        for (int i = 0; i < array.length; i++) {
            result.append(array[i]);

            if (i < array.length - 1) {
                result.append(", ");
            }
        }

        result.append("]");
        return result.toString();
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}
