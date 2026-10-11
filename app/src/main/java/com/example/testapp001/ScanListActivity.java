package com.example.testapp001;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 端末に溜まっている読み取り。<b>2段</b>で見る。
 *
 * <ol>
 *   <li><b>棚の一覧</b> … 部署 × 棚卸日 × 棚名 ごとに、件数・未送信・最後に読んだ時刻。
 *       押すと中身へ。長押しで「この棚を送信」「この棚を消す」</li>
 *   <li><b>棚の中身</b> … <b>判定ごとにまとめて</b>並べる。異常（正常でないもの）が上に来る</li>
 * </ol>
 *
 * <p>★ 部署・版・棚名を行ごとに繰り返さない。同じ値が並ぶと、
 * 肝心のタグ番号と判定が読みにくい。
 *
 * <p>★ 送信は<b>棚ごと</b>。サーバ側が「バッチ = 1つの棚」で扱うため。
 * 送れなかった分は未送信のまま残す。
 */
public class ScanListActivity extends AppCompatActivity {

    /** 棚の中身を一度に出す上限 */
    private static final int MAX_ROWS = 500;

    /** 表示の絞り込み */
    private static final int F_ALL = 0;
    private static final int F_UNSENT = 1;
    private static final int F_NG = 2;

    private TextView summary;
    private TextView hint;
    private ListView listView;
    private Button btnBack;
    private Button btnSend;
    private Button btnFilter;
    private Button btnCopy;
    private Button btnClear;

    private ScanStore store;
    private ExecutorService io;
    private boolean sending = false;
    private int filter = F_ALL;

    /** 開いている棚。null なら棚の一覧 */
    private ScanStore.Group open = null;

    private List<ScanStore.Group> groups = new ArrayList<>();
    private List<ScanStore.Row> items = new ArrayList<>();
    /** 画面の行番号 → 中身。見出しの行は null */
    private List<Object> slots = new ArrayList<>();

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
        hint = findViewById(R.id.list_hint);
        listView = findViewById(R.id.list_view);
        btnBack = findViewById(R.id.btn_back);
        btnSend = findViewById(R.id.btn_send);
        btnFilter = findViewById(R.id.btn_filter);
        btnCopy = findViewById(R.id.btn_copy);
        btnClear = findViewById(R.id.btn_clear);

        store = new ScanStore(this);
        io = Executors.newSingleThreadExecutor();

        btnBack.setOnClickListener(v -> {
            open = null;
            refresh();
        });
        btnSend.setOnClickListener(v -> {
            if (open == null) {
                confirmSendAll();
            } else {
                confirmSendShelf(open);
            }
        });
        btnFilter.setOnClickListener(v -> {
            filter = (filter + 1) % 3;
            refresh();
        });
        btnCopy.setOnClickListener(v -> copyTags());
        btnClear.setOnClickListener(v -> {
            if (open == null) {
                askClear();
            } else {
                confirmDeleteShelf(open);
            }
        });

        listView.setOnItemClickListener((p, v, pos, id) -> {
            Object o = slot(pos);
            if (o instanceof ScanStore.Group) {
                open = (ScanStore.Group) o;
                refresh();
            }
        });
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            Object o = slot(pos);
            if (o instanceof ScanStore.Group) {
                shelfMenu((ScanStore.Group) o);
                return true;
            }
            if (o instanceof ScanStore.Row) {
                rowMenu((ScanStore.Row) o);
                return true;
            }
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

    @Override
    public void onBackPressed() {
        if (open != null) {
            open = null;
            refresh();
            return;
        }
        super.onBackPressed();
    }

    private Object slot(int pos) {
        return (pos >= 0 && pos < slots.size()) ? slots.get(pos) : null;
    }

    // ------------------------------------------------------------------ 表示
    private void refresh() {
        if (open == null) {
            showGroups();
        } else {
            showItems();
        }
    }

    private String filterName() {
        if (filter == F_UNSENT) {
            return getString(R.string.list_filter_unsent_now);
        }
        if (filter == F_NG) {
            return getString(R.string.list_filter_ng_now);
        }
        return getString(R.string.list_filter_all_now);
    }

