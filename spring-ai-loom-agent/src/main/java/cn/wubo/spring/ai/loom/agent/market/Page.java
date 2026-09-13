package cn.wubo.spring.ai.loom.agent.market;

import java.util.List;

public record Page<T>(List<T> items, long total, int page, int size) {
    public static <T> Page<T> of(List<T> items, long total, int page, int size) {
        return new Page<>(items, total, page, size);
    }
}
