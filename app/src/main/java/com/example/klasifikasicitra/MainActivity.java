package com.example.klasifikasicitra;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private Button btnMulaiKlasifikasi;
    private LinearLayout layoutRiwayatHome;

    private DatabaseHelper databaseHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnMulaiKlasifikasi = findViewById(R.id.btnMulaiKlasifikasi);
        layoutRiwayatHome = findViewById(R.id.layoutRiwayatHome);

        databaseHelper = new DatabaseHelper(this);

        btnMulaiKlasifikasi.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, KlasifikasiActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        tampilkanRiwayatDiHome();
    }

    private void tampilkanRiwayatDiHome() {
        Cursor cursor = databaseHelper.getAllHistory();

        layoutRiwayatHome.removeAllViews();

        if (cursor == null || cursor.getCount() == 0) {
            TextView emptyText = new TextView(this);
            emptyText.setText("Belum ada riwayat klasifikasi");
            emptyText.setTextColor(0xFF666666);
            emptyText.setTextSize(14);
            emptyText.setGravity(Gravity.CENTER);
            emptyText.setPadding(16, 40, 16, 40);
            emptyText.setBackgroundResource(R.drawable.bg_history_card);

            layoutRiwayatHome.addView(emptyText);

            if (cursor != null) {
                cursor.close();
            }

            return;
        }

        int nomor = 1;

        while (cursor.moveToNext() && nomor <= 5) {
            String namaDaun = cursor.getString(
                    cursor.getColumnIndexOrThrow(DatabaseHelper.COL_NAMA_DAUN)
            );

            float confidence = cursor.getFloat(
                    cursor.getColumnIndexOrThrow(DatabaseHelper.COL_CONFIDENCE)
            );

            String waktu = cursor.getString(
                    cursor.getColumnIndexOrThrow(DatabaseHelper.COL_WAKTU)
            );

            String imagePath = cursor.getString(
                    cursor.getColumnIndexOrThrow(DatabaseHelper.COL_IMAGE_PATH)
            );

            View itemView = LayoutInflater.from(this)
                    .inflate(R.layout.item_riwayat_home, layoutRiwayatHome, false);

            ImageView imgRiwayat = itemView.findViewById(R.id.imgRiwayat);
            TextView txtNamaDaun = itemView.findViewById(R.id.txtNamaDaunRiwayat);
            TextView txtConfidence = itemView.findViewById(R.id.txtConfidenceRiwayat);
            TextView txtWaktu = itemView.findViewById(R.id.txtWaktuRiwayat);

            txtNamaDaun.setText(nomor + ". " + namaDaun);
            txtConfidence.setText("Confidence: " + confidence + "%");
            txtWaktu.setText("Waktu: " + waktu);

            if (imagePath != null && !imagePath.isEmpty()) {
                imgRiwayat.setImageURI(Uri.parse(imagePath));
            } else {
                imgRiwayat.setImageResource(R.drawable.ic_photo_outline);
            }

            layoutRiwayatHome.addView(itemView);

            nomor++;
        }

        cursor.close();
    }
}