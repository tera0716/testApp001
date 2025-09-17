package com.example.testapp001;

import android.Manifest;
import android.annotation.SuppressLint; // ★追加
import android.content.pm.PackageManager;
import android.graphics.Point; // ★追加
import android.graphics.Rect; // ★追加
import android.media.Image; // ★追加
import android.os.Bundle;
import android.util.Log;
import android.util.Size; // ★追加
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull; // ★追加
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis; // ★追加
import androidx.camera.core.ImageProxy; // ★追加
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.tasks.OnCompleteListener; // ★追加
import com.google.android.gms.tasks.OnFailureListener; // ★追加
import com.google.android.gms.tasks.OnSuccessListener; // ★追加
import com.google.android.gms.tasks.Task; // ★追加
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner; // ★追加
import com.google.mlkit.vision.barcode.BarcodeScannerOptions; // ★追加
import com.google.mlkit.vision.barcode.BarcodeScanning; // ★追加
import com.google.mlkit.vision.barcode.common.Barcode; // ★追加
import com.google.mlkit.vision.common.InputImage; // ★追加

import java.util.List; // ★追加
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private TextView barcodeResultTextView;
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    Toast.makeText(this, R.string.camera_permission_granted, Toast.LENGTH_SHORT).show();
                    startCamera();
                } else {
                    Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
                }
            });

    private ExecutorService cameraExecutor;
    private static final String TAG = "MainActivity";
    private BarcodeScanner barcodeScanner; // ★追加: バーコードスキャナのインスタンス

    // ▼▼▼ 以下を追加 ▼▼▼
    private long lastUiUpdateTimeMillis = 0; // 最後にUIを更新した時刻
    private static final long UI_UPDATE_INTERVAL_MILLIS = 2000; // UI更新間隔 (例: 2秒)
    // ▲▲▲ ここまで追加 ▲▲▲

    // ▼▼▼ 以下を追加 ▼▼▼
    private String lastDisplayedBarcodeValue = null; // 最後にUIに表示したバーコードの値
    // ▲▲▲ ここまで追加 ▲▲▲


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        barcodeResultTextView = findViewById(R.id.barcodeResultTextView);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        requestCameraPermission();
        cameraExecutor = Executors.newSingleThreadExecutor();

        // ★追加: バーコードスキャナの初期化
        BarcodeScannerOptions options =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_QR_CODE,
                                Barcode.FORMAT_AZTEC,
                                Barcode.FORMAT_CODE_128, // 一般的なバーコード
                                Barcode.FORMAT_EAN_13,   // 商品JANコード(13桁)
                                Barcode.FORMAT_EAN_8,    // 商品JANコード(8桁)
                                Barcode.FORMAT_UPC_A,    // アメリカの一般的な商品コード
                                Barcode.FORMAT_UPC_E,
                                Barcode.FORMAT_CODE_39,
                                Barcode.FORMAT_CODE_93,
                                Barcode.FORMAT_CODABAR,
                                Barcode.FORMAT_DATA_MATRIX,
                                Barcode.FORMAT_ITF,      // ITFコード(段ボール等)
                                Barcode.FORMAT_PDF417
                        ) // 必要に応じて他のフォーマットも追加
                        .build();
        barcodeScanner = BarcodeScanning.getClient(options);
    }

    private void requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.camera_permission_already_granted, Toast.LENGTH_SHORT).show();
            startCamera();
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            Toast.makeText(this, R.string.camera_permission_rationale, Toast.LENGTH_LONG).show();
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @SuppressLint("UnsafeOptInUsageError") // ★追加: ImageProxy.getImage() に必要
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                // ★追加: ImageAnalysis ユースケースの設定
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        // ターゲット解像度を設定 (任意、デバイスやパフォーマンスに応じて調整)
                        // 大きすぎるとパフォーマンスに影響し、小さすぎると認識精度が落ちる可能性
                        .setTargetResolution(new Size(1280, 720)) // 例: 720p
                        // バックプレッシャー戦略: 最新の画像のみを処理する
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                // ★追加: ImageAnalysis のアナライザーを設定
                imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy imageProxy) {
                        Image mediaImage = imageProxy.getImage();
                        if (mediaImage != null) {
                            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                            processBarcode(image, imageProxy); // ★変更: imageProxyも渡す
                        } else {
                            imageProxy.close(); // ★重要: mediaImageがnullでも必ずcloseする
                        }
                    }
                });

                cameraProvider.unbindAll();
                // ★変更: imageAnalysis を bindToLifecycle に追加
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis // ★追加
                );

                barcodeResultTextView.setText(R.string.scan_barcode_prompt);
                Log.d(TAG, "Camera preview and analysis started successfully.");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
                Toast.makeText(this, "カメラの起動に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
                barcodeResultTextView.setText("カメラ起動エラー");
            } catch (Exception e) {
                Log.e(TAG, "An unexpected error occurred during camera start", e);
                Toast.makeText(this, "予期せぬエラーが発生しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
                barcodeResultTextView.setText("予期せぬエラー");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ★追加: バーコード処理メソッド
    // 提案した修正案の processBarcode メソッドの開始
    private void processBarcode(InputImage image, ImageProxy imageProxy) {
        barcodeScanner.process(image)
                .addOnSuccessListener(new OnSuccessListener<List<Barcode>>() {
                    @Override
                    public void onSuccess(List<Barcode> barcodes) {
                        if (barcodes.isEmpty()) {
                            // (修正案の isEmpty の中身)
                            if (lastDisplayedBarcodeValue != null) {
                                Log.d(TAG, "No barcode detected. Resetting lastDisplayedBarcodeValue.");
                                lastDisplayedBarcodeValue = null;
                            }
                            return;
                        }

                        // (修正案のバーコードが検出された場合の中身)
                        Barcode firstBarcode = barcodes.get(0);
                        String displayValue = firstBarcode.getDisplayValue();

                        if (displayValue != null && displayValue.equals(lastDisplayedBarcodeValue)) {
                            return;
                        }

                        long currentTimeMillis = System.currentTimeMillis();
                        if (currentTimeMillis - lastUiUpdateTimeMillis < UI_UPDATE_INTERVAL_MILLIS) {
                            return;
                        }

                        // (修正案のUI更新処理)
                        String rawValue = firstBarcode.getRawValue();
                        int valueType = firstBarcode.getValueType();
                        Log.d(TAG, "Processing for UI (New or after interval): Raw Value = " + rawValue + ", Display Value = " + displayValue + ", Type = " + valueTypeToString(valueType));
                        lastUiUpdateTimeMillis = currentTimeMillis;
                        lastDisplayedBarcodeValue = displayValue;
                        runOnUiThread(() -> {
                            String formattedResult = getString(R.string.barcode_scan_result_format, displayValue);
                            barcodeResultTextView.setText(formattedResult);
                        });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "Barcode scanning failed", e);
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "バーコードスキャン失敗: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        });
                    }
                })
                .addOnCompleteListener(new OnCompleteListener<List<Barcode>>() {
                    @Override
                    public void onComplete(@NonNull Task<List<Barcode>> task) {
                        imageProxy.close();
                    }
                });
    }    private String valueTypeToString(int valueType) {
        switch (valueType) {
            case Barcode.TYPE_CONTACT_INFO: return "CONTACT_INFO";
            case Barcode.TYPE_EMAIL: return "EMAIL";
            case Barcode.TYPE_ISBN: return "ISBN";
            case Barcode.TYPE_PHONE: return "PHONE";
            case Barcode.TYPE_PRODUCT: return "PRODUCT";
            case Barcode.TYPE_SMS: return "SMS";
            case Barcode.TYPE_TEXT: return "TEXT";
            case Barcode.TYPE_URL: return "URL";
            case Barcode.TYPE_WIFI: return "WIFI";
            case Barcode.TYPE_GEO: return "GEO";
            case Barcode.TYPE_CALENDAR_EVENT: return "CALENDAR_EVENT";
            case Barcode.TYPE_DRIVER_LICENSE: return "DRIVER_LICENSE";
            default: return "UNKNOWN_TYPE";
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        // ★追加: スキャナのリソースを解放 (任意だが推奨)
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
    }
}