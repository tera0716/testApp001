package com.example.testapp001;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * タグ正規化の試験。端末もエミュレータも要らない（JVM 上で動く）。
 *     gradlew :app:testDebugUnitTest
 *
 * ★ 期待値は Web 側（20260221_skt_tmp_tbl_2.php）の挙動に合わせてある。
 */
public class TagNormalizerTest {

    @Test
    public void 数字だけならそのまま() {
        assertEquals("14063118", TagNormalizer.normalize("14063118"));
        assertEquals("90960861", TagNormalizer.normalize("90960861"));
    }

    @Test
    public void 前後の空白は落とす() {
        assertEquals("14063118", TagNormalizer.normalize("  14063118 "));
        assertEquals("14063118", TagNormalizer.normalize("\t14063118\r\n"));
    }

    @Test
    public void 全角の数字は半角にする() {
        assertEquals("14063118", TagNormalizer.normalize("１４０６３１１８"));
        assertEquals("14063118", TagNormalizer.normalize("１４0６3１１８"));
    }

    @Test
    public void NW7のスタートストップ文字を落とす() {
        assertEquals("14063118", TagNormalizer.normalize("A14063118B"));
        assertEquals("14063118", TagNormalizer.normalize("a14063118a"));
        assertEquals("14063118", TagNormalizer.normalize("D14063118C"));
        // 片方だけ付いている場合も通す（読み取り機によって変わる）
        assertEquals("14063118", TagNormalizer.normalize("A14063118"));
        assertEquals("14063118", TagNormalizer.normalize("14063118B"));
        // 全角で来ても通す
        assertEquals("14063118", TagNormalizer.normalize("Ａ１４０６３１１８Ｂ"));
    }

    @Test
    public void 先頭のゼロは落とす() {
        assertEquals("14063118", TagNormalizer.normalize("014063118"));
        assertEquals("14063118", TagNormalizer.normalize("A014063118B"));
        assertEquals("1", TagNormalizer.normalize("00000001"));
        // 全部ゼロなら "0" を残す
        assertEquals("0", TagNormalizer.normalize("0000"));
    }

    @Test
    public void 九桁までは通す() {
        assertEquals("123456789", TagNormalizer.normalize("123456789"));
        // 先頭ゼロを落とした結果が9桁なら通る
        assertEquals("123456789", TagNormalizer.normalize("0123456789"));
    }

    @Test
    public void 十桁以上は弾く() {
        assertNull(TagNormalizer.normalize("1234567890"));
        assertNull(TagNormalizer.normalize("A1234567890B"));
    }

    @Test
    public void 数字以外が混ざっていたら弾く() {
        assertNull(TagNormalizer.normalize("1406-3118"));
        assertNull(TagNormalizer.normalize("14063118X"));
        assertNull(TagNormalizer.normalize("ABC"));
        assertNull(TagNormalizer.normalize("14E63118"));   // 中の英字は落とさない
        assertNull(TagNormalizer.normalize("https://example.com"));
    }

    @Test
    public void 空やnullは弾く() {
        assertNull(TagNormalizer.normalize(null));
        assertNull(TagNormalizer.normalize(""));
        assertNull(TagNormalizer.normalize("   "));
        assertNull(TagNormalizer.normalize("A"));
        assertNull(TagNormalizer.normalize("AB"));
    }

    @Test
    public void isTagはnormalizeと一致する() {
        assertTrue(TagNormalizer.isTag("A14063118B"));
        assertTrue(TagNormalizer.isTag("14063118"));
        assertFalse(TagNormalizer.isTag("ABC"));
        assertFalse(TagNormalizer.isTag(null));
    }

    @Test
    public void 実際に本番で見たタグで確かめる() {
        // 引継ぎ書・差異リストに出てきた実在のタグ番号
        assertEquals("54088", TagNormalizer.normalize("54088"));
        assertEquals("90954088", TagNormalizer.normalize("90954088"));
        assertEquals("57814", TagNormalizer.normalize("A57814B"));
        assertEquals("157601", TagNormalizer.normalize("157601"));
        assertEquals("14105054", TagNormalizer.normalize("14105054"));
        assertEquals("800299343", TagNormalizer.normalize("800299343"));   // 9桁
    }
}
