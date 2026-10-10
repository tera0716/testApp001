package com.example.testapp001;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 棚卸アプリ（Web）のある場所と、ログインの Cookie。
 *
 * <p>★ 認証は「WebView で既存のログイン画面に入ってもらい、その Cookie を借りる」方式。
 * サーバ側に新しい入口を作らずに済む。
 *
 * <p>★ かならず https を使う。http だと通信が平文になる。
 */
public class ServerConfig {

    private static final String PREF = "server";
    private static final String K_BASE = "base";
    private static final String K_COOKIE = "cookie";

    /** 既定の置き場所（本番） */
    public static final String DEFAULT_BASE =
            "https://www.azalea-system.com/azalea_pos/kanri/test_dir/";

    /** 棚卸チェック画面（照会の口もここにある） */
    public static final String PAGE_MAIN = "20260221_skt_tmp_tbl_2.php";
    /** ログイン画面 */
    public static final String PAGE_LOGIN = "20240209_tana_login.php";

    private ServerConfig() {
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 置き場所。末尾は必ず / にする */
    public static String base(Context c) {
        String b = p(c).getString(K_BASE, DEFAULT_BASE);
        if (TextUtils.isEmpty(b)) {
            b = DEFAULT_BASE;
        }
        return b.endsWith("/") ? b : b + "/";
    }

    public static void setBase(Context c, String b) {
        p(c).edit().putString(K_BASE, b).apply();
    }

    /** 照会の口の URL */
    public static String scanUrl(Context c, int pst, String ymd, String tag) {
        return base(c) + PAGE_MAIN
                + "?ajax=scan"
                + "&pst=" + pst
                + "&ymd=" + android.net.Uri.encode(ymd)
                + "&tag=" + android.net.Uri.encode(tag);
    }

    public static String loginUrl(Context c) {
        return base(c) + PAGE_LOGIN + "?redirect=" + android.net.Uri.encode(PAGE_MAIN);
    }

    public static String mainUrl(Context c) {
        return base(c) + PAGE_MAIN;
    }

    /** ログイン済みの Cookie（無ければ空） */
    public static String cookie(Context c) {
        return p(c).getString(K_COOKIE, "");
    }

    public static void setCookie(Context c, String v) {
        p(c).edit().putString(K_COOKIE, v == null ? "" : v).apply();
    }

    public static boolean hasCookie(Context c) {
        return !TextUtils.isEmpty(cookie(c));
    }

    public static void clearCookie(Context c) {
        p(c).edit().remove(K_COOKIE).apply();
    }
}
