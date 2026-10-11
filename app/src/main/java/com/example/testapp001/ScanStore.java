package com.example.testapp001;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 読んだタグを端末の中に溜める。
 *
 * <p>★ 電波が無くても読み続けられることが目的。送信は別（電波が戻ったらまとめて送る）。
 *
 * <p>Room ではなく {@link SQLiteOpenHelper} を使っている。
 * ライブラリを足さずに済み、アノテーション処理も要らないため。
 *
 * <p>同じ棚（部署・棚卸日・棚名が同じ）で同じタグを2回読んだ場合は <b>入れない</b>。
 * 読み直しただけなので、入れてしまうと確定時に一意制約（部署×棚卸日×タグ番号）で落ちる。
 * ★ 別の棚にまたがる重複は意味があるので（現物の確認が必要）、そちらは入れる。
 */
public class ScanStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "tanaoroshi.db";
    private static final int DB_VERSION = 2;

    public static final String T = "scan";
    public static final String C_ID = "_id";
    public static final String C_TAG = "tag";        // 正規化したタグ番号
    public static final String C_RAW = "raw";        // 読み取った生の文字列（証拠として残す）
    public static final String C_PST = "pst";        // 部署コード
    public static final String C_YMD = "ymd";        // 棚卸日 YYYY-MM-DD
    public static final String C_TANA = "tana";      // 棚名
    public static final String C_READ_AT = "read_at";// 読んだ時刻
    public static final String C_SENT_AT = "sent_at";// 送った時刻（null なら未送信）
    public static final String C_CHK = "chk";        // 照会の判定（正常 / N_在庫 など）
    public static final String C_INFO = "info";      // 照会で分かった商品情報（1行）

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN);

    /** 1件の読み取り */
    public static class Row {
        public long id;
        public String tag;
        public String raw;
        public int pst;
        public String ymd;
        public String tana;
        public String readAt;
        public String sentAt;
        public String chk;
        public String info;

        public boolean isSent() {
            return !TextUtils.isEmpty(sentAt);
        }
    }

    /** 保存の結果 */
    public enum Result {
        /** 入れた */
        SAVED,
        /** 同じ棚で既に読んでいる（入れていない） */
        DUPLICATE_SAME_SHELF,
        /** タグとして読めない */
        NOT_A_TAG,
        /** 版（部署・棚卸日・棚名）が未設定 */
        NO_SETTING
    }

    public ScanStore(Context c) {
        super(c.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + " ("
                + C_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + C_TAG + " TEXT NOT NULL, "
                + C_RAW + " TEXT, "
                + C_PST + " INTEGER NOT NULL, "
                + C_YMD + " TEXT NOT NULL, "
                + C_TANA + " TEXT NOT NULL, "
                + C_READ_AT + " TEXT NOT NULL, "
                + C_SENT_AT + " TEXT, "
                + C_CHK + " TEXT, "
                + C_INFO + " TEXT)");
        // 同じ棚の重複を速く調べるため
        db.execSQL("CREATE INDEX idx_scan_shelf ON " + T
                + " (" + C_PST + ", " + C_YMD + ", " + C_TANA + ", " + C_TAG + ")");
        // 未送信を速く拾うため
        db.execSQL("CREATE INDEX idx_scan_sent ON " + T + " (" + C_SENT_AT + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            // 照会の結果を持てるようにする（既に溜めたものは空のまま）
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_CHK + " TEXT");
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN " + C_INFO + " TEXT");
        }
    }

    /** {@link #save} で入れた行の id（照会の結果を後から書き込むために使う） */
    private long lastInsertedId = -1;

    public long lastInsertedId() {
        return lastInsertedId;
    }

    /** 照会の結果を書き込む */
    public void setLookup(long id, String chk, String info) {
        if (id <= 0) {
            return;
        }
        ContentValues v = new ContentValues();
        v.put(C_CHK, chk);
        v.put(C_INFO, info);
        getWritableDatabase().update(T, v, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** いまの時刻の文字列 */
    public static String now() {
        return TS.format(new Date());
    }

    /**
     * 読んだタグを溜める。
     *
     * @param raw      読み取った生の文字列
     * @param setting  版（部署・棚卸日・棚名）
     * @return 保存の結果
     */
    public Result save(String raw, ScanSetting setting) {
        if (setting == null || !setting.isReady()) {
            return Result.NO_SETTING;
        }
        String tag = TagNormalizer.normalize(raw);
        if (tag == null) {
            return Result.NOT_A_TAG;
        }
        SQLiteDatabase db = getWritableDatabase();
        if (countInShelf(db, setting, tag) > 0) {
            return Result.DUPLICATE_SAME_SHELF;
        }
        ContentValues v = new ContentValues();
        v.put(C_TAG, tag);
        v.put(C_RAW, raw);
        v.put(C_PST, setting.pst);
        v.put(C_YMD, setting.ymd);
        v.put(C_TANA, setting.tana);
        v.put(C_READ_AT, now());
        lastInsertedId = db.insert(T, null, v);
        return Result.SAVED;
    }

    private int countInShelf(SQLiteDatabase db, ScanSetting s, String tag) {
        Cursor c = db.rawQuery("SELECT count(*) FROM " + T
                        + " WHERE " + C_PST + "=? AND " + C_YMD + "=? AND " + C_TANA + "=? AND " + C_TAG + "=?",
                new String[]{String.valueOf(s.pst), s.ymd, s.tana, tag});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** この棚で読んだ件数 */
    public int countShelf(ScanSetting s) {
        if (s == null || !s.isReady()) {
            return 0;
        }
        Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM " + T
                        + " WHERE " + C_PST + "=? AND " + C_YMD + "=? AND " + C_TANA + "=?",
                new String[]{String.valueOf(s.pst), s.ymd, s.tana});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 全部の件数 */
    public int countAll() {
        return count(null, null);
    }

    /** 未送信の件数 */
    public int countUnsent() {
        return count(C_SENT_AT + " IS NULL", null);
    }

    private int count(String where, String[] args) {
        Cursor c = getReadableDatabase().rawQuery("SELECT count(*) FROM " + T
                + (where == null ? "" : " WHERE " + where), args);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 読んだ順（新しいものが先）で取り出す。
     *
     * @param unsentOnly true なら未送信だけ
     * @param limit      上限（0 以下なら全部）
     */
    public List<Row> list(boolean unsentOnly, int limit) {
        List<Row> out = new ArrayList<>();
        String sql = "SELECT " + C_ID + "," + C_TAG + "," + C_RAW + "," + C_PST + ","
                + C_YMD + "," + C_TANA + "," + C_READ_AT + "," + C_SENT_AT + ","
                + C_CHK + "," + C_INFO
                + " FROM " + T
                + (unsentOnly ? " WHERE " + C_SENT_AT + " IS NULL" : "")
                + " ORDER BY " + C_ID + " DESC"
                + (limit > 0 ? " LIMIT " + limit : "");
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getLong(0);
                r.tag = c.getString(1);
                r.raw = c.getString(2);
                r.pst = c.getInt(3);
                r.ymd = c.getString(4);
                r.tana = c.getString(5);
                r.readAt = c.getString(6);
                r.sentAt = c.getString(7);
                r.chk = c.getString(8);
                r.info = c.getString(9);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 未送信のかたまり（棚ごと）。送信はこの単位で行う */
    public static class Shelf {
        public int pst;
        public String ymd;
        public String tana;
        public int cnt;
        public List<Long> ids = new ArrayList<>();
        public List<String> tags = new ArrayList<>();

        public String label() {
            return "部署" + pst + " / " + ymd + " / " + tana + "　" + cnt + " 件";
        }

        /** サーバへ送る形（改行区切り） */
        public String tagsText() {
            StringBuilder b = new StringBuilder();
            for (String t : tags) {
                b.append(t).append(System.lineSeparator());
            }
            return b.toString();
        }
    }

    /** 未送信を棚ごとにまとめる */
    public List<Shelf> unsentShelves() {
        List<Shelf> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + C_ID + "," + C_TAG + "," + C_PST + "," + C_YMD + "," + C_TANA
                        + " FROM " + T + " WHERE " + C_SENT_AT + " IS NULL"
                        + " ORDER BY " + C_PST + "," + C_YMD + "," + C_TANA + "," + C_ID, null);
        try {
            Shelf cur = null;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String tag = c.getString(1);
                int pst = c.getInt(2);
                String ymd = c.getString(3);
                String tana = c.getString(4);
                if (cur == null || cur.pst != pst || !cur.ymd.equals(ymd) || !cur.tana.equals(tana)) {
                    cur = new Shelf();
                    cur.pst = pst;
                    cur.ymd = ymd;
                    cur.tana = tana;
                    out.add(cur);
                }
                cur.ids.add(id);
                cur.tags.add(tag);
                cur.cnt++;
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 送信済みを全部消す */
    public int deleteSent() {
        return getWritableDatabase().delete(T, C_SENT_AT + " IS NOT NULL", null);
    }

    /** この棚の分を全部消す */
    public int deleteShelf(ScanSetting s) {
        if (s == null || !s.isReady()) {
            return 0;
        }
        return getWritableDatabase().delete(T,
                C_PST + "=? AND " + C_YMD + "=? AND " + C_TANA + "=?",
                new String[]{String.valueOf(s.pst), s.ymd, s.tana});
    }

    /** 全部消す */
    public int deleteAll() {
        return getWritableDatabase().delete(T, null, null);
    }

    /** 1件消す（読み間違いを消すため） */
    public int delete(long id) {
        return getWritableDatabase().delete(T, C_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 送信できた分に印を付ける */
    public int markSent(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                in.append(',');
            }
            in.append(ids.get(i));
        }
        ContentValues v = new ContentValues();
        v.put(C_SENT_AT, now());
        return getWritableDatabase().update(T, v, C_ID + " IN (" + in + ")", null);
    }

    /** 送信済みの古い分を消す（溜めっぱなしにしないため） */
    public int deleteSentBefore(String ts) {
        return getWritableDatabase().delete(T,
                C_SENT_AT + " IS NOT NULL AND " + C_SENT_AT + " < ?", new String[]{ts});
    }
}
