package com.hospio.messaging.mail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Writes one sample of every email to {@code -Demail.previews=<dir>} (docs/email.md), to look at the designs:
 * mvn -pl messaging-service test -Dtest=EmailPreviewWriter -Demail.previews=/tmp/previews
 */
@EnabledIfSystemProperty(named = "email.previews", matches = ".+")
class EmailPreviewWriter {

    private record Sample(String kind, String from, String subject, String text, EmailTemplates.Brand brand) {
    }

    @Test
    void write() throws Exception {
        Path dir = Path.of(System.getProperty("email.previews"));
        Files.createDirectories(dir);
        EmailTemplates.Brand hospio = EmailTemplates.Brand.HOSPIO;
        EmailTemplates.Brand wine = EmailTemplates.Brand.of("#722f37", null);
        EmailTemplates.Brand blue = EmailTemplates.Brand.of("#1d4ed8", null);
        Map<String, Sample> samples = new LinkedHashMap<>();
        samples.put("01-booking-confirmed", new Sample("BOOKING_CONFIRMATION", "St. Paul's Hotel", "Your booking HBTNWWES is confirmed",
                "Hello Victor,\n\nYour booking is confirmed.\n\nReference: HBTNWWES\nStay: Mon 21 Dec to Tue 22 Dec · 1 night\nRoom: Deluxe\n\nReply to this email if anything needs changing. We look forward to welcoming you.", wine));
        samples.put("02-booking-changed", new Sample("BOOKING_CHANGED", "Simpas Ikeja", "Your stay has changed",
                "Hello Victor,\n\nYour stay has new dates.\n\nReference: HBTNWWES\nCheck-in: Tue 22 Dec\nCheck-out: Thu 24 Dec\nNights: 2\nRoom: A204\n\nReply to this email if that is not right.", hospio));
        samples.put("03-booking-cancelled", new Sample("BOOKING_CANCELLED", "Simpas Ikeja", "Your booking HBTNWWES is cancelled",
                "Hello Victor,\n\nYour booking has been cancelled.\n\nReference: HBTNWWES\nWas: Mon 21 Dec to Tue 22 Dec\nReason: Guest asked to cancel\n\nReply to this email if you have any questions. We hope to welcome you another time.", hospio));
        samples.put("04-receipt", new Sample("RECEIPT", "Royal Suites Ikeja", "Your receipt RC-000214",
                "Thank you, Adaeze. We have received your payment.\n\nAmount: ₦45,000\nMethod: Card\nReference: RC-000214\n\nView it online: https://web-staging.hospio360.com/receipt/RC-000214", blue));
        samples.put("05-invite", new Sample("INVITE", "Royal Suites Ikeja", "You're invited to Hospio360",
                "Hello Tunde Bakare,\n\nOpen this link to choose your password and sign in:\nhttps://web-staging.hospio360.com/accept-invite?token=abc123\n\nThe link works until 16 Nov 2026, 18:00.", blue));
        samples.put("06-temp-password", new Sample("TEMP_PASSWORD", "Royal Suites Ikeja", "Your Hospio360 temporary password",
                "Hello Tunde Bakare,\n\nA Hospio360 login was set up for you.\n\nEmail: tunde@royalsuites.ng\nTemporary password: Kq7!mPz2\nIt works until 16 Nov 2026, 18:00 and must be changed on first sign-in.\n\nSign in at https://web-staging.hospio360.com", hospio));
        samples.put("07-password-reset", new Sample("PASSWORD_RESET", "Hospio360", "Your Hospio360 password was reset",
                "Hello Ada,\n\nYour password was reset by your manager.\n\nTemporary password: Zt4#pQ9w\n\nSign in at https://web-staging.hospio360.com", hospio));
        samples.put("08-handover", new Sample("HANDOVER_PACK", "Hospio360", "Your Hospio360 handover pack · Royal Suites Ltd",
                "Hello Godwin,\n\nRoyal Suites Ltd is set up on Hospio360 and ready to run.\n\nHotels: 4\nRooms: 120\nStaff invited: 18\n\n- Sign in and check your hotels\n- Invite the rest of your team\n- Link your POS terminals\n\nSign in at https://web-staging.hospio360.com", hospio));
        samples.put("09-statement", new Sample("STATEMENT", "Royal Suites Ikeja", "Statement · Zenith Bank Plc · November",
                "Hello Zenith Bank travel desk,\n\nHere is your account statement for November.\n\nStays: 6\nRoom nights: 14\nTotal: ₦630,000\nPaid: ₦400,000\nBalance due: ₦230,000\n\nPlease pay within 30 days.", blue));
        samples.put("10-payment-reminder", new Sample("PAYMENT_REMINDER", "Royal Suites Ikeja", "Payment reminder · Zenith Bank Plc",
                "Hello Zenith Bank travel desk,\n\nYour invoice INV-0042 is now 7 days overdue.\n\nInvoice: INV-0042\nAmount due: ₦230,000\nDue date: 5 Nov 2026\n\nReply to this email if you have already paid.", wine));
        samples.put("11-purchase-order", new Sample("PURCHASE_ORDER", "Royal Suites Ikeja", "Purchase order PO-0042",
                "Purchase order PO-0042 from Royal Suites Ikeja.\n\n- 12 x Peak milk tins\n- 30 x Indomie cartons\n- 4 x Golden Penny semovita bags\n\nTotal: ₦186,500\nDeliver by: Fri 20 Nov\n\nNote: Please call the store keeper on arrival.", hospio));
        samples.put("12-report", new Sample("REPORT", "Hospio360", "Monthly revenue · October 2026",
                "Your scheduled report is ready.\n\nReport: Monthly revenue\nPeriod: October 2026\nHotels: All 4\n\nOpen it: https://web-staging.hospio360.com/reports?report=revenue", hospio));
        samples.put("13-decision", new Sample("DECISION", "Royal Suites Ikeja", "Your expense was approved",
                "Godwin Kwin approved your expense (Diesel for generator · ₦85,000). Go ahead and pay the supplier.", hospio));
        samples.put("14-guest-message", new Sample("MESSAGE", "St. Paul's Hotel", "About your airport pickup",
                "Hello Victor,\n\nYour driver will meet you at arrivals, gate 3, holding a sign with your name. Please call the front desk if your flight changes.\n\nSee you soon!", wine));
        for (Map.Entry<String, Sample> e : samples.entrySet()) {
            Sample s = e.getValue();
            EmailTemplates.Rendered r = new EmailTemplates().render(s.kind(), s.from(), s.subject(), s.text(), s.brand());
            Files.writeString(dir.resolve(e.getKey() + ".html"), r.html());
            Files.writeString(dir.resolve(e.getKey() + ".subject"), r.subject());
        }
    }
}
