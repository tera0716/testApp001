package com.example.testapp001;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 端末向けの口（20261011_skt_api.php）を叩く。
 *
 * <ul>
 *   <li>{@link #versions} … 既存の版の一覧。<b>端末から版は作らない</b>ので、ここから選ぶ</li>
 *   <li>{@link #push} … 溜めた読み取りをまとめて登録する</li>
 * </ul>
 *
 * <p>★ 通信するのでワーカースレッドから呼ぶこと。
 * <p>★ 認証はログイン済みの Cookie（{@link ServerConfig}）。
 */
public class ScanPush {

    private static final String TAG = "ScanPush";
    private static final int TIMEOUT_MS = 15000;

    /** 既存の版 */
    public static class Version {
        public int pst;
        public String pstNm = "";
        public String ymd = "";
        public String verTnt = "";
        public String tntNm = "";
        public int webCnt;
        public int accCnt;
        public String src = "";

        /** 一覧に出す1行 */
        public String label() {
            StringBuilder b = new StringBuilder();
            b.append(ymd).append("　部署").append(pst);
            if (!TextUtils.isEmpty(pstNm)) {
                b.append('_').append(pstNm);
            }
            b.append("　[").append(src).append(']');
            b.append("  Web ").append(webCnt).append(" / Access ").append(accCnt);
            if (!TextUtils.isEmpty(tntNm)) {
                b.append("　担当 ").append(tntNm);
            }
            return b.toString();
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("pst", pst).put("pst_nm", pstNm).put("ymd", ymd)
             .put("ver_tnt", verTnt).put("tnt_nm", tntNm)
             .put("web_cnt", webCnt).put("acc_cnt", accCnt).put("src", src);
            return o;
        }

        public static Version fromJson(JSONObject o) {
            Version v = new Version();
            v.pst = o.optInt("pst");
            v.pstNm = o.optString("pst_nm", "");
            v.ymd = o.optString("ymd", "");
            v.verTnt = o.optString("ver_tnt", "");
            v.tntNm = o.optString("tnt_nm", "");
            v.webCnt = o.optInt("web_cnt");
            v.accCnt = o.optInt("acc_cnt");
            v.src = o.optString("src", "");
            return v;
        }
    }

    /** 通信の結果に共通で付くもの */
    public static class Res {
        public boolean ok = false;
        public boolean offline = false;
        public boolean needLogin = false;
        public String msg = "";
    }

    public static class VersionsRes extends Res {
        public List<Version> list = new ArrayList<>();
        /** 電波が無くて、覚えてある一覧を使ったか */
        public boolean fromCache = false;
    }

    public static class PushRes extends Res {
        public int ins = 0;
        public int dup = 0;
        public int stripped = 0;
        public String batch = "";
        public boolean reused = false;
        public List<String> badKeta = new ArrayList<>();
        public List<String> rejected = new ArrayList<>();
    }

    private ScanPush() {
    }

    /** その版にすでにある棚 */
    public static class Shelf {
        public String src = "";
        public String batch = "";
        public String tana = "";
        public int cnt;
        public int tnt;
        public String tntNm = "";
        /** 確定済み。ここには足せない */
        public boolean locked;
        public String last = "";

        public String line1() {
            return tana + "　" + cnt + " 件" + (locked ? "　【確定済・追加できません】" : "");
        }

        public String line2() {
            StringBuilder b = new StringBuilder();
            b.append(src);
            if (tnt > 0) {
                b.append("　担当 ").append(tnt);
                if (!TextUtils.isEmpty(tntNm)) {
                    b.append('_').append(tntNm);
                }
            }
            if (!TextUtils.isEmpty(last)) {
                b.append("　最後 ").append(last);
            }
            return b.toString();
        }
    }

    /** 担当者 */
    public static class Staff {
        public int tnt;
        public String tntNm = "";
        public int pst;
        public int recent;

        public String line1() {
            return tnt + "　" + tntNm;
        }

        public String line2() {
            if (recent > 0) {
                return "この部署で最近 " + recent + " 件 読んでいます";
            }
            return pst > 0 ? ("所属 部署" + pst) : "";
        }
    }

    public static class ShelvesRes extends Res {
        public List<Shelf> list = new ArrayList<>();
    }

    public static class StaffRes extends Res {
        public List<Staff> list = new ArrayList<>();
    }

    /**
     * ★ ログインできているかを確かめる。
     *
     * <p>Cookie の有無では判断できない。ログインしていなくても PHPSESSID は発行される。
     */
    public static boolean verifyLogin(Context ctx) {
        String cookie = ServerConfig.cookie(ctx);
        if (TextUtils.isEmpty(cookie)) {
            ServerConfig.setVerified(ctx, false);
            return false;
        }
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(ServerConfig.whoamiUrl(ctx)).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Cookie", cookie);
            boolean okNow = (con.getResponseCode() == 200);
            ServerConfig.setVerified(ctx, okNow);
            return okNow;
        } catch (Exception e) {
            // 電波が無いだけなら、前に確かめた結果をそのままにしておく
            Log.w(TAG, "ログインを確かめられませんでした", e);
            return ServerConfig.isVerified(ctx);
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    /** その版にすでにある棚を取る */
    public static ShelvesRes shelves(Context ctx, int pst, String ymd) {
        ShelvesRes r = new ShelvesRes();
        JSONObject j = getJson(ctx, ServerConfig.shelvesUrl(ctx, pst, ymd), r);
        if (j == null) {
            return r;
        }
        JSONArray a = j.optJSONArray("shelves");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Shelf s = new Shelf();
                s.src = o.optString("src", "");
                s.batch = o.optString("batch", "");
                s.tana = o.optString("tana", "");
                s.cnt = o.optInt("cnt");
                s.tnt = o.optInt("tnt");
                s.tntNm = o.optString("tnt_nm", "");
                s.locked = o.optBoolean("locked");
                s.last = o.optString("last", "");
                r.list.add(s);
            }
        }
        r.ok = true;
        return r;
    }

    /** 担当者の一覧を取る */
    public static StaffRes staff(Context ctx, int pst) {
        StaffRes r = new StaffRes();
        JSONObject j = getJson(ctx, ServerConfig.staffUrl(ctx, pst), r);
        if (j == null) {
            return r;
        }
        JSONArray a = j.optJSONArray("staff");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Staff s = new Staff();
                s.tnt = o.optInt("tnt");
                s.tntNm = o.optString("tnt_nm", "");
                s.pst = o.optInt("pst");
                s.recent = o.optInt("recent");
                r.list.add(s);
            }
        }
        r.ok = true;
        return r;
    }

    /** GET して JSON を返す。だめなら res に理由を立てて null */
    private static JSONObject getJson(Context ctx, String url, Res res) {
        String cookie = ServerConfig.cookie(ctx);
        if (TextUtils.isEmpty(cookie)) {
            res.needLogin = true;
            res.msg = "ログインが必要です";
            return null;
        }
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Cookie", cookie);
            int code = con.getResponseCode();
            if (code == 401 || code == 302 || code == 301) {
                res.needLogin = true;
                res.msg = "ログインが切れました";
                ServerConfig.setVerified(ctx, false);
                return null;
            }
            if (code != 200) {
                res.offline = true;
                res.msg = "サーバの応答 " + code;
                return null;
            }
            JSONObject j = new JSONObject(readAll(con.getInputStream()));
            if (!j.optBoolean("ok")) {
                res.msg = j.optString("msg", "取れませんでした");
                return null;
            }
            return j;
        } catch (Exception e) {
            Log.w(TAG, "取れませんでした: " + url, e);
            res.offline = true;
            res.msg = "つながりません";
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- 版の一覧
    public static VersionsRes versions(Context ctx) {
        VersionsRes r = new VersionsRes();
        String cookie = ServerConfig.cookie(ctx);
        if (TextUtils.isEmpty(cookie)) {
            r.needLogin = true;
            r.msg = "ログインが必要です";
            loadCache(ctx, r);
            return r;
        }
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(ServerConfig.versionsUrl(ctx)).openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Cookie", cookie);
            int code = con.getResponseCode();
            if (code == 401 || code == 302 || code == 301) {
                r.needLogin = true;
                r.msg = "ログインが切れました";
                loadCache(ctx, r);
                return r;
            }
            if (code != 200) {
                r.offline = true;
                r.msg = "サーバの応答 " + code;
                loadCache(ctx, r);
                return r;
            }
            String body = readAll(con.getInputStream());
            JSONObject j = new JSONObject(body);
            if (!j.optBoolean("ok")) {
                r.msg = j.optString("msg", "取れませんでした");
                loadCache(ctx, r);
                return r;
            }
            JSONArray a = j.optJSONArray("versions");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) {
                        r.list.add(Version.fromJson(o));
                    }
                }
            }
            r.ok = true;
            r.msg = r.list.size() + " 件の版";
            ServerConfig.setVersionsCache(ctx, body);   // 電波が無いときのために覚えておく
            return r;
        } catch (Exception e) {
            Log.w(TAG, "版の一覧を取れませんでした", e);
            r.offline = true;
            r.msg = "つながりません";
            loadCache(ctx, r);
            return r;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    /** 覚えてある一覧を使う */
    private static void loadCache(Context ctx, VersionsRes r) {
        String s = ServerConfig.versionsCache(ctx);
        if (TextUtils.isEmpty(s)) {
            return;
        }
        try {
            JSONArray a = new JSONObject(s).optJSONArray("versions");
            if (a == null) {
                return;
            }
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    r.list.add(Version.fromJson(o));
                }
            }
            r.fromCache = true;
            r.msg += "（前に取った一覧を使っています）";
        } catch (Exception e) {
            Log.w(TAG, "覚えてある一覧を読めませんでした", e);
        }
    }

    // ---------------------------------------------------------------- 一括登録
    /**
     * 読んだタグをまとめて送る。
     *
     * @param tags 改行で区切ったタグ番号
     */
    public static PushRes push(Context ctx, int pst, String ymd, String tana, int tnt, String tags) {
        PushRes r = new PushRes();
        String cookie = ServerConfig.cookie(ctx);
        if (TextUtils.isEmpty(cookie)) {
            r.needLogin = true;
            r.msg = "ログインが必要です";
            return r;
        }
        HttpURLConnection con = null;
        try {
            String body = "pst=" + pst
                    + "&ymd=" + URLEncoder.encode(ymd, "UTF-8")
                    + "&tana=" + URLEncoder.encode(tana, "UTF-8")
                    + "&tnt=" + tnt
                    + "&tags=" + URLEncoder.encode(tags, "UTF-8");

            con = (HttpURLConnection) new URL(ServerConfig.pushUrl(ctx)).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setInstanceFollowRedirects(false);
            con.setDoOutput(true);
            con.setRequestProperty("Cookie", cookie);
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] out = body.getBytes("UTF-8");
            con.setFixedLengthStreamingMode(out.length);
            OutputStream os = con.getOutputStream();
            os.write(out);
            os.flush();
            os.close();

            int code = con.getResponseCode();
            InputStream in = (code >= 400) ? con.getErrorStream() : con.getInputStream();
            String resp = (in == null) ? "" : readAll(in);

            if (code == 401 || code == 302 || code == 301) {
                r.needLogin = true;
                r.msg = "ログインが切れました";
                return r;
            }

            JSONObject j;
            try {
                j = new JSONObject(resp);
            } catch (Exception e) {
                r.offline = true;
                r.msg = "応答を読めません（" + code + "）";
                return r;
            }
            r.ok = j.optBoolean("ok");
            r.msg = j.optString("msg", j.optString("err", ""));
            r.ins = j.optInt("ins");
            r.dup = j.optInt("dup");
            r.stripped = j.optInt("stripped");
            r.batch = j.optString("batch", "");
            r.reused = j.optBoolean("reused");
            JSONArray bk = j.optJSONArray("bad_keta");
            if (bk != null) {
                for (int i = 0; i < bk.length(); i++) {
                    r.badKeta.add(bk.optString(i));
                }
            }
            JSONArray rj = j.optJSONArray("rejected");
            if (rj != null) {
                for (int i = 0; i < rj.length(); i++) {
                    r.rejected.add(rj.optString(i));
                }
            }
            if (!r.ok && TextUtils.isEmpty(r.msg)) {
                r.msg = "登録できませんでした（" + code + "）";
            }
            return r;
        } catch (Exception e) {
            Log.w(TAG, "送れませんでした", e);
            r.offline = true;
            r.msg = "つながりません";
            return r;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > 4 * 1024 * 1024) {
                break;
            }
        }
        in.close();
        return b.toString("UTF-8");
    }
}
