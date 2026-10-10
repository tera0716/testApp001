package com.example.testapp001;

import android.os.Bundle;
import android.text.Editable;
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
import java.util.Date;
import java.util.Locale;

/**
 * 読む前に「部署・棚卸日・棚名」を決める画面。
 *
 * <p>★ 棚卸日を間違えると、読んでも Web の版と一致せずどこにも出てこない。
 * Access 側で実際に何度も起きている事故なので、
 * <b>今日から大きく離れていたら画面上で注意を出す</b>。
 */
public class SetupActivity extends AppCompatActivity {

    /** 今日からこれ以上離れていたら注意する日数 */
    private static final int WARN_DAYS = 30;

    private EditText inPst;
    private EditText inYmd;
    private EditText inTana;
    private TextView status;

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

        inPst = findViewById(R.id.in_pst);
        inYmd = findViewById(R.id.in_ymd);
        inTana = findViewById(R.id.in_tana);
        status = findViewById(R.id.setup_status);
        Button today = findViewById(R.id.btn_today);
        Button save = findViewById(R.id.btn_save);

        ScanSetting s = ScanSetting.load(this);
        if (s.pst > 0) {
            inPst.setText(String.valueOf(s.pst));
        }
        inYmd.setText(s.ymd);
        inTana.setText(s.tana);

        today.setOnClickListener(v -> {
            inYmd.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(new Date()));
            refresh();
        });

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
        inPst.addTextChangedListener(w);
        inYmd.addTextChangedListener(w);
        inTana.addTextChangedListener(w);

        save.setOnClickListener(v -> doSave());
        refresh();
    }

    /** 入力から設定を作る（保存はしない） */
    private ScanSetting current() {
        ScanSetting s = new ScanSetting();
        String p = inPst.getText().toString().trim();
        try {
            s.pst = p.isEmpty() ? 0 : Integer.parseInt(p);
        } catch (NumberFormatException e) {
            s.pst = 0;
        }
        s.ymd = inYmd.getText().toString().trim();
        s.tana = inTana.getText().toString().trim();
        return s;
    }

    /** 入力のたびに状態を出す */
    private void refresh() {
        ScanSetting s = current();
        StringBuilder sb = new StringBuilder();

        if (s.pst <= 0) {
            sb.append(getString(R.string.setup_err_pst)).append('\n');
        }
        if (!ScanSetting.isValidYmd(s.ymd)) {
            sb.append(getString(R.string.setup_err_ymd)).append('\n');
        } else {
            long days = daysFromToday(s.ymd);
            if (Math.abs(days) > WARN_DAYS) {
                sb.append(getString(R.string.setup_warn_ymd, days)).append('\n');
            }
        }
        if (s.tana.isEmpty()) {
            sb.append(getString(R.string.setup_err_tana)).append('\n');
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
