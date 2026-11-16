package com.hospio.common.integrations.settings;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.SecretBox;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a provider's settings record into its saved values (key → text) and back, checks a typed-in value against its field, masks
 * what is shown, and lays saved settings over the server's environment ({@link #overlay}). Records and fields are matched by name.
 */
public final class IntegrationCatalogue {

    private IntegrationCatalogue() {
    }

    /** The record's saved values as text, in field order; a null component (not saved) is left out. */
    public static Map<String, String> values(IntegrationSettings settings) {
        Map<String, String> out = new LinkedHashMap<>();
        if (settings == null) {
            return out;
        }
        for (RecordComponent c : settings.getClass().getRecordComponents()) {
            Object v = get(c, settings);
            if (v != null) {
                out.put(c.getName(), String.valueOf(v));
            }
        }
        return out;
    }

    /** The provider's record from saved text values (already checked by {@link #normalize}); a key not in the map is null. */
    public static IntegrationSettings build(IntegrationProvider provider, Map<String, String> values) {
        RecordComponent[] components = provider.type().getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            String raw = values.get(components[i].getName());
            Class<?> t = components[i].getType();
            args[i] = raw == null ? null : t == Integer.class ? Integer.valueOf(raw) : t == Boolean.class ? Boolean.valueOf(raw) : raw;
        }
        return construct(provider.type(), components, args);
    }

    /**
     * The value as it is saved: trimmed, a URL with http(s) and a host (no trailing slash), a NUMBER a whole number, a BOOLEAN "true" or
     * "false", a choice one of its options. Anything else is refused with a sentence the admin can act on.
     */
    public static String normalize(IntegrationProvider provider, IntegrationField field, String raw) {
        String v = raw == null ? "" : raw.trim();
        String name = field.label() + " (" + provider.label() + ")";
        switch (field.type()) {
            case URL -> {
                URI uri;
                try {
                    uri = URI.create(v);
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException(name + " isn't a web address. Write it in full, e.g. https://api.example.com");
                }
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new BadRequestException(name + " isn't a web address. Write it in full, e.g. https://api.example.com");
                }
                return v.replaceAll("/+$", "");
            }
            case NUMBER -> {
                try {
                    int n = Integer.parseInt(v);
                    if (n < 1 || n > 65535) {
                        throw new NumberFormatException();
                    }
                    return String.valueOf(n);
                } catch (NumberFormatException e) {
                    throw new BadRequestException(name + " must be a whole number from 1 to 65535");
                }
            }
            case BOOLEAN -> {
                String b = v.toLowerCase(Locale.ROOT);
                if (b.equals("true") || b.equals("false")) {
                    return b;
                }
                throw new BadRequestException(name + " is on or off: send true or false");
            }
            default -> {
                if (field.options() != null) {
                    String upper = v.toUpperCase(Locale.ROOT);
                    if (!field.options().contains(upper)) {
                        throw new BadRequestException(name + " must be one of: " + String.join(", ", field.options()));
                    }
                    return upper;
                }
                if (v.length() > 4000) {
                    throw new BadRequestException(name + " is too long (4000 characters at most)");
                }
                return v;
            }
        }
    }

    /** What the screen shows of a saved value: a secret masked ("••••••3456"), anything else as saved. */
    public static String display(IntegrationField field, String value) {
        if (value == null) {
            return null;
        }
        return field.secret() ? SecretBox.mask(value) : value;
    }

    /**
     * The settings in force: each field saved in the portal, else the server's value for it ({@code env}). A saved "false" switches a
     * channel off even when the server has it on.
     */
    @SuppressWarnings("unchecked")
    public static <T extends IntegrationSettings> T overlay(T saved, T env) {
        if (saved == null) {
            return env;
        }
        if (env == null) {
            return saved;
        }
        RecordComponent[] components = env.getClass().getRecordComponents();
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Object s = get(components[i], saved);
            args[i] = s != null ? s : get(components[i], env);
        }
        return (T) construct((Class<? extends IntegrationSettings>) env.getClass(), components, args);
    }

    private static Object get(RecordComponent c, Object r) {
        try {
            return c.getAccessor().invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't read " + c.getName(), e);
        }
    }

    private static IntegrationSettings construct(Class<? extends IntegrationSettings> type, RecordComponent[] components, Object[] args) {
        try {
            Constructor<? extends IntegrationSettings> canonical =
                    type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            return canonical.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't build " + type.getSimpleName(), e);
        }
    }
}
