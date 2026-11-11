package com.hospio.tenant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospio.common.delivery.Delivery;
import com.hospio.tenant.dto.HotelResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The handover pack email: the business, its hotels with room counts, every login created during onboarding
 * grouped by role (never a password: those travel in their own TEMP_PASSWORD deliveries), and where to sign in.
 */
@Component
public class HandoverPack {

    private final String hotelUrl;

    public HandoverPack(@Value("${hospio.apps.hotel-url:https://web-staging.hospio360.com}") String hotelUrl) {
        this.hotelUrl = hotelUrl != null && hotelUrl.endsWith("/") ? hotelUrl.substring(0, hotelUrl.length() - 1) : hotelUrl;
    }

    public String signInUrl() {
        return hotelUrl;
    }

    public Delivery email(String businessId, String businessName, String ownerName, String ownerEmail, List<HotelResponse> hotels,
                          List<JsonNode> logins) {
        return Delivery.email(ownerEmail, "Your Hospio360 handover pack \u00b7 " + businessName,
                text(businessName, ownerName, hotels, logins), Delivery.Kind.HANDOVER_PACK, businessId);
    }

    public String text(String businessName, String ownerName, List<HotelResponse> hotels, List<JsonNode> logins) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hello ").append(ownerName == null ? "there" : ownerName).append(",\n\n");
        sb.append("Hospio360 has finished setting up ").append(businessName).append(" on Hospio360. Here is what is ready for you.\n\n");

        sb.append("HOTELS\n");
        if (hotels == null || hotels.isEmpty()) {
            sb.append("- none yet\n");
        } else {
            for (HotelResponse h : hotels) {
                sb.append("- ").append(h.name());
                if (h.city() != null && !h.city().isBlank()) {
                    sb.append(", ").append(h.city());
                }
                sb.append(" \u00b7 ").append(h.roomCount()).append(h.roomCount() == 1 ? " room" : " rooms").append('\n');
            }
        }

        sb.append("\nLOGINS\n");
        Map<String, String> hotelNames = hotels == null ? Map.of()
                : hotels.stream().collect(Collectors.toMap(HotelResponse::id, HotelResponse::name, (a, b) -> a));
        Map<String, List<JsonNode>> byRole = logins == null ? Map.of() : logins.stream()
                .collect(Collectors.groupingBy(l -> l.path("role").asText("OTHER"), LinkedHashMap::new, Collectors.toList()));
        if (byRole.isEmpty()) {
            sb.append("- none yet\n");
        }
        byRole.forEach((role, people) -> {
            sb.append(label(role)).append('\n');
            for (JsonNode p : people) {
                sb.append("- ").append(p.path("fullName").asText("")).append(" \u00b7 ").append(p.path("email").asText(""));
                String hotelId = p.path("hotelId").asText(null);
                if (hotelId != null && !hotelId.isBlank()) {
                    sb.append(" \u00b7 ").append(hotelNames.getOrDefault(hotelId, hotelId));
                }
                sb.append('\n');
            }
        });
        sb.append("Each person has their own temporary password and invite link, sent separately.\n");

        sb.append("\nSIGN IN\n").append(hotelUrl).append('\n');
        sb.append("\nOn first sign-in you will choose a password, review this handover and accept it; the business then goes live.\n");
        return sb.toString();
    }

    static String label(String role) {
        return switch (role) {
            case "OWNER" -> "Owner";
            case "MANAGER" -> "Managers";
            case "FRONT_OFFICE" -> "Front office";
            case "HOUSEKEEPING" -> "Housekeeping";
            case "LAUNDRY" -> "Laundry";
            case "MAINTENANCE" -> "Maintenance";
            case "KITCHEN" -> "Kitchen";
            case "BAR" -> "Bar";
            case "INVENTORY" -> "Inventory";
            case "FINANCE" -> "Finance";
            default -> Function.<String>identity().apply(role.charAt(0) + role.substring(1).toLowerCase().replace('_', ' '));
        };
    }
}
