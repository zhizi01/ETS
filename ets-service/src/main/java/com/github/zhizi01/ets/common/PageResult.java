package com.github.zhizi01.ets.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> list = Collections.emptyList();
    private long total;
    private int page = 1;
    private int size = 20;

    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        return new PageResult<>(list, total, page, size);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(Collections.emptyList(), 0, page, size);
    }

    /**
     * 对已经全量取出的列表做内存分页。page 从 1 开始。
     */
    public static <T> PageResult<T> slice(List<T> all, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 500);
        int from = Math.min((p - 1) * s, all.size());
        int to = Math.min(from + s, all.size());
        return new PageResult<>(all.subList(from, to), all.size(), p, s);
    }
}