    /** 1段目: 棚の一覧 */
    private void showGroups() {
        groups = store.groups(filter == F_UNSENT);
        int all = store.countAll();
        int unsent = store.countUnsent();

        btnBack.setVisibility(View.GONE);
        hint.setText(R.string.list_hint_groups);
        summary.setText(getString(R.string.list_summary, all, unsent, groups.size()));
        btnFilter.setText(filterName());
        btnCopy.setText(R.string.list_copy);
        btnClear.setText(R.string.list_clear);
        btnSend.setEnabled(!sending && unsent > 0);
        btnSend.setText(sending ? getString(R.string.list_sending)
                                : getString(R.string.list_send_n, unsent));

        slots = new ArrayList<>();
        List<Map<String, String>> data = new ArrayList<>();
        for (ScanStore.Group g : groups) {
            Map<String, String> m = new HashMap<>();
            m.put("a", g.tana + "　" + g.total + " 件"
                    + (g.unsent > 0 ? "（未送信 " + g.unsent + "）" : ""));
            m.put("b", "部署" + g.pst + " / " + g.ymd
                    + "　最後 " + shortTime(g.last));
            data.add(m);
            slots.add(g);
        }
        setAdapter(data);
    }

    /** 2段目: 棚の中身。判定ごとにまとめる */
    private void showItems() {
        items = store.listShelf(open.pst, open.ymd, open.tana, filter == F_UNSENT, MAX_ROWS);
        if (filter == F_NG) {
            List<ScanStore.Row> ng = new ArrayList<>();
            for (ScanStore.Row r : items) {
                if (!isOk(r)) {
                    ng.add(r);
                }
            }
            items = ng;
        }

        btnBack.setVisibility(View.VISIBLE);
        hint.setText(R.string.list_hint_items);
        btnFilter.setText(filterName());
        btnCopy.setText(R.string.list_copy);
        btnClear.setText(R.string.list_clear_this);

        // この棚の内訳
        int tot = 0, uns = 0;
        for (ScanStore.Group g : store.groups(false)) {
            if (g.key().equals(open.key())) {
                tot = g.total;
                uns = g.unsent;
            }
        }
        summary.setText(getString(R.string.list_shelf_summary,
                open.pst, open.ymd, open.tana, tot, uns, items.size()));
        btnSend.setEnabled(!sending && uns > 0);
        btnSend.setText(sending ? getString(R.string.list_sending)
                                : getString(R.string.list_send_shelf, uns));

        // ★ 判定ごとにまとめる。異常（正常でないもの）を先に出す
        Map<String, List<ScanStore.Row>> by = new LinkedHashMap<>();
        for (ScanStore.Row r : items) {
            if (!isOk(r)) {
                put(by, chkName(r), r);
            }
        }
        for (ScanStore.Row r : items) {
            if (isOk(r)) {
                put(by, chkName(r), r);
            }
        }

        slots = new ArrayList<>();
        List<Map<String, String>> data = new ArrayList<>();
        for (Map.Entry<String, List<ScanStore.Row>> e : by.entrySet()) {
            Map<String, String> h = new HashMap<>();
            h.put("a", getString(R.string.list_chk_group, e.getKey(), e.getValue().size()));
            h.put("b", "");
            data.add(h);
            slots.add(null);
            for (ScanStore.Row r : e.getValue()) {
                Map<String, String> m = new HashMap<>();

                // 1行目: タグ番号 / ブランド・アイテム・品番・色・サイズ / 上代
                StringBuilder l1 = new StringBuilder(r.tag);
                if (r.info != null && !r.info.isEmpty()) {
                    l1.append("　").append(r.info);
                }
                if (r.jdi > 0) {
                    l1.append("　").append(String.format(Locale.JAPAN, "%,d", r.jdi));
                }
                m.put("a", l1.toString());

                // 2行目: 棚卸日時 / 送信の状態 / メモ
                StringBuilder l2 = new StringBuilder(dateTime(r.readAt));
                l2.append("　").append(r.isSent() ? getString(R.string.list_sent)
                                                  : getString(R.string.list_unsent));
                if (r.memo != null && !r.memo.trim().isEmpty()) {
                    l2.append("　✎ ").append(r.memo.trim());
                }
                m.put("b", l2.toString());

                data.add(m);
                slots.add(r);
            }
        }
        setAdapter(data);
    }

