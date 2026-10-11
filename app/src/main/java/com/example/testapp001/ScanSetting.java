package com.example.testapp001;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * いま読んでいる「版と棚」。部署・棚卸日・棚名の3つ。
 *
 * <p>Web 側の「版」は（棚卸日, 部署, 担当）で、棚名はバッチ単位で持つ。
 * アプリでは読みながら棚を替えるので、<b>棚名もここに持たせる</b>。
 *
 * <p>★ 棚卸日は Web の版と一致していなければならない。
 * ここがずれると、せっかく読んでも棚卸チェックにも差異リストにも出てこない
 * （Access 側で実際に起きた事故。引継ぎ書 5-2bk / 5-2bq）。
 * 既定値を今日にしているのは、古い日付が残り続けるのを防ぐため。
 */
public class ScanSetting {

    private static final String PREF = "scan_setting";
    private static final String K_PST = "pst";
    private static final String K_YMD = "ymd";
    private static final String K_TANA = "tana";
    private static final String K_TNT = "tnt";

    private static final Pattern YMD = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");

    /** 部署コード。0 は未設定 */
    public int pst;
    /** 棚卸日 YYYY-MM-DD */
    public String ymd = "";
    /** 棚名 */
    public String tana = "";
    /** 棚卸をする人（担当者コード）。サーバへ送るときに要る */
    public int tnt;

    public static ScanSetting load(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ScanSetting s = new ScanSetting();
        s.pst = p.getInt(K_PST, 0);
        s.ymd = p.getString(K_YMD, "");
        s.tana = p.getString(K_TANA, "");
        s.tnt = p.getInt(K_TNT, 0);
        return s;
    }

    public void save(Context c) {
        c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(K_PST, pst)
                .putString(K_YMD, ymd == null ? "" : ymd.trim())
                .putString(K_TANA, tana == null ? "" : tana.trim())
                .putInt(K_TNT, tnt)
                .apply();
    }

    /** 3つとも入っているか */
    public boolean isReady() {
        return pst > 0 && isValidYmd(ymd) && !TextUtils.isEmpty(tana)
                && !tana.trim().isEmpty() && tnt > 0;
    }

    /** 画面に出す1行 */
    public String label() {
        if (!isReady()) {
            return "版・棚名・担当が未設定";
        }
        return "部署" + pst + " / " + ymd + " / " + tana + " / 担当" + tnt;
    }

    public static boolean isValidYmd(String s) {
        if (s == null) {
            return false;
        }
        Matcher m = YMD.matcher(s.trim());
        if (!m.matches()) {
            return false;
        }
        int mo = Integer.parseInt(m.group(2));
        int d = Integer.parseInt(m.group(3));
        return mo >= 1 && mo <= 12 && d >= 1 && d <= 31;
    }

    /** 棚卸日 YYYY-MM-DD を YYYYMMDD にする（送信用） */
    public String ymdCompact() {
        return isValidYmd(ymd) ? ymd.replace("-", "") : "";
    }
}
