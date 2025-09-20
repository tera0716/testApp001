package com.example.testapp001;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private PreviewView previewView;
    private TextView barcodeResultTextView;
    private Button urlConfirmButton;

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
    private BarcodeScanner barcodeScanner;

    // UI状態管理用の変数
    private String currentDisplayedValue = null; // 現在TextViewに表示している実際の文字列
    private String lastScannedValidUrl = null;   // 最後にスキャンされた有効な「純粋な」URL
    private boolean isUrlButtonVisible = false; // ボタンが表示されるべき状態か (true=VISIBLE, false=INVISIBLE)
    private long lastTextUpdateTimeMillis = 0;   // TextViewの最終更新時刻
    private static final long TEXT_UPDATE_INTERVAL_MILLIS = 1000; // TextViewの更新間隔 (ミリ秒)


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this); // 必要に応じて
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        barcodeResultTextView = findViewById(R.id.barcodeResultTextView);
        urlConfirmButton = findViewById(R.id.url_confirm_button);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // ボタンの初期状態設定とクリックリスナー
        // XML側で android:visibility="invisible" に設定されている前提
        urlConfirmButton.setVisibility(View.INVISIBLE);
        isUrlButtonVisible = false;

        urlConfirmButton.setOnClickListener(v -> {
            if (lastScannedValidUrl != null && !lastScannedValidUrl.isEmpty()) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(lastScannedValidUrl));
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "対応するアプリが見つかりません。", Toast.LENGTH_SHORT).show();
                } catch (Exception e) { // Uri.parseで発生しうる例外もキャッチ (例: 不正なURL形式)
                    Toast.makeText(MainActivity.this, "URLの形式が正しくありません。", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Error parsing or viewing URL: " + lastScannedValidUrl, e);
                }
            } else {
                Toast.makeText(MainActivity.this, "開けるURLがありません。", Toast.LENGTH_SHORT).show();
            }
        });

        requestCameraPermission();
        cameraExecutor = Executors.newSingleThreadExecutor();

        BarcodeScannerOptions options =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_QR_CODE, Barcode.FORMAT_AZTEC,
                                Barcode.FORMAT_CODE_128, Barcode.FORMAT_EAN_13,
                                Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_39,
                                Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODABAR,
                                Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_ITF,
                                Barcode.FORMAT_PDF417)
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

    @SuppressLint("UnsafeOptInUsageError")
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> { // ラムダ式で Analyzer を簡潔に
                    Image mediaImage = imageProxy.getImage();
                    if (mediaImage != null) {
                        InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                        processBarcode(image, imageProxy);
                    } else {
                        // mediaImageがnullでも、下流のonCompleteでimageProxy.close()が呼ばれるようにする
                        // ここでcloseするとonCompleteが呼ばれない可能性があるため、何もしないか、
                        // もし頻発するならログだけ出すなど。ただし、通常はnullにならないはず。
                        // Log.w(TAG, "MediaImage was null in analyzer.");
                        imageProxy.close(); // やはりここで閉じておく方が安全かもしれない
                    }
                });

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                        this,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );

                // カメラ起動時のUI初期化
                runOnUiThread(() -> {
                    barcodeResultTextView.setText(R.string.scan_barcode_prompt);
                    currentDisplayedValue = getString(R.string.scan_barcode_prompt);
                    lastScannedValidUrl = null;
                    // isUrlButtonVisible は onCreate で false になっているので、
                    // urlConfirmButton.setVisibility(View.INVISIBLE); も onCreate で設定済み
                    // 再度呼ぶ必要は薄いが、念のため呼んでも害はない
                    if (isUrlButtonVisible) { // 万が一trueになっていたら
                        urlConfirmButton.setVisibility(View.INVISIBLE);
                        isUrlButtonVisible = false;
                    }
                });
                Log.d(TAG, "Camera preview and analysis started successfully.");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "カメラの起動に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    barcodeResultTextView.setText("カメラ起動エラー");
                    currentDisplayedValue = "カメラ起動エラー";
                    lastScannedValidUrl = null;
                    if (urlConfirmButton != null) urlConfirmButton.setVisibility(View.INVISIBLE);
                    isUrlButtonVisible = false;
                });
            } catch (Exception e) { // その他の予期せぬエラー
                Log.e(TAG, "An unexpected error occurred during camera start", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "予期せぬエラーが発生しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    barcodeResultTextView.setText("予期せぬエラー");
                    currentDisplayedValue = "予期せぬエラー";
                    lastScannedValidUrl = null;
                    if (urlConfirmButton != null) urlConfirmButton.setVisibility(View.INVISIBLE);
                    isUrlButtonVisible = false;
                });
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processBarcode(InputImage image, ImageProxy imageProxy) {
        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (barcodes.isEmpty()) {
                        if (lastScannedValidUrl != null) {
                            lastScannedValidUrl = null;
                            if (isUrlButtonVisible) {
                                runOnUiThread(() -> urlConfirmButton.setVisibility(View.INVISIBLE));
                                isUrlButtonVisible = false;
                            }
                        }
                        // プロンプトに戻すなどの処理はここで行わない (連続スキャン時にチラつくため)
                        // currentDisplayedValue は以前のまま
                        return;
                    }

                    Barcode firstBarcode = barcodes.get(0);
                    String rawValue = firstBarcode.getRawValue();
                    String displayValue = firstBarcode.getDisplayValue();

                    if (displayValue == null && rawValue == null) {
                        return; // 処理できる値がない
                    }

                    String valueToDisplay = (displayValue != null) ? displayValue : rawValue; // 表示にはdisplayValue優先

                    boolean isCurrentScanUrl = (firstBarcode.getValueType() == Barcode.TYPE_URL) ||
                            (rawValue != null && Patterns.WEB_URL.matcher(rawValue).matches()) ||
                            (displayValue != null && Patterns.WEB_URL.matcher(displayValue).matches());

                    long currentTimeMillis = System.currentTimeMillis();

                    // TextViewの更新ロジック
                    if (!valueToDisplay.equals(currentDisplayedValue) &&
                            (currentTimeMillis - lastTextUpdateTimeMillis > TEXT_UPDATE_INTERVAL_MILLIS ||
                                    currentDisplayedValue == null ||
                                    currentDisplayedValue.equals(getString(R.string.scan_barcode_prompt)))) {
                        runOnUiThread(() -> {
                            String formattedResult = getString(R.string.barcode_scan_result_format, valueToDisplay);
                            barcodeResultTextView.setText(formattedResult);
                        });
                        currentDisplayedValue = valueToDisplay;
                        lastTextUpdateTimeMillis = currentTimeMillis;
                    }

                    // ボタンの表示/非表示ロジック
                    if (isCurrentScanUrl) {
                        // URLの場合
                        // Intentで使う純粋なURLを lastScannedValidUrl にセット
                        // rawValue があればそれを優先 (より加工されていないデータのため)
                        lastScannedValidUrl = (rawValue != null && Patterns.WEB_URL.matcher(rawValue).matches()) ? rawValue : displayValue;

                        if (!isUrlButtonVisible) {
                            runOnUiThread(() -> urlConfirmButton.setVisibility(View.VISIBLE));
                            isUrlButtonVisible = true;
                        }
                    } else {
                        // URLではない場合
                        lastScannedValidUrl = null;
                        if (isUrlButtonVisible) {
                            runOnUiThread(() -> urlConfirmButton.setVisibility(View.INVISIBLE));
                            isUrlButtonVisible = false;
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Barcode scanning failed", e);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "バーコードスキャン失敗: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        lastScannedValidUrl = null;
                        if (isUrlButtonVisible) {
                            if (urlConfirmButton != null) urlConfirmButton.setVisibility(View.INVISIBLE);
                            isUrlButtonVisible = false;
                        }
                    });
                })
                .addOnCompleteListener(task -> {
                    // imageProxy.close() はここで行うのが CameraX の推奨
                    imageProxy.close();
                });
    }

    // valueTypeToString メソッドは、デバッグログ用などに残しておいても良いでしょう
    private String valueTypeToString(int valueType) {
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
            default: return "UNKNOWN_TYPE (" + valueType + ")";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (barcodeScanner != null) {
            barcodeScanner.close(); // リソース解放
        }
    }
}
