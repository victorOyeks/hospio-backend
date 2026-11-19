package com.hospio.platform.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.platform.system.SystemHealthDtos.Cert;
import com.hospio.platform.system.SystemHealthDtos.ConsumerGroup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the probes read from the server, parsed the way uptime, free, df and systemctl show it (samples from the staging box). */
class SystemHealthProbesTest {

    static final String MEMINFO = """
            MemTotal:       12247040 kB
            MemFree:         1365000 kB
            MemAvailable:    1717000 kB
            Buffers:           10000 kB
            Cached:           700000 kB
            SwapTotal:       4193280 kB
            SwapFree:        2534400 kB
            """;

    @Test
    void memoryIsTotalMinusAvailableLikeFree() {
        HostProbe.Memory m = HostProbe.parseMeminfo(MEMINFO);
        assertThat(m.total()).isEqualTo(12247040L * 1024);
        assertThat(m.used()).isEqualTo((12247040L - 1717000L) * 1024);
        assertThat(m.swapUsed()).isEqualTo((4193280L - 2534400L) * 1024);
    }

    @Test
    void oldKernelsWithoutMemAvailableCountFreeBuffersAndCache() {
        HostProbe.Memory m = HostProbe.parseMeminfo("MemTotal: 1000 kB\nMemFree: 100 kB\nBuffers: 50 kB\nCached: 150 kB\n");
        assertThat(m.available()).isEqualTo(300L * 1024);
        assertThat(m.swapTotal()).isZero();
    }

    @Test
    void loadAndCpuAndPressure() {
        assertThat(HostProbe.parseLoadavg("2.87 3.52 4.17 3/1203 2041496\n")).containsExactly(2.87, 3.52, 4.17);
        long[] cpu = HostProbe.parseCpu("cpu  100 0 50 800 50 0 0 0 0 0\ncpu0 1 2 3 4\nbtime 1761000000\n");
        assertThat(cpu).containsExactly(850L, 1000L);
        assertThat(HostProbe.parseBtime("cpu 1 2\nbtime 1761000000\n")).contains(1761000000L);
        assertThat(HostProbe.parsePressure("some avg10=0.00 avg60=12.50 avg300=3.10 total=1\nfull avg10=0.00 avg60=1.00 avg300=0 total=1\n"))
                .contains(12.5);
    }

    @Test
    void cpuPercentIsBusySinceTheLastLook(@TempDir Path proc) throws Exception {
        HostProbe probe = new HostProbe(proc, proc);
        Files.writeString(proc.resolve("stat"), "cpu  100 0 0 900 0 0 0 0\n");
        probe.cpuPct();
        Files.writeString(proc.resolve("stat"), "cpu  150 0 0 950 0 0 0 0\n");
        assertThat(probe.cpuPct()).contains(50.0);
    }

    @Test
    void onlyRealDisksOncePerDevice() {
        String mounts = """
                /dev/sda1 / ext4 rw,relatime 0 0
                tmpfs /run tmpfs rw 0 0
                /dev/loop3 /snap/core20/2105 squashfs ro 0 0
                /dev/sda16 /boot ext4 rw 0 0
                /dev/sda15 /boot/efi vfat rw 0 0
                /dev/sda1 /var/lib/docker/overlay2/x ext4 rw 0 0
                overlay /var/lib/docker/overlay2/y/merged overlay rw 0 0
                """;
        assertThat(HostProbe.parseMounts(mounts)).extracting(HostProbe.MountPoint::path).containsExactly("/", "/boot", "/boot/efi");
    }

    @Test
    void diskPercentRoundsUpLikeDf() {
        HostProbe.Disk d = new HostProbe.Disk(new HostProbe.MountPoint("/dev/sda1", "/", "ext4"), 100, 19, 77);
        assertThat(d.usedPct()).isEqualTo(19.8);
    }

    @Test
    void systemctlShowBlocksAreParsedPerUnit() {
        String out = """
                MainPID=2041496
                NRestarts=0
                MemoryCurrent=480485376
                CPUUsageNSec=689506602000
                Id=hospio-platform-service.service
                ActiveState=active
                SubState=running
                ActiveEnterTimestampMonotonic=2765000000000

                MainPID=0
                NRestarts=4
                MemoryCurrent=[not set]
                CPUUsageNSec=18446744073709551615
                Id=hospio-maintenance-service.service
                ActiveState=failed
                SubState=failed
                ActiveEnterTimestampMonotonic=0
                """;
        List<UnitProbe.Unit> units = UnitProbe.parseShow(out);
        assertThat(units).hasSize(2);
        assertThat(units.get(0).id()).isEqualTo("hospio-platform-service.service");
        assertThat(units.get(0).memoryCurrent()).isEqualTo(480485376L);
        assertThat(units.get(1).activeState()).isEqualTo("failed");
        assertThat(units.get(1).restarts()).isEqualTo(4);
        assertThat(units.get(1).memoryCurrent()).isNull();
        assertThat(units.get(1).cpuNs()).isNull();
    }