    private void setAdapter(List<Map<String, String>> data) {
        listView.setAdapter(new SimpleAdapter(this, data,
                android.R.layout.simple_list_item_2,
                new String[]{"a", "b"},
                new int[]{android.R.id.text1, android.R.id.text2}));
    }

    private static void put(Map<String, List<ScanStore.Row>> m, String k, ScanStore.Row r) {
        List<ScanStore.Row> l = m.get(k);
        if (l == null) {
            l = new ArrayList<>();
            m.put(k, l);
        }
        l.add(r);
    }

    /** 判定が「正常」か。照会していないものは判定なし扱い */
    private static boolean isOk(ScanStore.Row r) {
        return "正常".equals(r.chk);
    }

    private String chkName(ScanStore.Row r) {
        return (r.chk == null || r.chk.isEmpty()) ? getString(R.string.list_chk_none) : r.chk;
    }

    /** "2026-10-11 18:35:11" → "18:35" */
    private static String shortTime(String ts) {
        if (ts == null) {
            return "";
        }
        return ts.length() >= 16 ? ts.substring(11, 16) : ts;
    }

    /** "2026-10-11 18:35:11" → "10-11 18:35"（日付も見えるように） */
    private static String dateTime(String ts) {
        if (ts == null) {
            return "";
        }
        return ts.length() >= 16 ? ts.substring(5, 16) : ts;
    }

