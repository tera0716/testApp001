package com.example.testapp001;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 棚卸アプリ（Web）の照会の口を叩く。
 *
 * <pre>
 *   GET  …/20260221_skt_tmp_tbl_2.php?ajax=scan&amp;pst=3&amp;ymd=2026-10-11&amp;tag=14063118
 *   →  {"tag":"14063118","level":"ok","chk":"正常",
 *       "brd":"1259_ケイジュク","itm":"パンツ","hin":"ABC-123","col":"BLK","siz":"M",
 *       "jdi":38000,"dup":null,"suggest":[…]}
 * </pre>
 *
 * <p>★ サーバ側は一切変えていない。棚卸チェック画面がスキャン欄で使っている口をそのまま使う。
 * <p>★ 認証はログイン済みの Cookie を付けるだけ（{@link ServerConfig}）。
 * <p>★ 電波が無い・ログインが切れた場合は {@link Result#offline} / {@link Result#needLogin} を立てる。
 *    どちらでも端末への記録は続ける（溜めてから送る作りのため）。
 */
public class ScanApi {

    private static final String TAG = "ScanApi";
    private static final int TIMEOUT_MS = 6000;

    /** 照会の結果 */
    public static class Result {
        public String tag = "";
        /** ok / warn / info / error */
        public String level = "error";
        /** 判定（正常 / N_在庫 / N_売上済 / 該当なし（マスタに無い） など） */
        public String chk = "";
        public String brd = "";
        public String itm = "";
        public String hin = "";
        public String col = "";
        public String siz = "";
        public int jdi = 0;
        /** 同じ版で既に読まれていれば、その場所 */
        public String dup = null;
        /** 桁落ちの復元候補 */
        public List<String> suggest = new ArrayList<>();

        /** 電波が無い・サーバに届かない */
        public boolean offline = false;
        /** ログインが必要（Cookie が無い・切れた） */
        public boolean needLogin = false;

        /** 上代を「12,345」の形で（0 なら空） */
        public String jdiText() {
            return jdi > 0 ? String.format(java.util.Locale.JAPAN, "%,d", jdi) : "";
        }

        /** 商品情報（ブランド / アイテム / 品番 / 色 / サイズ）。上代は含めない */
        public String goodsLine() {
            StringBuilder b = new StringBuilder();
            if (!TextUtils.isEmpty(brd)) {
                b.append(brd);
            }
            if (!TextUtils.isEmpty(itm)) {
                if (b.length() > 0) {
                    b.append(" / ");
                }
                b.append(itm);
            }
            StringBuilder c = new StringBuilder();
            if (!TextUtils.isEmpty(hin)) {
                c.append(hin);
            }
            if (!TextUtils.isEmpty(col)) {
                c.append(c.length() > 0 ? " / " : "").append(col);
            }
            if (!TextUtils.isEmpty(siz)) {
                c.append(c.length() > 0 ? " / " : "").append(siz);
            }
            if (c.length() > 0) {
                if (b.length() > 0) {
                    b.append('\n');
                }
                b.append(c);
            }
            // ★ 上代は一覧の1行目に出すので、ここには入れない
            return b.toString();
        }

        /** 商品が見つかったか */
        public boolean found() {
            return !TextUtils.isEmpty(brd) || !TextUtils.isEmpty(hin) || !TextUtils.isEmpty(itm);
        }
    }

    private ScanApi() {
    }

    /**
     * 照会する。<b>通信するのでワーカースレッドから呼ぶこと。</b>
     */
    public static Result lookup(Context ctx, int pst, String ymd, String tag) {
        Result r = new Result();
        r.tag = tag;

        String cookie = ServerConfig.cookie(ctx);
        if (TextUtils.isEmpty(cookie)) {
            r.needLogin = true;
            r.chk = "ログインが必要です";
            return r;
        }

        HttpURLConnection con = null;
        try {
            URL u = new URL(ServerConfig.scanUrl(ctx, pst, ymd, tag));
            con = (HttpURLConnection) u.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(false);   // ログイン画面への転送を「切れた」と判断するため
            con.setRequestProperty("Cookie", cookie);
            con.setRequestProperty("Accept", "application/json");

            int code = con.getResponseCode();
            if (code == 302 || code == 301) {
                // セッションが切れると、ログイン画面へ転送される
                r.needLogin = true;
                r.chk = "ログインが切れました";
                return r;
            }
            if (code != 200) {
                r.offline = true;
                r.chk = "サーバの応答 " + code;
                return r;
            }

            String body = readAll(con.getInputStream());
            parse(body, r);
            return r;

        } catch (Exception e) {
            Log.w(TAG, "照会できませんでした: " + tag, e);
            r.offline = true;
            r.chk = "つながりません";
            return r;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    static void parse(String body, Result r) {
        try {
            JSONObject j = new JSONObject(body);
            r.tag = j.optString("tag", r.tag);
            r.level = j.optString("level", "error");
            r.chk = j.optString("chk", "");
            r.brd = j.optString("brd", "");
            r.itm = j.optString("itm", "");
            r.hin = j.optString("hin", "");
            r.col = j.optString("col", "");
            r.siz = j.optString("siz", "");
            r.jdi = j.optInt("jdi", 0);
            String dup = j.isNull("dup") ? null : j.optString("dup", null);
            r.dup = TextUtils.isEmpty(dup) ? null : dup;

            JSONArray sg = j.optJSONArray("suggest");
            if (sg != null) {
                for (int i = 0; i < sg.length(); i++) {
                    JSONObject o = sg.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    // 候補は画面が使う形が決まっていないので、タグ番号だけ拾う
                    String t = o.optString("タグ番号", o.optString("tag", ""));
                    if (!TextUtils.isEmpty(t)) {
                        r.suggest.add(t);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "応答を読めませんでした", e);
            r.offline = true;
            r.chk = "応答を読めません";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > 1024 * 1024) {
                break;      // 想定外に大きい応答は打ち切る
            }
        }
        in.close();
        return b.toString("UTF-8");
    }
}