    @Test
    void theCommandRunnerOnlyRunsReadOnlySystemctlWithPlainArguments() {
        CommandRunner runner = new CommandRunner();
        assertThatThrownBy(() -> runner.systemctl("restart", List.of("hospio-platform-service"), 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.systemctl("stop", List.of(), 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.systemctl("show", List.of("x; rm -rf /"), 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.systemctl("show", List.of("$(id)"), 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.systemctl("show", List.of("a b"), 1000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CommandRunner.SAFE_ARG.matcher("Id,ActiveState,NRestarts").matches()).isTrue();
        assertThat(CommandRunner.SAFE_ARG.matcher("--type=service").matches()).isTrue();
        assertThat(UnitProbe.UNIT.matcher("hospio-api-gateway").matches()).isTrue();
        assertThat(UnitProbe.UNIT.matcher("../etc").matches()).isFalse();
    }

    @Test
    void redisInfoKeysAcrossDatabases() {
        Properties p = new Properties();
        p.setProperty("redis_version", "7.0.15");
        p.setProperty("used_memory", "1310720");
        p.setProperty("used_memory_peak", "1352663");
        p.setProperty("maxmemory", "0");
        p.setProperty("db1", "keys=37,expires=37,avg_ttl=741462522");
        p.setProperty("db2", "keys=19,expires=19,avg_ttl=2870520150");
        SystemHealthDtos.Redis r = RedisProbe.fromInfo(p);
        assertThat(r.up()).isTrue();
        assertThat(r.keys()).isEqualTo(56);
        assertThat(r.usedBytes()).isEqualTo(1310720L);
        assertThat(r.maxBytes()).isZero();
    }

    @Test
    void certificateLevels() {
        Instant now = Instant.parse("2026-11-19T12:00:00Z");
        Cert ok = CertProbe.withLevel(new Cert("a", null, now.plus(Duration.ofDays(80)), "Let's Encrypt", null, "OK"), 21, now);
        Cert soon = CertProbe.withLevel(new Cert("b", null, now.plus(Duration.ofDays(15)), "Let's Encrypt", null, "OK"), 21, now);
        Cert urgent = CertProbe.withLevel(new Cert("c", null, now.plus(Duration.ofDays(3)), "Let's Encrypt", null, "OK"), 21, now);
        Cert expired = CertProbe.withLevel(new Cert("d", null, now.minus(Duration.ofDays(2)), "Let's Encrypt", null, "OK"), 21, now);
        assertThat(ok.daysLeft()).isEqualTo(80);
        assertThat(ok.level()).isEqualTo("OK");
        assertThat(soon.level()).isEqualTo("WARNING");
        assertThat(urgent.level()).isEqualTo("PROBLEM");
        assertThat(expired.level()).isEqualTo("PROBLEM");
        assertThat(expired.daysLeft()).isNegative();
    }

    @Test
    void aConsumerGroupOverTheLimitThatStopsReadingIsStuck() {
        KafkaProbe k = new KafkaProbe("localhost:9092");
        Instant t0 = Instant.parse("2026-11-19T12:00:00Z");
        ConsumerGroup first = k.group("hospio-folio-service", 900, 100, t0, 500, 10);
        assertThat(first.level()).isEqualTo("WARNING");
        assertThat(first.stuckMinutes()).isNull();
        ConsumerGroup later = k.group("hospio-folio-service", 1200, 100, t0.plus(Duration.ofMinutes(11)), 500, 10);
        assertThat(later.trend()).isEqualTo("GROWING");
        assertThat(later.stuckMinutes()).isEqualTo(11);
        assertThat(later.level()).isEqualTo("PROBLEM");
        assertThat(later.service()).isEqualTo("folio-service");
        // It reads again: no longer stuck, even with lag left.
        ConsumerGroup moving = k.group("hospio-folio-service", 800, 600, t0.plus(Duration.ofMinutes(12)), 500, 10);
        assertThat(moving.level()).isEqualTo("WARNING");
        assertThat(moving.trend()).isEqualTo("FALLING");
        // Under the limit is fine whatever the progress.
        assertThat(k.group("hospio-fnb-service", 3, 10, t0, 500, 10).level()).isEqualTo("OK");
    }

    @Test
    void folderSizes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.log"), "x".repeat(1000));
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/b.log"), "y".repeat(500));
        assertThat(DirSizes.measure("Logs", dir.toString()).bytes()).isEqualTo(1500L);
        assertThat(DirSizes.measure("Gone", dir.resolve("nope").toString()).note()).isEqualTo("Not on this server");
    }
}
