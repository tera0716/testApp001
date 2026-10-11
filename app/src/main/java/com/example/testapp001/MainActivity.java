package com.example.testapp001;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.TorchState;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    /** TextView の表示を切り替える最短間隔 (ミリ秒)。連続スキャン時のチラつき防止。 */
    private static final long TEXT_UPDATE_INTERVAL_MILLIS = 1000;

    /** 改行。文字リテラルを直接書くと、生成の途中で実改行に化けることがあったため定数にしている */
    private static final String NL = System.lineSeparator();

    /**
     * ★ 同じタグ番号を何回続けて読めたら「読めた」とみなすか（2026-10-11）。
     *
     * <p>カメラは長いバーコードの一部だけを拾うことがある。
     * 実際に 9桁の 800299343 が 8002993 と 299343 に分かれて誤読された。
     * <b>分割誤読は毎回ちがう値になる</b>ので、同じ値が続けて出たときだけ採用すれば弾ける。
     * 正しく読めているときは毎フレーム同じ値が出るので、すぐ条件を満たす。
     */
    private static final int CONFIRM_COUNT = 2;

    /** 確認の有効時間。これを過ぎたら数え直す */
    private static final long CONFIRM_WINDOW_MS = 1500;

    private PreviewView previewView;
    private TextView barcodeResultTextView;
    private Button urlConfirmButton;

    // ===== 棚卸 =====
    private TextView scanHeaderTextView;
    private Button btnSetup;
    private Button btnList;
    private ScanStore scanStore;
    private ScanSetting scanSetting;
    /** 直前に保存したタグ。同じものを見続けている間は保存し直さない */
    private String lastSavedTag = null;
    private TextView goodsTextView;
    private Button btnLogin;
    /** 照会は通信するので、カメラとは別のスレッドで行う */
    private ExecutorService lookupExecutor;

    /** トーチ（暗い場所で読むため）。カメラを bind したときに受け取る */
    private Camera camera;
    private Button btnTorch;

    // 同じ値が続けて読めたかを数える（分割誤読よけ）
    private String pendingTag = null;
    private int pendingCount = 0;
    private long pendingAt = 0;

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

    // UI 状態管理用の変数。ML Kit のコールバックはメインスレッドで呼ばれるため、
    // これらのフィールドへのアクセスもすべてメインスレッド上で行われる。
    private String currentDisplayedValue = null; // 現在 TextView に表示している文字列
    private String lastScannedValidUrl = null;   // 最後に検出した http/https URL (検証済み)
    private boolean isUrlButtonVisible = false;  // ボタンの表示状態
    private long lastTextUpdateTimeMillis = 0;   // TextView の最終更新時刻

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        barcodeResultTextView = findViewById(R.id.barcodeResultTextView);
        urlConfirmButton = findViewById(R.id.url_confirm_button);

        // ===== 棚卸: 版と棚の表示・設定・一覧 =====
        scanHeaderTextView = findViewById(R.id.scanHeaderTextView);
        btnSetup = findViewById(R.id.btn_setup);
        btnList = findViewById(R.id.btn_list);
        scanStore = new ScanStore(this);
        btnSetup.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        btnList.setOnClickListener(v -> startActivity(new Intent(this, ScanListActivity.class)));
        goodsTextView = findViewById(R.id.goodsTextView);
        btnLogin = findViewById(R.id.btn_login);
        btnLogin.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
        lookupExecutor = Executors.newSingleThreadExecutor();

        btnTorch = findViewById(R.id.btn_torch);
        btnTorch.setEnabled(false);          // カメラが始まるまで押せない
        btnTorch.setOnClickListener(v -> toggleTorch());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        urlConfirmButton.setVisibility(View.INVISIBLE);
        isUrlButtonVisible = false;
        urlConfirmButton.setOnClickListener(v -> openScannedUrl());

        requestCameraPermission();
        cameraExecutor = Executors.newSingleThreadExecutor();

        // ★ 読み取る形式を絞る（2026-10-11）
        //
        //   値札は NW-7(Codabar) だけ。ところが CODE_39 / CODE_93 / CODE_128 / ITF /
        //   EAN / UPC はどれも「線を並べただけ」の形が似ているため、同時に見ていると
        //   長いバーコードの一部だけを別の形式として拾ってしまうことがある。
        //
        //   実際に 9桁の 800299343 が 8002993 と 299343 に分かれて誤読された。
        //   どちらもマスタに無い番号だが、見た目は「ありそうなタグ番号」なので危ない。
        //
        //   そこで線形コードは CODABAR だけにする。
        //   QR は形がまったく違うので混ざらない。既存の「URLを開く」機能のために残す。
        BarcodeScannerOptions options =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_CODABAR,
                                Barcode.FORMAT_QR_CODE)
                        .build();
        barcodeScanner = BarcodeScanning.getClient(options);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 設定画面から戻ったときに読み直す。棚が替わったら保存の抑止も解く。
        scanSetting = ScanSetting.load(this);
        lastSavedTag = null;
        pendingTag = null;
        pendingCount = 0;
        refreshScanHeader();
        refreshLoginButton();
    }

    /**
     * ★ ログインできているかを確かめてからボタンに出す。
     *
     * <p>Cookie の有無で判断してはいけない。
     * <b>ログインしていなくても PHPSESSID は発行される</b>ので、
     * 「ログイン済」と誤って出ていた（実際にそうなった）。
     */
    private void refreshLoginButton() {
        if (btnLogin == null) {
            return;
        }
        if (!ServerConfig.hasCookie(this)) {
            btnLogin.setText(R.string.scan_btn_login);
            return;
        }
        btnLogin.setText(R.string.scan_btn_login_check);
        lookupExecutor.execute(() -> {
            final boolean okNow = ScanPush.verifyLogin(MainActivity.this);
            runOnUiThread(() -> btnLogin.setText(
                    okNow ? R.string.scan_btn_logged_in : R.string.scan_btn_login));
        });
    }

    /** ヘッダー（版と棚、この棚で読んだ件数）と一覧ボタンの件数を出し直す */
    private void refreshScanHeader() {
        if (scanHeaderTextView == null) {
            return;
        }
        if (scanSetting == null || !scanSetting.isReady()) {
            scanHeaderTextView.setText(R.string.scan_header_no_setting);
        } else {
            scanHeaderTextView.setText(getString(R.string.scan_header,
                    scanSetting.label(), scanStore.countShelf(scanSetting)));
        }
        btnList.setText(getString(R.string.scan_btn_list, scanStore.countUnsent()));
    }

    /**
     * 読めたバーコードを棚卸として記録する。
     *
     * <p>★ 同じタグを見続けている間は保存し直さない（{@link #lastSavedTag}）。
     * 同じ棚での重複は {@link ScanStore} 側でも弾くが、毎フレーム DB を触らないため。
     */
    private void handleTanaoroshiScan(String raw) {
        String tag = TagNormalizer.normalize(raw);
        if (tag == null) {
            // タグではないもの（URL の QR など）は棚卸としては無視する。
            // 既存の「URLを開く」機能はそのまま働く。
            return;
        }
        if (tag.equals(lastSavedTag)) {
            return;
        }

        // ★ 同じ値が CONFIRM_COUNT 回続けて読めるまでは採用しない（分割誤読よけ）
        long now = System.currentTimeMillis();
        if (tag.equals(pendingTag) && now - pendingAt <= CONFIRM_WINDOW_MS) {
            pendingCount++;
        } else {
            pendingTag = tag;
            pendingCount = 1;
        }
        pendingAt = now;
        if (pendingCount < CONFIRM_COUNT) {
            if (goodsTextView != null) {
                goodsTextView.setTextColor(0xFF616161);
                goodsTextView.setText(getString(R.string.scan_confirming, tag));
            }
            return;
        }
        pendingTag = null;
        pendingCount = 0;
        lastSavedTag = tag;

        if (scanSetting == null || !scanSetting.isReady()) {
            Toast.makeText(this, R.string.scan_no_setting, Toast.LENGTH_SHORT).show();
            return;
        }

        ScanStore.Result r = scanStore.save(raw, scanSetting);
        switch (r) {
            case SAVED:
                feedback(true);
                Toast.makeText(this, getString(R.string.scan_saved,
                        tag, scanStore.countShelf(scanSetting)), Toast.LENGTH_SHORT).show();
                lookupGoods(tag, scanStore.lastInsertedId());
                break;
            case DUPLICATE_SAME_SHELF:
                feedback(false);
                Toast.makeText(this, getString(R.string.scan_dup, tag), Toast.LENGTH_SHORT).show();
                lookupGoods(tag, -1);   // 記録はしないが、何の商品かは出す
                break;
            case NO_SETTING:
                Toast.makeText(this, R.string.scan_no_setting, Toast.LENGTH_SHORT).show();
                break;
            case NOT_A_TAG:
            default:
                break;
        }
        refreshScanHeader();
    }

    /**
     * タグ番号をサーバに照会して、商品情報と判定を画面に出す。
     *
     * <p>★ 通信は別スレッド。電波が無くても読み取り自体は止めない。
     * <p>★ サーバ側は既存の口（?ajax=scan）をそのまま使う。1行も変えていない。
     */
    private void lookupGoods(final String tag, final long rowId) {
        if (goodsTextView == null || scanSetting == null || !scanSetting.isReady()) {
            return;
        }
        goodsTextView.setText(getString(R.string.lookup_checking, tag));
        final int pst = scanSetting.pst;
        final String ymd = scanSetting.ymd;
        lookupExecutor.execute(() -> {
            final ScanApi.Result r = ScanApi.lookup(MainActivity.this, pst, ymd, tag);
            runOnUiThread(() -> showLookup(tag, rowId, r));
        });
    }

    private void showLookup(String tag, long rowId, ScanApi.Result r) {
        if (goodsTextView == null) {
            return;
        }
        // 表示している間に次のタグを読んでいたら、古い結果は捨てる
        if (!tag.equals(lastSavedTag)) {
            return;
        }

        if (r.needLogin) {
            goodsTextView.setText(getString(R.string.lookup_need_login, tag));
            return;
        }
        if (r.offline) {
            goodsTextView.setText(getString(R.string.lookup_offline, tag));
            return;
        }

        StringBuilder b = new StringBuilder();
        b.append(getString(R.string.lookup_result, tag, r.chk));
        String goods = r.goodsLine();
        if (!goods.isEmpty()) {
            b.append(NL).append(goods);
        }
        if (r.dup != null) {
            b.append(NL).append(getString(R.string.lookup_dup, r.dup));
        }
        if (!r.suggest.isEmpty()) {
            b.append(NL).append(getString(R.string.lookup_suggest,
                    android.text.TextUtils.join(" ", r.suggest)));
        }
        goodsTextView.setText(b.toString());

        // 判定で色を変える（倉庫では文字を読む余裕がないことがある）
        int color;
        if ("ok".equals(r.level)) {
            color = 0xFF2E7D32;        // 緑: 正常
        } else if ("warn".equals(r.level)) {
            color = 0xFFE65100;        // 橙: 二度読み
        } else if ("error".equals(r.level)) {
            color = 0xFFC62828;        // 赤: マスタに無い・桁落ち
        } else {
            color = 0xFF1565C0;        // 青: 売上済など
        }
        goodsTextView.setTextColor(color);

        // 一覧でも見えるように、照会の結果を残す
        if (rowId > 0) {
            scanStore.setLookup(rowId, r.chk, goods);
        }
    }

    /**
     * トーチのボタンを使えるようにする。
     *
     * <p>★ 端末によってはライトが無い（前面カメラだけの機種など）。
     * {@code hasFlashUnit()} で確かめてから出す。
     * <p>実際の点灯状態は {@code getTorchState()} を見て合わせる。
     * 他のアプリや OS が消すことがあるので、押した回数で覚えない。
     */
    private void setupTorch() {
        if (camera == null || btnTorch == null) {
            return;
        }
        if (!camera.getCameraInfo().hasFlashUnit()) {
            btnTorch.setEnabled(false);
            btnTorch.setText(R.string.torch_none);
            return;
        }
        btnTorch.setEnabled(true);
        camera.getCameraInfo().getTorchState().observe(this, state -> {
            boolean on = (state != null && state == TorchState.ON);
            btnTorch.setText(on ? R.string.torch_off : R.string.torch_on);
        });
    }

    private void toggleTorch() {
        if (camera == null) {
            return;
        }
        if (!camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, R.string.torch_none, Toast.LENGTH_SHORT).show();
            return;
        }
        Integer st = camera.getCameraInfo().getTorchState().getValue();
        boolean on = (st != null && st == TorchState.ON);
        camera.getCameraControl().enableTorch(!on);
    }

    /** 読めたことを音と振動で伝える（倉庫では画面を見ていないことがある） */
    private void feedback(boolean ok) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(ok ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_NACK, 150);
            // 鳴らし終わってから片付ける
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(tg::release, 300);
        } catch (Exception e) {
            Log.w(TAG, "音を鳴らせませんでした", e);
        }
        try {
            // VIBRATOR_SERVICE は API 31 で非推奨になり、VibratorManager 経由になった
            Vibrator vib;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vib = (vm == null) ? null : vm.getDefaultVibrator();
            } else {
                vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vib != null && vib.hasVibrator()) {
                long ms = ok ? 60 : 200;
                vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception e) {
            Log.w(TAG, "振動させられませんでした", e);
        }
    }

    private void openScannedUrl() {
        if (lastScannedValidUrl == null || lastScannedValidUrl.isEmpty()) {
            Toast.makeText(this, R.string.error_no_url_available, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(lastScannedValidUrl)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_no_app_for_url, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "URL を開けませんでした: " + lastScannedValidUrl, e);
        }
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
            // 非同期コールバックのため、到達時には Activity が破棄済みの可能性がある。
            if (isFinishing() || isDestroyed()) {
                Log.d(TAG, "Activity は既に破棄されているためカメラ起動を中止します。");
                return;
            }
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setResolutionStrategy(new ResolutionStrategy(
                                new Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    Image mediaImage = imageProxy.getImage();
                    if (mediaImage == null) {
                        imageProxy.close();
                        return;
                    }
                    InputImage image = InputImage.fromMediaImage(
                            mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                    processBarcode(image, imageProxy);
                });

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
                setupTorch();

                barcodeResultTextView.setText(R.string.scan_barcode_prompt);
                currentDisplayedValue = getString(R.string.scan_barcode_prompt);
                lastScannedValidUrl = null;
                urlConfirmButton.setVisibility(View.INVISIBLE);
                isUrlButtonVisible = false;

                Log.d(TAG, "Camera preview and analysis started successfully.");

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
                showFatalError(getString(R.string.error_camera_start, String.valueOf(e.getMessage())),
                        getString(R.string.error_camera_start_short));
            } catch (Exception e) {
                Log.e(TAG, "An unexpected error occurred during camera start", e);
                showFatalError(getString(R.string.error_unexpected, String.valueOf(e.getMessage())),
                        getString(R.string.error_unexpected_short));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processBarcode(InputImage image, ImageProxy imageProxy) {
        barcodeScanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    if (barcodes.isEmpty()) {
                        // 1 フレーム見失っただけで表示を消すとボタンが点滅するため、
                        // 直前の結果は次のバーコードを検出するまで保持する。
                        return;
                    }

                    Barcode firstBarcode = barcodes.get(0);
                    String rawValue = firstBarcode.getRawValue();
                    String displayValue = firstBarcode.getDisplayValue();
                    if (displayValue == null && rawValue == null) {
                        return;
                    }

                    String valueToDisplay = (displayValue != null) ? displayValue : rawValue;
                    // ★ 棚卸の記録は表示の抑止より前に行う。
                    //   表示は 1 秒のあいだ更新を抑えているので、そこに入れると読み落とす。
                    handleTanaoroshiScan(valueToDisplay);
                    onBarcodeDetected(valueToDisplay, extractHttpUrl(firstBarcode));
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Barcode scanning failed", e);
                    Toast.makeText(this,
                            getString(R.string.error_barcode_scan_failed, String.valueOf(e.getMessage())),
                            Toast.LENGTH_SHORT).show();
                    updateUrlButton(null);
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * 検出したバーコードを UI に反映する。
     * TextView とボタンを必ず同じタイミングで更新し、表示内容が食い違わないようにする。
     */
    private void onBarcodeDetected(String valueToDisplay, String httpUrl) {
        if (valueToDisplay.equals(currentDisplayedValue)) {
            // 同じコードを見続けているだけ。ボタンの状態だけ同期する。
            updateUrlButton(httpUrl);
            return;
        }

        boolean showingPrompt = currentDisplayedValue == null
                || currentDisplayedValue.equals(getString(R.string.scan_barcode_prompt));
        long now = System.currentTimeMillis();
        if (!showingPrompt && now - lastTextUpdateTimeMillis <= TEXT_UPDATE_INTERVAL_MILLIS) {
            // 別のコードに切り替わった直後は無視し、表示の暴れを抑える。
            return;
        }

        currentDisplayedValue = valueToDisplay;
        lastTextUpdateTimeMillis = now;
        barcodeResultTextView.setText(getString(R.string.barcode_scan_result_format, valueToDisplay));
        updateUrlButton(httpUrl);
    }

    private void updateUrlButton(String httpUrl) {
        lastScannedValidUrl = httpUrl;
        boolean shouldBeVisible = (httpUrl != null);
        if (shouldBeVisible == isUrlButtonVisible) {
            return;
        }
        isUrlButtonVisible = shouldBeVisible;
        urlConfirmButton.setVisibility(shouldBeVisible ? View.VISIBLE : View.INVISIBLE);
    }

    /**
     * バーコードから http / https の URL だけを取り出す。
     * それ以外のスキーム (intent:, file:, javascript: など) やスキームのない文字列は
     * null を返し、ACTION_VIEW には渡さない。
     */
    private String extractHttpUrl(Barcode barcode) {
        String candidate = null;
        if (barcode.getValueType() == Barcode.TYPE_URL && barcode.getUrl() != null) {
            candidate = barcode.getUrl().getUrl();
        }
        if (candidate == null) {
            candidate = barcode.getRawValue();
        }
        if (candidate == null) {
            return null;
        }

        String trimmed = candidate.trim();
        try {
            Uri uri = Uri.parse(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            return trimmed;
        } catch (Exception e) {
            Log.w(TAG, "URL の解析に失敗しました: " + trimmed, e);
            return null;
        }
    }

    private void showFatalError(String toastMessage, String shortMessage) {
        Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
        barcodeResultTextView.setText(shortMessage);
        currentDisplayedValue = shortMessage;
        lastScannedValidUrl = null;
        urlConfirmButton.setVisibility(View.INVISIBLE);
        isUrlButtonVisible = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (barcodeScanner != null) {
            barcodeScanner.close();
        }
        if (lookupExecutor != null) {
            lookupExecutor.shutdown();
        }
        if (scanStore != null) {
            scanStore.close();
        }
    }
}
