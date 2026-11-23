package com.example.klasifikasicitra;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "klasifikasi_daun.db";
    private static final int DATABASE_VERSION = 2;

    public static final String TABLE_HISTORY = "riwayat_klasifikasi";

    public static final String COL_ID = "id";
    public static final String COL_NAMA_DAUN = "nama_daun";
    public static final String COL_CONFIDENCE = "confidence";
    public static final String COL_WAKTU = "waktu";
    public static final String COL_IMAGE_PATH = "image_path";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_HISTORY + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_NAMA_DAUN + " TEXT, " +
                COL_CONFIDENCE + " REAL, " +
                COL_WAKTU + " TEXT, " +
                COL_IMAGE_PATH + " TEXT" +
                ")";

        db.execSQL(createTable);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_HISTORY);
        onCreate(db);
    }

    public boolean insertHistory(String namaDaun, float confidence, String waktu, String imagePath) {
        SQLiteDatabase db = this.getWritableDatabase();

        try {
            ContentValues values = new ContentValues();
            values.put(COL_NAMA_DAUN, namaDaun);
            values.put(COL_CONFIDENCE, confidence);
            values.put(COL_WAKTU, waktu);
            values.put(COL_IMAGE_PATH, imagePath);

            long result = db.insert(TABLE_HISTORY, null, values);

            android.util.Log.d("DB_CHECK", "Insert result: " + result);
            android.util.Log.d("DB_CHECK", "Nama daun: " + namaDaun);
            android.util.Log.d("DB_CHECK", "Confidence: " + confidence);
            android.util.Log.d("DB_CHECK", "Waktu: " + waktu);
            android.util.Log.d("DB_CHECK", "Image path: " + imagePath);

            return result != -1;

        } catch (Exception e) {
            android.util.Log.e("DB_CHECK", "Insert gagal: " + e.getMessage());
            return false;

        } finally {
            db.close();
        }
    }

    public Cursor getAllHistory() {
        SQLiteDatabase db = this.getReadableDatabase();

        return db.rawQuery(
                "SELECT * FROM " + TABLE_HISTORY + " ORDER BY " + COL_ID + " DESC",
                null
        );
    }

    public void deleteAllHistory() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_HISTORY, null, null);
        db.close();
    }
}