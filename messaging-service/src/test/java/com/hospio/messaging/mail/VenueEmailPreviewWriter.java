package com.hospio.messaging.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.common.venue.VenueWording;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Renders the emails of a lounge, bar or restaurant (docs/plan-venue-type.md, phase 5) to look at them:
 * {@code -Dvenue.email.texts=<dir>} holds one {@code .txt} per mail (line 1 the kind, line 2 the From name, line 3 the place word
 * (hotel, lounge, bar, restaurant, club or location), line 4 the subject, then the text), written by tenant-service's
 * {@code VenueTypeTest} and platform-service's {@code MonthlyInvoiceServiceTest} with the same property; an {@code .html} is written
 * beside each. The day-closed mail's words are finance {@code NightAuditService.tellOwner}'s for a location without rooms.
 */
@EnabledIfSystemProperty(named = "venue.email.texts", matches = ".+")
class VenueEmailPreviewWriter {

    @Test
    void write() throws Exception {
        Path dir = Path.of(System.getProperty("venue.email.texts"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("day-closed-lounge.txt"), "DAILY_REPORT\nQA Lounge Wuse Bar\nlounge\n"
                + "QA Lounge Wuse Bar · day closed 2026-11-18 · revenue 68800.00\n"
                + "Day 2026-11-18 closed at QA Lounge Wuse Bar by Bisi Manager.\n"
                + "Revenue 68800.00 (16 payments), expenses 4500.00, net 64300.00.\n"
                + "Cash balanced.");
        List<Path> texts;
        try (Stream<Path> s = Files.list(dir)) {
            texts = s.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
        for (Path p : texts) {
            String[] part = Files.readString(p).split("\n", 5);
            VenueWording words = new VenueWording(part[2].trim());
            EmailTemplates.Rendered r = new EmailTemplates().render(part[0].trim(), part[1].trim(), part[3].trim(), part[4],
                    EmailTemplates.Brand.HOSPIO, words);
            if (!words.isHotel()) {
                assertThat(r.html()).as(p.getFileName().toString()).doesNotContain("hotel management").doesNotContain("Your hotel is ready");
            }
            Files.writeString(dir.resolve(p.getFileName().toString().replace(".txt", ".html")), r.html());
        }
    }
}
