package com.example.testapp001;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 端末に溜まっている読み取りの一覧。
 *
 * <p>できること
 * <ul>
 *   <li>件数（全部／未送信）を見る</li>
 *   <li>未送信だけに絞る</li>
 *   <li>1件を長押しして消す（読み間違いを取り消す）</li>
 *   <li><b>タグ番号を改行区切りでコピー</b>
 *       … 送信の仕組みができる前でも、Web の「一括入力」に貼れば登録できる</li>
 * </ul>
 */
public class ScanListActivity extends AppCompatActivity {

    /** 画面に出す上限 */
    private static final int MAX_ROWS = 500;

    private TextView summary;
    private ListView listView;
    private Button btnFilter;
    private ScanStore store;
    private boolean unsentOnly = false;
    private List<ScanStore.Row> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_scan_list);
        setTitle(R.string.list_title);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.list_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        summary = findViewById(R.id.list_summary);
        listView = findViewById(R.id.list_view);
        btnFilter = findViewById(R.id.btn_filter);
        Button btnCopy = findViewById(R.id.btn_copy);

        store = new ScanStore(this);

        btnFilter.setOnClickListener(v -> {
            unsentOnly = !unsentOnly;
            refresh();
        });
        btnCopy.setOnClickListener(v -> copyTags());
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(pos);
            return true;
        });

        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (store != null) {
            store.close();
        }
    }

    private void refresh() {
        rows = store.list(unsentOnly, MAX_ROWS);
        int all = store.countAll();
        int unsent = store.countUnsent();

        summary.setText(getString(R.string.list_summary, all, unsent, rows.size()));
        btnFilter.setText(unsentOnly ? R.string.list_filter_all : R.string.list_filter_unsent);

        List<String> lines = new ArrayList<>();
        for (ScanStore.Row r : rows) {
            lines.add(getString(R.string.list_row,
                    r.tag, r.pst, r.ymd, r.tana, r.readAt,
                    r.isSent() ? getString(R.string.list_sent) : getString(R.string.list_unsent)));
        }
        listView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, lines));
    }

    /** タグ番号を改行区切りでコピーする（Web の一括入力に貼れる） */
    private void copyTags() {
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.list_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ScanStore.Row r : rows) {
            sb.append(r.tag).append('\n');
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, R.string.list_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("tags", sb.toString()));
        Toast.makeText(this, getString(R.string.list_copied, rows.size()), Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(int pos) {
        if (pos < 0 || pos >= rows.size()) {
            return;
        }
        final ScanStore.Row r = rows.get(pos);
        new AlertDialog.Builder(this)
                .setTitle(R.string.list_delete_title)
                .setMessage(getString(R.string.list_delete_msg, r.tag, r.tana, r.readAt))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_delete_ok, (d, w) -> {
                    store.delete(r.id);
                    Toast.makeText(this, getString(R.string.list_deleted, r.tag),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }
}
