package com.hospio.platform.system;

import com.hospio.platform.system.SystemHealthDtos.ConsumerGroup;
import com.hospio.platform.system.SystemHealthDtos.Kafka;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kafka through the admin API (read-only): brokers, topics, the size of the brokers' log folders, and for every Hospio consumer
 * group (name starting {@code hospio-}) how many messages wait to be read. Progress is remembered between looks so a group whose
 * lag stays above the limit without reading anything is reported as stuck, and the trend (growing, steady, falling) is shown.
 */
@Slf4j
@Component
public class KafkaProbe {

    static final String GROUP_PREFIX = "hospio-";

    private final String servers;
    private volatile AdminClient admin;
    /** group → what was seen last: committed offsets total, lag, when the offsets last moved. */
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    record Seen(long committed, long lag, Instant movedAt) {
    }

    public KafkaProbe(@Value("${spring.kafka.bootstrap-servers}") String servers) {
        this.servers = servers;
    }

    private AdminClient admin() {
        AdminClient a = admin;
        if (a == null) {
            synchronized (this) {
                if (admin == null) {
                    admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers,
                            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000, AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 4000,
                            AdminClientConfig.CLIENT_ID_CONFIG, "platform-system-health"));
                }
                a = admin;
            }
        }
        return a;
    }

    @PreDestroy
    void close() {
        AdminClient a = admin;
        if (a != null) {
            a.close(Duration.ofSeconds(2));
        }
    }

    /** lagLimit and stuckMinutes from the settings: a group over the limit that hasn't moved for that long is stuck. */
    public Kafka probe(int lagLimit, int stuckMinutes) {
        try {
            AdminClient a = admin();
            Collection<Node> nodes = a.describeCluster().nodes().get(4, TimeUnit.SECONDS);
            int topics = a.listTopics().names().get(4, TimeUnit.SECONDS).size();
            Long logBytes = null;
            try {
                List<Integer> ids = nodes.stream().map(Node::id).toList();
                long sum = 0;
                for (Map<String, LogDirDescription> dirs : a.describeLogDirs(ids).allDescriptions().get(4, TimeUnit.SECONDS).values()) {
                    for (LogDirDescription d : dirs.values()) {
                        for (ReplicaInfo r : d.replicaInfos().values()) {
                            sum += r.size();
                        }
                    }
                }
                logBytes = sum;
            } catch (Exception e) {
                log.debug("Kafka log dirs not readable: {}", e.toString());
            }
            List<ConsumerGroup> groups = new ArrayList<>();
            Instant now = Instant.now();
            for (ConsumerGroupListing g : a.listConsumerGroups().all().get(4, TimeUnit.SECONDS)) {
                if (!g.groupId().startsWith(GROUP_PREFIX)) {
                    continue;
                }
                Map<TopicPartition, OffsetAndMetadata> committed = a.listConsumerGroupOffsets(g.groupId()).partitionsToOffsetAndMetadata()
                        .get(4, TimeUnit.SECONDS);
                Map<TopicPartition, OffsetSpec> ask = new HashMap<>();
                committed.keySet().forEach(tp -> ask.put(tp, OffsetSpec.latest()));
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = ask.isEmpty() ? Map.of() : a.listOffsets(ask).all().get(4, TimeUnit.SECONDS);
                long lag = 0;
                long done = 0;
                for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                    if (e.getValue() == null) {
                        continue;
                    }
                    done += e.getValue().offset();
                    ListOffsetsResult.ListOffsetsResultInfo end = ends.get(e.getKey());
                    if (end != null) {
                        lag += Math.max(0, end.offset() - e.getValue().offset());
                    }
                }
                groups.add(group(g.groupId(), lag, done, now, lagLimit, stuckMinutes));
            }
            groups.sort((x, y) -> Long.compare(y.lag(), x.lag()));
            String level = groups.stream().map(ConsumerGroup::level).reduce(SystemHealthDtos.OK, SystemHealthDtos::worst);
            return new Kafka(!nodes.isEmpty(), null, nodes.size(), topics, logBytes, groups, nodes.isEmpty() ? SystemHealthDtos.PROBLEM : level);
        } catch (Exception e) {
            log.warn("System health: Kafka not readable at {}: {}", servers, e.toString());
            return new Kafka(false, "Not reachable: " + rootName(e), null, null, null, List.of(), SystemHealthDtos.PROBLEM);
        }
    }

    ConsumerGroup group(String id, long lag, long committed, Instant now, int lagLimit, int stuckMinutes) {
        Seen before = seen.get(id);
        Instant movedAt = before == null || before.committed() != committed ? now : before.movedAt();
        seen.put(id, new Seen(committed, lag, movedAt));
        String trend = before == null ? "STEADY" : lag > before.lag() ? "GROWING" : lag < before.lag() ? "FALLING" : "STEADY";
        int still = (int) Duration.between(movedAt, now).toMinutes();
        boolean over = lag >= lagLimit;
        Integer stuck = over && before != null && still >= 1 ? still : null;
        String level = over && stuck != null && stuck >= stuckMinutes ? SystemHealthDtos.PROBLEM : over ? SystemHealthDtos.WARNING : SystemHealthDtos.OK;
        String service = id.startsWith(GROUP_PREFIX) ? id.substring(GROUP_PREFIX.length()) : id;
        return new ConsumerGroup(id, service, lag, trend, stuck, level);
    }

    private static String rootName(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
