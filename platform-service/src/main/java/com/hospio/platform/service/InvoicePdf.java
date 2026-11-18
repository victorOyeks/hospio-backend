package com.hospio.platform.service;

import com.hospio.platform.entity.MonthlyInvoicePayment;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The monthly invoice and receipt PDFs (docs/plan-monthly-invoices.md), on the Oyeks Global letterhead of the contract templates:
 * "OYEKS GLOBAL LIMITED" in maroon, "Hospio360 is a product of Oyeks Global Limited", and the footer "Oyeks Global Ltd · RC 10001123 ·
 * Lagos, Nigeria · hello@oyeksglobal.com". No street address is ever printed.
 */
@Component
public class InvoicePdf {

    static final Color MAROON = new Color(0x4C, 0x09, 0x1E);
    static final Color GREY = new Color(0x6B, 0x72, 0x80);
    static final Color LINE = new Color(0xE5, 0xE7, 0xEB);
    static final Color SHADE = new Color(0xF8, 0xF4, 0xF5);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK).withZone(ZoneId.of("Africa/Lagos"));

    private static final Font H1 = new Font(Font.HELVETICA, 20, Font.BOLD, MAROON);
    private static final Font BRAND = new Font(Font.HELVETICA, 15, Font.BOLD, MAROON);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, GREY);
    private static final Font SMALL_I = new Font(Font.HELVETICA, 8, Font.ITALIC, GREY);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.BLACK);
    private static final Font BOLD = new Font(Font.HELVETICA, 9, Font.BOLD, Color.BLACK);
    private static final Font HEAD = new Font(Font.HELVETICA, 11, Font.BOLD, MAROON);
    private static final Font TOTAL = new Font(Font.HELVETICA, 12, Font.BOLD, MAROON);

    /** The invoice: header, bill-to, sections, totals, how to pay; then the day-by-day platform fee per location. */
    public byte[] invoice(MonthlyInvoiceService.InvoiceView v, List<DailyFeeService.DailyFeeView> days, String payLink) {
        return render(doc -> {
            letterhead(doc);
            PdfPTable top = table(new float[] {1.2f, 1f});
            PdfPCell left = cell(null, false);
            left.addElement(new Paragraph("INVOICE", H1));
            left.addElement(new Paragraph(v.number(), BOLD));
            left.addElement(new Paragraph(v.monthLabel() + " · Hospio360", BODY));
            top.addCell(left);
            PdfPCell right = cell(null, false);
            right.addElement(kv("Issued", DAY.format(v.issuedOn())));
            right.addElement(kv("Due", DAY.format(v.dueOn())));
            if (v.extendedUntil() != null) {
                right.addElement(kv("Extended to", DAY.format(v.extendedUntil())));
            }
            right.addElement(kv("Status", status(v.displayStatus())));
            top.addCell(right);
            doc.add(top);
            doc.add(space(6));
            doc.add(new Paragraph("Bill to", SMALL));
            doc.add(new Paragraph(v.businessName() == null ? "" : v.businessName(), BOLD));
            doc.add(space(8));

            section(doc, "Platform fee", v, "PLATFORM", true);
            section(doc, "Guest messages", v, "MESSAGES", false);
            section(doc, "Devices & internet", v, "DEVICES", false);
            section(doc, "Carried in", v, "CARRIED", false);
            section(doc, "Credits & adjustments", v, "ADJUSTMENT", false);

            PdfPTable totals = table(new float[] {2.2f, 1.8f});
            totals.setWidthPercentage(60);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalRow(totals, "Platform fee", v.sections().platform(), BODY);
            totalRow(totals, "Guest messages", v.sections().messages(), BODY);
            totalRow(totals, "Devices & internet", v.sections().devices(), BODY);
            if (v.sections().carriedIn().signum() != 0) {
                totalRow(totals, "Carried in", v.sections().carriedIn(), BODY);
            }
            if (v.sections().adjustments().signum() != 0) {
                totalRow(totals, "Credits & adjustments", v.sections().adjustments(), BODY);
            }
            totalRow(totals, v.vatPct().signum() > 0 ? "VAT (" + MonthlyInvoiceService.pct(v.vatPct()) + "%)" : "VAT (not charged)", v.vatAmount(), BODY);
            totalRow(totals, "Total", v.total(), TOTAL);
            if (v.paidAmount().signum() > 0) {
                totalRow(totals, "Paid", v.paidAmount().negate(), BODY);
                totalRow(totals, "Balance due", v.outstanding(), BOLD);
            }
            doc.add(totals);
            doc.add(space(10));
            if ("CARRIED_FORWARD".equals(v.status())) {
                doc.add(new Paragraph("This invoice is under the NGN 1,000 minimum: nothing is due now. It is carried forward to next month's invoice"
                        + (v.carriedIntoNumber() == null ? "." : " (" + v.carriedIntoNumber() + ")."), BODY));
            } else if (!"PAID".equals(v.status()) && !"VOID".equals(v.status())) {
                doc.add(new Paragraph("How to pay", HEAD));
                doc.add(new Paragraph("Pay the full amount by " + DAY.format(v.dueOn()) + " with \"Pay now\" in Hospio360 (Billing & invoices): card, bank transfer or "
                        + "USSD through Paystack.", BODY));
                doc.add(new Paragraph(payLink, SMALL));
                doc.add(new Paragraph("If it is still unpaid after " + DAY.format(v.lastDay()) + ", from " + DAY.format(v.lastDay().plusDays(1))
                        + " 06:00 card/transfer POS payments, new sales and new bookings pause until it is paid. Signing in, check-out, cash on existing bills, "
                        + "reports and paying the invoice keep working.", SMALL));
            } else if ("PAID".equals(v.status())) {
                doc.add(new Paragraph("Paid in full" + (v.paidAt() == null ? "" : " on " + WHEN.format(v.paidAt())) + ". Thank you.", BOLD));
            } else {
                doc.add(new Paragraph("This invoice was voided" + (v.voidReason() == null ? "" : ": " + v.voidReason())
                        + (v.replacedByNumber() == null ? "." : ". It is replaced by " + v.replacedByNumber() + "."), BOLD));
            }
            doc.add(space(6));
            doc.add(new Paragraph("Amounts in Nigerian naira (NGN). The platform fee is the agreed % of each location's daily sales as recorded in Hospio360, "
                    + "excluding VAT. Query any line within 7 days of issue: hello@oyeksglobal.com.", SMALL_I));

            if (!days.isEmpty()) {
                doc.newPage();
                doc.add(new Paragraph("Platform fee, day by day · " + v.number(), HEAD));
                doc.add(space(4));
                Map<String, List<DailyFeeService.DailyFeeView>> byHotel = new LinkedHashMap<>();
                days.forEach(d -> byHotel.computeIfAbsent(d.hotelName() == null ? "Location" : d.hotelName(), k -> new java.util.ArrayList<>()).add(d));
                for (Map.Entry<String, List<DailyFeeService.DailyFeeView>> e : byHotel.entrySet()) {
                    doc.add(new Paragraph(e.getKey(), BOLD));
                    PdfPTable t = table(new float[] {1.4f, 1.6f, 0.7f, 1.2f, 1.2f});
                    head(t, "Sale day", "Sales (NGN)", "Rate", "Fee (NGN)", "Devices (NGN)");
                    BigDecimal sales = BigDecimal.ZERO;
                    BigDecimal fee = BigDecimal.ZERO;
                    BigDecimal dev = BigDecimal.ZERO;
                    for (DailyFeeService.DailyFeeView d : e.getValue()) {
                        row(t, BODY, DAY.format(d.date()), MonthlyInvoiceService.naira(d.sales()), MonthlyInvoiceService.pct(d.rate()) + "%",
                                MonthlyInvoiceService.naira(d.fee()), d.deviceFee() == null || d.deviceFee().signum() == 0 ? "" : MonthlyInvoiceService.naira(d.deviceFee()));
                        sales = sales.add(d.sales());
                        fee = fee.add(d.fee());
                        dev = dev.add(d.deviceFee() == null ? BigDecimal.ZERO : d.deviceFee());
                    }
                    row(t, BOLD, "Total", MonthlyInvoiceService.naira(sales), "", MonthlyInvoiceService.naira(fee), dev.signum() == 0 ? "" : MonthlyInvoiceService.naira(dev));
                    doc.add(t);
                    doc.add(space(6));
                }
            }
        });
    }

    /** One payment's receipt. */
    public byte[] receipt(MonthlyInvoiceService.InvoiceView v, MonthlyInvoicePayment p) {
        return render(doc -> {
            letterhead(doc);
            doc.add(new Paragraph("RECEIPT", H1));
            doc.add(new Paragraph(p.getReceiptNumber(), BOLD));
            doc.add(space(8));
            PdfPTable t = table(new float[] {1f, 2f});
            t.setWidthPercentage(70);
            t.setHorizontalAlignment(Element.ALIGN_LEFT);
            row(t, BODY, "Received from", v.businessName() == null ? "" : v.businessName());
            row(t, BODY, "For invoice", v.number() + " (" + v.monthLabel() + ")");
            row(t, BODY, "Amount", "NGN " + MonthlyInvoiceService.naira(p.getAmount()));
            row(t, BODY, "Paid by", MonthlyInvoiceService.label(p.getVia()));
            row(t, BODY, "Reference", p.getReference());
            row(t, BODY, "Date", DAY.format(p.getPaidOn()));
            BigDecimal paidUpTo = v.payments().stream().filter(x -> x.createdAt() != null && p.getCreatedAt() != null && !x.createdAt().isAfter(p.getCreatedAt()))
                    .map(MonthlyInvoiceService.PaymentView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            row(t, BODY, "Invoice total", "NGN " + MonthlyInvoiceService.naira(v.total()));
            row(t, BOLD, "Balance after this payment", "NGN " + MonthlyInvoiceService.naira(v.total().subtract(paidUpTo).max(BigDecimal.ZERO)));
            if (p.getRecordedByName() != null && !"Paystack".equals(p.getRecordedByName())) {
                row(t, BODY, "Recorded by", p.getRecordedByName());
            }
            doc.add(t);
            doc.add(space(10));
            doc.add(new Paragraph("Thank you for your payment.", BOLD));
        });
    }

    // ---- layout ------------------------------------------------------------------------------------------------

    private interface Body {
        void write(Document doc) throws Exception;
    }

    private byte[] render(Body body) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 42, 42, 40, 50);
            PdfWriter w = PdfWriter.getInstance(doc, out);
            w.setPageEvent(new Footer());
            doc.open();
            body.write(doc);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("The PDF could not be made: " + e.getMessage(), e);
        }
    }

    private static void letterhead(Document doc) throws Exception {
        Paragraph brand = new Paragraph("OYEKS GLOBAL LIMITED", BRAND);
        brand.setAlignment(Element.ALIGN_CENTER);
        doc.add(brand);
        Paragraph sub = new Paragraph("Hospio360 is a product of Oyeks Global Limited", SMALL_I);
        sub.setAlignment(Element.ALIGN_CENTER);
        doc.add(sub);
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(""));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(MAROON);
        c.setBorderWidth(1.5f);
        c.setFixedHeight(6);
        rule.addCell(c);
        doc.add(rule);
        doc.add(space(10));
    }

    private static final class Footer extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfPTable f = new PdfPTable(1);
            f.setTotalWidth(document.right() - document.left());
            PdfPCell c = new PdfPCell(new Phrase(MonthlyInvoiceService.COMPANY_LINE + "  ·  Page " + writer.getPageNumber(), SMALL));
            c.setBorder(Rectangle.TOP);
            c.setBorderColor(LINE);
            c.setHorizontalAlignment(Element.ALIGN_CENTER);
            c.setPaddingTop(4);
            f.addCell(c);
            f.writeSelectedRows(0, -1, document.left(), document.bottom() - 8, writer.getDirectContent());
        }
    }

    private static void section(Document doc, String title, MonthlyInvoiceService.InvoiceView v, String section, boolean platform) throws Exception {
        List<MonthlyInvoiceService.LineView> ls = v.lines().stream().filter(l -> section.equals(l.section())).toList();
        if (ls.isEmpty()) {
            return;
        }
        doc.add(new Paragraph(title, HEAD));
        PdfPTable t = platform ? table(new float[] {2.2f, 1.4f, 0.6f, 1.2f}) : table(new float[] {3.6f, 1.2f});
        if (platform) {
            head(t, "Location", "Sales (NGN)", "Rate", "Fee (NGN)");
        } else {
            head(t, "Item", "Amount (NGN)");
        }
        for (MonthlyInvoiceService.LineView l : ls) {
            if (platform) {
                PdfPCell name = cell(null, true);
                name.addElement(new Paragraph(l.hotelName() == null ? l.label() : l.hotelName(), BODY));
                if (l.detail() != null) {
                    name.addElement(new Paragraph(l.detail(), SMALL));
                }
                t.addCell(name);
                t.addCell(right(MonthlyInvoiceService.naira(l.baseAmount()), BODY));
                t.addCell(right(l.rate() == null ? "various" : MonthlyInvoiceService.pct(l.rate()) + "%", BODY));
                t.addCell(right(MonthlyInvoiceService.naira(l.amount()), BODY));
            } else {
                PdfPCell name = cell(null, true);
                name.addElement(new Paragraph(l.label(), BODY));
                if (l.detail() != null) {
                    name.addElement(new Paragraph(l.detail(), SMALL));
                }
                t.addCell(name);
                t.addCell(right(MonthlyInvoiceService.naira(l.amount()), BODY));
            }
        }
        doc.add(t);
        doc.add(space(6));
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(2);
        return t;
    }

    private static PdfPCell cell(String text, boolean line) {
        PdfPCell c = text == null ? new PdfPCell() : new PdfPCell(new Phrase(text, BODY));
        c.setBorder(line ? Rectangle.BOTTOM : Rectangle.NO_BORDER);
        c.setBorderColor(LINE);
        c.setPadding(4);
        return c;
    }

    private static PdfPCell right(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setPadding(4);
        return c;
    }

    private static void head(PdfPTable t, String... titles) {
        for (int k = 0; k < titles.length; k++) {
            PdfPCell c = new PdfPCell(new Phrase(titles[k], new Font(Font.HELVETICA, 8, Font.BOLD, MAROON)));
            c.setBackgroundColor(SHADE);
            c.setBorder(Rectangle.NO_BORDER);
            c.setPadding(4);
            c.setHorizontalAlignment(k == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            t.addCell(c);
        }
    }

    private static void row(PdfPTable t, Font f, String... values) {
        for (int k = 0; k < values.length; k++) {
            PdfPCell c = k == 0 ? new PdfPCell(new Phrase(values[k], f)) : right(values[k], f);
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(LINE);
            c.setPadding(4);
            t.addCell(c);
        }
    }

    private static void totalRow(PdfPTable t, String label, BigDecimal amount, Font f) {
        PdfPCell l = new PdfPCell(new Phrase(label, f));
        l.setBorder(Rectangle.NO_BORDER);
        l.setPadding(3);
        t.addCell(l);
        PdfPCell a = new PdfPCell(new Phrase("NGN " + MonthlyInvoiceService.naira(amount), f));
        a.setBorder(Rectangle.NO_BORDER);
        a.setHorizontalAlignment(Element.ALIGN_RIGHT);
        a.setPadding(3);
        t.addCell(a);
    }

    private static Paragraph kv(String k, String v) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(k + ": ", SMALL));
        p.add(new Chunk(v, BOLD));
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static Paragraph space(float h) {
        Paragraph p = new Paragraph(" ", new Font(Font.HELVETICA, h));
        p.setLeading(h);
        return p;
    }

    static String status(String s) {
        return switch (s == null ? "" : s) {
            case "DUE", "DUE_SOON" -> "Due";
            case "OVERDUE" -> "Overdue";
            case "RESTRICTED" -> "Overdue · paused";
            case "PAID" -> "Paid";
            case "CARRIED_FORWARD" -> "Carried forward";
            case "VOID" -> "Void";
            default -> s;
        };
    }
}
