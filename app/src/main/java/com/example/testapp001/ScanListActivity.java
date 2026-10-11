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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 端末に溜まっている読み取りの一覧。
 *
 * <ul>
 *   <li><b>送信</b> … 未送信を<b>棚ごとに</b>まとめてサーバへ送る。成功した分に印を付ける</li>
 *   <li>未送信だけに絞る</li>
 *   <li>タグ番号を改行区切りでコピー（Web の「一括入力」に貼れる）</li>
 *   <li><b>まとめて消す</b> … 送信済みだけ／この棚だけ／全部</li>
 *   <li>1件を長押しして消す（読み間違いの取り消し）</li>
 * </ul>
 *
 * <p>★ 送信は棚ごとに分ける。サーバ側が「バッチ = 1つの棚」で扱うため。
 * <p>★ 送れなかった分は未送信のまま残す。消さない。
 */
public class ScanListActivity extends AppCompatActivity {

    /** 画面に出す上限 */
    private static final int MAX_ROWS = 500;

    private TextView summary;
    private ListView listView;
    private Button btnFilter;
    private Button btnSend;
    private ScanStore store;
    private ExecutorService io;
    private boolean unsentOnly = false;
    private boolean sending = false;
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
        btnSend = findViewById(R.id.btn_send);
        Button btnCopy = findViewById(R.id.btn_copy);
        Button btnClear = findViewById(R.id.btn_clear);

        store = new ScanStore(this);
        io = Executors.newSingleThreadExecutor();

        btnSend.setOnClickListener(v -> confirmSend());
        btnFilter.setOnClickListener(v -> {
            unsentOnly = !unsentOnly;
            refresh();
        });
        btnCopy.setOnClickListener(v -> copyTags());
        btnClear.setOnClickListener(v -> askClear());
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(pos);
            return true;
        });

        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
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
        btnSend.setEnabled(!sending && unsent > 0);
        btnSend.setText(sending ? getString(R.string.list_sending)
                                : getString(R.string.list_send_n, unsent));

        List<String> lines = new ArrayList<>();
        for (ScanStore.Row r : rows) {
            String base = getString(R.string.list_row,
                    r.tag, r.pst, r.ymd, r.tana, r.readAt,
                    r.isSent() ? getString(R.string.list_sent) : getString(R.string.list_unsent));
            if (r.chk != null && !r.chk.isEmpty()) {
                base = base + "\n　" + r.chk + (r.info == null || r.info.isEmpty()
                        ? "" : "　" + r.info.replace("\n", " / "));
            }
            lines.add(base);
        }
        listView.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, lines));
    }

    // ------------------------------------------------------------------ 送信
    private void confirmSend() {
        final List<ScanStore.Shelf> shelves = store.unsentShelves();
        if (shelves.isEmpty()) {
            Toast.makeText(this, R.string.list_nothing_unsent, Toast.LENGTH_SHORT).show();
            return;
        }
        ScanSetting s = ScanSetting.load(this);
        if (s.tnt <= 0) {
            Toast.makeText(this, R.string.list_need_tnt, Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder b = new StringBuilder();
        int total = 0;
        for (ScanStore.Shelf sh : shelves) {
            b.append('・').append(sh.label()).append('\n');
            total += sh.cnt;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.list_send_title, total))
                .setMessage(b.toString().trim() + "\n\n" + getString(R.string.list_send_note))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_send_ok, (d, w) -> doSend(shelves, s.tnt))
                .show();
    }

    private void doSend(final List<ScanStore.Shelf> shelves, final int tnt) {
        sending = true;
        refresh();
        io.execute(() -> {
            final StringBuilder log = new StringBuilder();
            int okShelf = 0, okRows = 0, ngShelf = 0;
            boolean needLogin = false, offline = false;

            for (ScanStore.Shelf sh : shelves) {
                ScanPush.PushRes r = ScanPush.push(ScanListActivity.this,
                        sh.pst, sh.ymd, sh.tana, tnt, sh.tagsText());
                if (r.needLogin) {
                    needLogin = true;
                    log.append("・").append(sh.tana).append(" … ログインが切れました\n");
                    break;        // これ以上送っても同じ
                }
                if (r.offline) {
                    offline = true;
                    log.append("・").append(sh.tana).append(" … つながりません\n");
                    break;
                }
                if (r.ok) {
                    // ★ 送れた分だけ印を付ける。送れなかった分は未送信のまま残す。
                    store.markSent(sh.ids);
                    okShelf++;
                    okRows += sh.cnt;
                    log.append("・").append(sh.tana).append(" … ").append(r.msg).append('\n');
                } else {
                    ngShelf++;
                    log.append("・").append(sh.tana).append(" … ").append(r.msg).append('\n');
                }
            }

            final String body = log.toString().trim();
            final int fOkShelf = okShelf, fOkRows = okRows, fNgShelf = ngShelf;
            final boolean fNeedLogin = needLogin, fOffline = offline;
            runOnUiThread(() -> {
                sending = false;
                refresh();
                String head;
                if (fNeedLogin) {
                    head = getString(R.string.list_send_need_login);
                } else if (fOffline) {
                    head = getString(R.string.list_send_offline);
                } else {
                    head = getString(R.string.list_send_done, fOkShelf, fOkRows, fNgShelf);
                }
                new AlertDialog.Builder(ScanListActivity.this)
                        .setTitle(R.string.list_send_result)
                        .setMessage(head + "\n\n" + body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    // -------------------------------------------------------------- まとめて消す
    private void askClear() {
        ScanSetting s = ScanSetting.load(this);
        final int sent = store.countAll() - store.countUnsent();
        final int shelf = store.countShelf(s);
        final int all = store.countAll();

        final String[] items = {
                getString(R.string.list_clear_sent, sent),
                getString(R.string.list_clear_shelf, s.isReady() ? s.tana : "-", shelf),
                getString(R.string.list_clear_all, all),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.list_clear_title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        confirmClear(getString(R.string.list_clear_sent, sent), sent, 0);
                    } else if (which == 1) {
                        if (!s.isReady()) {
                            Toast.makeText(this, R.string.list_clear_no_setting, Toast.LENGTH_LONG).show();
                            return;
                        }
                        confirmClear(getString(R.string.list_clear_shelf, s.tana, shelf), shelf, 1);
                    } else {
                        confirmClear(getString(R.string.list_clear_all, all), all, 2);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 消す前に必ず確かめる。未送信が混ざるときは強く念を押す。 */
    private void confirmClear(String what, int n, final int kind) {
        if (n <= 0) {
            Toast.makeText(this, R.string.list_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = getString(R.string.list_clear_confirm, what, n);
        int unsent = store.countUnsent();
        if (kind != 0 && unsent > 0) {
            // ★ 未送信を消すと、読んだ記録がどこにも残らない
            msg += "\n\n" + getString(R.string.list_clear_warn_unsent, unsent);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.list_clear_title)
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_delete_ok, (d, w) -> {
                    int del;
                    if (kind == 0) {
                        del = store.deleteSent();
                    } else if (kind == 1) {
                        del = store.deleteShelf(ScanSetting.load(this));
                    } else {
                        del = store.deleteAll();
                    }
                    Toast.makeText(this, getString(R.string.list_cleared, del),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    // ------------------------------------------------------------------ その他
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
