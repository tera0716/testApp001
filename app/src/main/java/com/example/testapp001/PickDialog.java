package com.example.testapp001;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.SimpleAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一覧から1つ選ばせる。<b>2行で出す</b>ので、版や棚や担当者を見分けやすい。
 *
 * <p>1行の文字の羅列だと区別が付かない、という指摘を受けて作った。
 */
public final class PickDialog {

    public interface OnPick {
        void pick(int index);
    }

    private PickDialog() {
    }

    /**
     * @param line1 1行目（太めに出る）
     * @param line2 2行目（小さく出る）
     */
    public static void show(Context c, String title, List<String> line1, List<String> line2,
                            final OnPick cb) {
        List<Map<String, String>> data = new ArrayList<>();
        for (int i = 0; i < line1.size(); i++) {
            Map<String, String> m = new HashMap<>();
            m.put("a", line1.get(i));
            m.put("b", i < line2.size() ? line2.get(i) : "");
            data.add(m);
        }
        SimpleAdapter ad = new SimpleAdapter(c, data,
                android.R.layout.simple_list_item_2,
                new String[]{"a", "b"},
                new int[]{android.R.id.text1, android.R.id.text2});

        new AlertDialog.Builder(c)
                .setTitle(title)
                .setAdapter(ad, (d, which) -> cb.pick(which))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
