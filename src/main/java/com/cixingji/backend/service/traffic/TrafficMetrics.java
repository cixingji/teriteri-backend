package com.cixingji.backend.service.traffic;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

@Component
public class TrafficMetrics {
    private final LongAdder accepted = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder published = new LongAdder();
    private final LongAdder publishFailures = new LongAdder();
    private final LongAdder aggregated = new LongAdder();

    public void accepted() { accepted.increment(); }
    public void rejected() { rejected.increment(); }
    public void published() { published.increment(); }
    public void publishFailed() { publishFailures.increment(); }
    public void aggregated(long count) { aggregated.add(count); }

    public Map<String, Long> snapshot() {
        Map<String, Long> data = new LinkedHashMap<>();
        data.put("accepted", accepted.sum());
        data.put("rejected", rejected.sum());
        data.put("published", published.sum());
        data.put("publishFailures", publishFailures.sum());
        data.put("aggregated", aggregated.sum());
        return data;
    }
}
