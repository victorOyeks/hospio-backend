package com.hospio.hotelconfig.dto;

/**
 * The admin portal's hotel card: the WhatsApp number and POS provider set up, and how much of the setup
 * catalogue the hotel has changed from the defaults (0-100).
 */
public record HotelOverviewResponse(String whatsappNumber, String posProvider, int setupCompletePct) {
}