    // ------------------------------------------------------------------ 送信
    private void confirmSendAll() {
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
            b.append('・').append(sh.label());
            if (sh.memoCount() > 0) {
                b.append("（メモ ").append(sh.memoCount()).append("）");
            }
            b.append(System.lineSeparator());
            total += sh.cnt;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.list_send_title, total))
                .setMessage(b.toString().trim() + "\n\n" + getString(R.string.list_send_note))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_send_ok, (d, w) -> doSend(shelves, s.tnt))
                .show();
    }

    private void confirmSendShelf(ScanStore.Group g) {
        final ScanStore.Shelf sh = store.unsentShelf(g.pst, g.ymd, g.tana);
        if (sh == null) {
            Toast.makeText(this, R.string.list_nothing_unsent, Toast.LENGTH_SHORT).show();
            return;
        }
        ScanSetting s = ScanSetting.load(this);
        if (s.tnt <= 0) {
            Toast.makeText(this, R.string.list_need_tnt, Toast.LENGTH_LONG).show();
            return;
        }
        List<ScanStore.Shelf> one = new ArrayList<>();
        one.add(sh);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.list_send_title, sh.cnt))
                .setMessage(sh.label() + "\n\n" + getString(R.string.list_send_note))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_send_ok, (d, w) -> doSend(one, s.tnt))
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
                        sh.pst, sh.ymd, sh.tana, tnt, sh.tagsText(), sh.memosText());
                if (r.needLogin) {
                    needLogin = true;
                    log.append("・").append(sh.tana).append(" … ログインが切れました\n");
                    break;
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
                } else {
                    ngShelf++;
                }
                log.append("・").append(sh.tana).append(" … ").append(r.msg).append('\n');
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

    // ------------------------------------------------------------------ 棚の操作
    private void shelfMenu(final ScanStore.Group g) {
        final String[] items2 = {
                getString(R.string.list_shelf_open),
                getString(R.string.list_send_shelf, g.unsent),
                getString(R.string.list_clear_shelf, g.tana, g.total),
        };
        new AlertDialog.Builder(this)
                .setTitle(g.tana)
                .setItems(items2, (d, which) -> {
                    if (which == 0) {
                        open = g;
                        refresh();
                    } else if (which == 1) {
                        confirmSendShelf(g);
                    } else {
                        confirmDeleteShelf(g);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteShelf(final ScanStore.Group g) {
        String msg = getString(R.string.list_clear_confirm,
                getString(R.string.list_clear_shelf, g.tana, g.total), g.total);
        if (g.unsent > 0) {
            msg += "\n\n" + getString(R.string.list_clear_warn_unsent, g.unsent);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.list_clear_title)
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_delete_ok, (d, w) -> {
                    int n = store.deleteShelfKey(g.pst, g.ymd, g.tana);
                    Toast.makeText(this, getString(R.string.list_cleared, n),
                            Toast.LENGTH_SHORT).show();
                    if (open != null && open.key().equals(g.key())) {
                        open = null;
                    }
                    refresh();
                })
                .show();
    }

    // -------------------------------------------------------------- まとめて消す
    private void askClear() {
        final int sent = store.countAll() - store.countUnsent();
        final int all = store.countAll();
        final String[] items2 = {
                getString(R.string.list_clear_sent, sent),
                getString(R.string.list_clear_all, all),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.list_clear_title)
                .setItems(items2, (d, which) -> {
                    if (which == 0) {
                        confirmClear(getString(R.string.list_clear_sent, sent), sent, true);
                    } else {
                        confirmClear(getString(R.string.list_clear_all, all), all, false);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmClear(String what, int n, final boolean sentOnly) {
        if (n <= 0) {
            Toast.makeText(this, R.string.list_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        String msg = getString(R.string.list_clear_confirm, what, n);
        int unsent = store.countUnsent();
        if (!sentOnly && unsent > 0) {
            msg += "\n\n" + getString(R.string.list_clear_warn_unsent, unsent);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.list_clear_title)
                .setMessage(msg)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.list_delete_ok, (d, w) -> {
                    int del = sentOnly ? store.deleteSent() : store.deleteAll();
                    Toast.makeText(this, getString(R.string.list_cleared, del),
                            Toast.LENGTH_SHORT).show();
                    open = null;
                    refresh();
                })
                .show();
    }

    // ------------------------------------------------------------------ その他
    /** いま出ているタグ番号を改行区切りでコピー（Web の一括入力に貼れる） */
    private void copyTags() {
        List<String> tags = new ArrayList<>();
        if (open == null) {
            for (ScanStore.Group g : groups) {
                for (ScanStore.Row r : store.listShelf(g.pst, g.ymd, g.tana, false, 0)) {
                    tags.add(r.tag);
                }
            }
        } else {
            for (ScanStore.Row r : items) {
                tags.add(r.tag);
            }
        }
        if (tags.isEmpty()) {
            Toast.makeText(this, R.string.list_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String t : tags) {
            sb.append(t).append(System.lineSeparator());
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, R.string.list_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("tags", sb.toString()));
        Toast.makeText(this, getString(R.string.list_copied, tags.size()),
                Toast.LENGTH_SHORT).show();
    }

    /** 1件を長押ししたときの選択 */
    private void rowMenu(final ScanStore.Row r) {
        final String[] items2 = {
                getString(R.string.row_memo),
                getString(R.string.row_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(r.tag)
                .setItems(items2, (d, which) -> {
                    if (which == 0) {
                        editMemo(r);
                    } else {
                        confirmDeleteRow(r);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * メモを書く。
     *
     * <p>★ いまは<b>端末の中だけ</b>に残る。サーバへ運ぶ仕組みは別途。
     * 勘違いしないよう、画面にもその旨を出す。
     */
    private void editMemo(final ScanStore.Row r) {
        final EditText in = new EditText(this);
        in.setHint(R.string.row_memo_hint);
        in.setText(r.memo == null ? "" : r.memo);
        in.setSelection(in.getText().length());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.row_memo_title, r.tag))
                .setMessage(R.string.row_memo_note)
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.row_memo_save, (d, w) -> {
                    store.setMemo(r.id, in.getText().toString());
                    refresh();
                })
                .show();
    }

    private void confirmDeleteRow(final ScanStore.Row r) {
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
