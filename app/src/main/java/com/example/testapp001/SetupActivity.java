package com.example.testapp001;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 読む前に「版・棚名・担当」を決める画面。
 *
 * <p>★ <b>版は端末から作らない。</b>サーバにある版（部署 × 棚卸日）から選ぶ。
 * 端末で版を増やせてしまうと、Access 側との突き合わせが崩れるため。
 *
 * <p>★ 棚卸日が今日から大きく離れていたら注意を出す。
 * Access 側で1年ずれが何度も起きているため（引継ぎ書 A-68 / 5-2bq）。
 */
public class SetupActivity extends AppCompatActivity {

    /** 今日からこれ以上離れていたら注意する日数 */
    private static final int WARN_DAYS = 30;

    private TextView verNow;
    private EditText inTana;
    private EditText inTnt;
    private TextView status;
    private ExecutorService io;

    private int pst;
    private String ymd = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_setup);
        setTitle(R.string.setup_title);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setup_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        verNow = findViewById(R.id.ver_now);
        inTana = findViewById(R.id.in_tana);
        inTnt = findViewById(R.id.in_tnt);
        status = findViewById(R.id.setup_status);
        Button pick = findViewById(R.id.btn_pick_ver);
        Button save = findViewById(R.id.btn_save);
        io = Executors.newSingleThreadExecutor();

        ScanSetting s = ScanSetting.load(this);
        pst = s.pst;
        ymd = s.ymd;
        inTana.setText(s.tana);
        if (s.tnt > 0) {
            inTnt.setText(String.valueOf(s.tnt));
        }

        pick.setOnClickListener(v -> pickVersion());
        ((Button) findViewById(R.id.btn_pick_tana)).setOnClickListener(v -> pickTana());
        ((Button) findViewById(R.id.btn_pick_tnt)).setOnClickListener(v -> pickTnt());

        TextWatcher w = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b2, int c2) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b2, int c2) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                refresh();
            }
        };
        inTana.addTextChangedListener(w);
        inTnt.addTextChangedListener(w);

        save.setOnClickListener(v -> doSave());
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
    }

    /** サーバから版の一覧を取って選ばせる */
    private void pickVersion() {
        status.setText(R.string.setup_ver_loading);
        io.execute(() -> {
            final ScanPush.VersionsRes r = ScanPush.versions(SetupActivity.this);
            runOnUiThread(() -> showVersions(r));
        });
    }

    private void showVersions(ScanPush.VersionsRes r) {
        if (r.list.isEmpty()) {
            String m = r.needLogin ? getString(R.string.setup_ver_need_login)
                                   : getString(R.string.setup_ver_none, r.msg);
            status.setText(m);
            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            return;
        }
        final List<ScanPush.Version> list = r.list;
        // ★ 1行の羅列だと区別が付かないので2行で出す
        List<String> l1 = new ArrayList<>();
        List<String> l2 = new ArrayList<>();
        for (ScanPush.Version v : list) {
            l1.add(v.ymd + "　部署" + v.pst
                    + (TextUtils.isEmpty(v.pstNm) ? "" : "_" + v.pstNm));
            StringBuilder b2 = new StringBuilder();
            b2.append(v.src).append("　Web ").append(v.webCnt).append(" / Access ").append(v.accCnt);
            if (!TextUtils.isEmpty(v.tntNm)) {
                b2.append("　担当 ").append(v.tntNm);
            }
            l2.add(b2.toString());
        }
        String title = r.fromCache ? getString(R.string.setup_ver_pick_cache)
                                   : getString(R.string.setup_ver_pick);
        PickDialog.show(this, title, l1, l2, which -> {
            ScanPush.Version v = list.get(which);
            pst = v.pst;
            ymd = v.ymd;
            // 版の担当が分かっていて、まだ担当を入れていなければ初期値にする
            if (TextUtils.isEmpty(inTnt.getText().toString().trim())
                    && !TextUtils.isEmpty(v.verTnt)) {
                inTnt.setText(v.verTnt);
            }
            refresh();
        });
    }

    /** その版にすでにある棚から選ぶ（同じ棚に追加できる） */
    private void pickTana() {
        if (pst <= 0 || !ScanSetting.isValidYmd(ymd)) {
            Toast.makeText(this, R.string.setup_pick_need_ver, Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText(R.string.setup_loading);
        final int p = pst;
        final String y = ymd;
        io.execute(() -> {
            final ScanPush.ShelvesRes r = ScanPush.shelves(SetupActivity.this, p, y);
            runOnUiThread(() -> {
                if (r.list.isEmpty()) {
                    status.setText(R.string.setup_shelf_none);
                    Toast.makeText(SetupActivity.this,
                            r.ok ? getString(R.string.setup_shelf_none) : r.msg,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                List<String> l1 = new ArrayList<>();
                List<String> l2 = new ArrayList<>();
                for (ScanPush.Shelf sh : r.list) {
                    l1.add(sh.line1());
                    l2.add(sh.line2());
                }
                PickDialog.show(SetupActivity.this, getString(R.string.setup_pick_tana_title),
                        l1, l2, which -> {
                            ScanPush.Shelf sh = r.list.get(which);
                            if (sh.locked) {
                                // ★ 確定済みの棚には足せない。足した分だけ未確定で取り残される。
                                Toast.makeText(SetupActivity.this,
                                        getString(R.string.setup_shelf_locked, sh.tana),
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            inTana.setText(sh.tana);
                            // ★ 同じ棚に足すなら、担当もその棚に合わせておく
                            if (sh.tnt > 0) {
                                inTnt.setText(String.valueOf(sh.tnt));
                            }
                            Toast.makeText(SetupActivity.this,
                                    getString(R.string.setup_shelf_picked, sh.tana, sh.cnt),
                                    Toast.LENGTH_LONG).show();
                            refresh();
                        });
            });
        });
    }

    /** 担当者を一覧から選ぶ */
    private void pickTnt() {
        status.setText(R.string.setup_loading);
        final int p = pst;
        io.execute(() -> {
            final ScanPush.StaffRes r = ScanPush.staff(SetupActivity.this, p);
            runOnUiThread(() -> {
                if (r.list.isEmpty()) {
                    status.setText(getString(R.string.setup_staff_none, r.msg));
                    Toast.makeText(SetupActivity.this,
                            getString(R.string.setup_staff_none, r.msg), Toast.LENGTH_LONG).show();
                    return;
                }
                List<String> l1 = new ArrayList<>();
                List<String> l2 = new ArrayList<>();
                for (ScanPush.Staff st : r.list) {
                    l1.add(st.line1());
                    l2.add(st.line2());
                }
                PickDialog.show(SetupActivity.this, getString(R.string.setup_pick_tnt_title),
                        l1, l2, which -> {
                            inTnt.setText(String.valueOf(r.list.get(which).tnt));
                            refresh();
                        });
            });
        });
    }

    /** 入力から設定を作る（保存はしない） */
    private ScanSetting current() {
        ScanSetting s = new ScanSetting();
        s.pst = pst;
        s.ymd = ymd;
        s.tana = inTana.getText().toString().trim();
        String t = inTnt.getText().toString().trim();
        try {
            s.tnt = t.isEmpty() ? 0 : Integer.parseInt(t);
        } catch (NumberFormatException e) {
            s.tnt = 0;
        }
        return s;
    }

    private void refresh() {
        ScanSetting s = current();
        verNow.setText(s.pst > 0 && ScanSetting.isValidYmd(s.ymd)
                ? ("部署" + s.pst + " / " + s.ymd)
                : getString(R.string.setup_ver_unset));

        StringBuilder sb = new StringBuilder();
        if (s.pst <= 0 || !ScanSetting.isValidYmd(s.ymd)) {
            sb.append(getString(R.string.setup_err_ver)).append('\n');
        } else {
            long days = daysFromToday(s.ymd);
            if (Math.abs(days) > WARN_DAYS) {
                sb.append(getString(R.string.setup_warn_ymd, days)).append('\n');
            }
        }
        if (s.tana.isEmpty()) {
            sb.append(getString(R.string.setup_err_tana)).append('\n');
        }
        if (s.tnt <= 0) {
            sb.append(getString(R.string.setup_err_tnt)).append('\n');
        }

        if (sb.length() == 0) {
            ScanStore st = new ScanStore(this);
            int n = st.countShelf(s);
            st.close();
            sb.append(getString(R.string.setup_ok, s.label(), n));
        }
        status.setText(sb.toString().trim());
    }

    /** 棚卸日と今日の差（日） */
    private static long daysFromToday(String ymd) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN);
            Date d = f.parse(ymd);
            Date t = f.parse(f.format(new Date()));
            if (d == null || t == null) {
                return 0;
            }
            return (t.getTime() - d.getTime()) / (24L * 60 * 60 * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    private void doSave() {
        ScanSetting s = current();
        if (!s.isReady()) {
            Toast.makeText(this, R.string.setup_cant_save, Toast.LENGTH_LONG).show();
            return;
        }
        s.save(this);
        Toast.makeText(this, getString(R.string.setup_saved, s.label()), Toast.LENGTH_SHORT).show();
        finish();
    }
}
