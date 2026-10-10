package com.example.testapp001;

/**
 * 読み取ったバーコードを「棚卸アプリ（Web）と同じ形」のタグ番号に直す。
 *
 * <p>★ ここがサーバと食い違うと、確定（skt_tbl への登録）で落ちる。
 * Web 側（20260221_skt_tmp_tbl_2.php の tag_normalize / 一括入力）と同じ順序で処理する。
 *
 * <ol>
 *   <li>前後の空白を落とす</li>
 *   <li><b>全角の数字を半角にする</b>（Web は mb_convert_kana($v, 'n')）</li>
 *   <li><b>NW-7(Codabar) のスタート/ストップ文字 A〜D を落とす</b>
 *       （値札は NW-7。読み取り機によって前後に付いてくる）</li>
 *   <li><b>先頭の 0 を落とす</b>（Web は ltrim($tag, '0')）</li>
 *   <li>数字だけ・9桁以内 でなければ「タグではない」とみなす</li>
 * </ol>
 *
 * <p>★ 読み取り機によって A〜D が付く/付かないが変わるので、どちらでも通るようにしている。
 */
public final class TagNormalizer {

    /** タグ番号の最大桁数（Web 側と同じ） */
    public static final int MAX_DIGITS = 9;

    private TagNormalizer() {
    }

    /**
     * @param raw 読み取った文字列
     * @return タグ番号（数字のみの文字列）。タグとして読めなければ null
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = toHalfWidthDigits(raw.trim());
        if (v.isEmpty()) {
            return null;
        }

        // NW-7 のスタート/ストップ文字（A〜D / a〜d）を前後から落とす。
        // 中に混ざっている場合は「タグではない」とみなすので、ここでは前後だけを見る。
        v = stripCodabarGuards(v);
        if (v.isEmpty()) {
            return null;
        }

        // 数字だけか
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }

        // 先頭の 0 を落とす。全部 0 なら "0" を残す（Web の ltrim と同じ結果になる形に寄せる）
        int p = 0;
        while (p < v.length() - 1 && v.charAt(p) == '0') {
            p++;
        }
        v = v.substring(p);

        if (v.length() > MAX_DIGITS) {
            return null;
        }
        return v;
    }

    /** タグとして読めるか */
    public static boolean isTag(String raw) {
        return normalize(raw) != null;
    }

    /**
     * 全角の数字（０〜９）を半角にする。
     * 全角の英字とスペースも、NW-7 の前後文字の判定ができるように半角へ寄せる。
     */
    static String toHalfWidthDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                b.append((char) ('0' + (c - '０')));
            } else if (c >= 'Ａ' && c <= 'Ｚ') {
                b.append((char) ('A' + (c - 'Ａ')));
            } else if (c >= 'ａ' && c <= 'ｚ') {
                b.append((char) ('a' + (c - 'ａ')));
            } else if (c == '　') {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    /**
     * NW-7 のスタート/ストップ文字を前後から落とす。
     * 「A1234B」「a1234a」「A1234」「1234B」のどれでも中身を取り出す。
     */
    static String stripCodabarGuards(String s) {
        int from = 0;
        int to = s.length();
        if (to > 1 && isGuard(s.charAt(from))) {
            from++;
        }
        if (to - 1 > from && isGuard(s.charAt(to - 1))) {
            to--;
        }
        return s.substring(from, to);
    }

    private static boolean isGuard(char c) {
        return (c >= 'A' && c <= 'D') || (c >= 'a' && c <= 'd');
    }
}
