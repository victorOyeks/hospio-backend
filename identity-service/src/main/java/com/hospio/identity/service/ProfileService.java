package com.hospio.identity.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.exception.UnauthorizedException;
import com.hospio.common.integrations.ObjectStorage;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.identity.dto.MeResponse;
import com.hospio.identity.dto.ProfileDtos;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.UserAccountRepository;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * My profile (docs/profile.md): the caller's own name, phone and language, their photo and their email preferences. The
 * user id always comes from the access token. Rows are read as the platform: a platform admin or marketer has no business
 * and the row-level policy would hide their own row otherwise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    public static final List<String> LANGUAGES = List.of("en", "yo", "ha", "ig", "pcm");
    static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;
    static final int PHOTO_SIZE = 256;
    /** Larger images are decoded subsampled, so a small file of huge dimensions can't exhaust memory. */
    static final long MAX_PIXELS = 40_000_000L;

    private final UserAccountRepository users;
    private final UserService userService;
    private final ObjectStorage storage;

    @Transactional
    public MeResponse update(String userId, ProfileDtos.UpdateProfile request) {
        system(() -> {
            UserAccount user = find(userId);
            apply(user, request, key -> users.countByPhoneKey(key) > 0);
            users.save(user);
            return null;
        });
        return userService.me(userId);
    }

    /** Validates and applies the request to the account; {@code phoneTaken} answers for a phone key. Package-visible for tests. */
    static void apply(UserAccount user, ProfileDtos.UpdateProfile request, java.util.function.Predicate<String> phoneTaken) {
        if (request == null) {
            throw new BadRequestException("Nothing to change");
        }
        if (request.fullName() != null) {
            String name = request.fullName().trim().replaceAll("\\s+", " ");
            if (!Phones.meaningfulName(name)) {
                throw new BadRequestException("Full name must be a real name, with letters");
            }
            if (name.length() > 150) {
                throw new BadRequestException("Full name is too long");
            }
            user.setFullName(name);
        }
        if (request.phone() != null) {
            String phone = Phones.clean(request.phone(), "Phone");
            String before = user.getPhone() == null ? null : Phones.key(user.getPhone());
            String after = phone == null ? null : Phones.key(phone);
            if (!Objects.equals(before, after)) {
                if (after != null && phoneTaken.test(after)) {
                    throw new ConflictException("This phone number is already in use on another account");
                }
                user.setPhoneVerified(false);
            }
            user.setPhone(phone);
        }
        if (request.language() != null) {
            String lang = request.language().trim().toLowerCase(java.util.Locale.ROOT);
            if (lang.isEmpty()) {
                user.setLanguage(null);
            } else if (!LANGUAGES.contains(lang)) {
                throw new BadRequestException("Pick a language from the list");
            } else {
                user.setLanguage(lang);
            }
        }
    }

    // ---- photo -------------------------------------------------------------------------------------------

    @Transactional
    public MeResponse uploadPhoto(String userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Pick a photo");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new BadRequestException("The photo is too big · keep it under 2 MB");
        }
        byte[] jpeg;
        try {
            jpeg = squareJpeg(file.getBytes());
        } catch (IOException e) {
            throw new BadRequestException("The photo must be a JPEG or PNG image");
        }
        system(() -> {
            UserAccount user = find(userId);
            long version = Math.max(System.currentTimeMillis(), user.getPhotoVersion() == null ? 0 : user.getPhotoVersion() + 1);
            String key = "identity/" + (user.getBusinessId() == null ? "platform" : user.getBusinessId()) + "/photos/" + user.getId()
                    + "-" + version + ".jpg";
            try {
                storage.put(key, jpeg, "image/jpeg");
            } catch (Exception e) {
                log.warn("Could not store the photo of {}: {}", user.getId(), e.getMessage());
                throw new BadRequestException("Couldn't save the photo right now · try again");
            }
            String old = user.getPhotoKey();
            user.setPhotoKey(key);
            user.setPhotoVersion(version);
            users.save(user);
            deleteQuietly(old);
            return null;
        });
        return userService.me(userId);
    }

    @Transactional
    public MeResponse removePhoto(String userId) {
        system(() -> {
            UserAccount user = find(userId);
            String old = user.getPhotoKey();
            user.setPhotoKey(null);
            user.setPhotoVersion(null);
            users.save(user);
            deleteQuietly(old);
            return null;
        });
        return userService.me(userId);
    }

    /** Someone's photo: themselves, anyone of the same business, or the platform. 404 otherwise or without one. */
    @Transactional(readOnly = true)
    public byte[] photo(HospioUserDetails caller, String userId) {
        UserAccount user = system(() -> users.findById(userId)).orElseThrow(() -> new ResourceNotFoundException("No photo"));
        boolean allowed = caller.getUserId().equals(user.getId()) || caller.isPlatform()
                || (caller.getBusinessId() != null && caller.getBusinessId().equals(user.getBusinessId()));
        if (!allowed || user.getPhotoKey() == null) {
            throw new ResourceNotFoundException("No photo");
        }
        return storage.get(user.getPhotoKey()).orElseThrow(() -> new ResourceNotFoundException("No photo"));
    }

    /**
     * Decodes the upload as an image (anything else is refused), crops the middle square and scales it to
     * {@link #PHOTO_SIZE} JPEG on white. Re-encoding drops EXIF and anything else riding in the file.
     */
    static byte[] squareJpeg(byte[] bytes) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                throw new IOException("unreadable");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("not an image");
            }
            ImageReader reader = readers.next();
            String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
            if (!format.equals("jpeg") && !format.equals("png")) {
                reader.dispose();
                throw new IOException("format " + format);
            }
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                ImageReadParam param = reader.getDefaultReadParam();
                int side = Math.min(w, h);
                int sub = Math.max(1, side / (PHOTO_SIZE * 2));
                while ((long) (w / sub) * (h / sub) > MAX_PIXELS) {
                    sub++;
                }
                if (sub > 1) {
                    param.setSourceSubsampling(sub, sub, 0, 0);
                }
                // Read only the middle square.
                int x = (w - side) / 2;
                int y = (h - side) / 2;
                param.setSourceRegion(new java.awt.Rectangle(x, y, side, side));
                BufferedImage src = reader.read(0, param);
                BufferedImage out = new BufferedImage(PHOTO_SIZE, PHOTO_SIZE, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = out.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, PHOTO_SIZE, PHOTO_SIZE);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, PHOTO_SIZE, PHOTO_SIZE, null);
                g.dispose();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                if (!ImageIO.write(out, "jpg", buf)) {
                    throw new IOException("no jpeg writer");
                }
                return buf.toByteArray();
            } catch (RuntimeException e) {
                throw new IOException(e);
            } finally {
                reader.dispose();
            }
        }
    }

    private void deleteQuietly(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        try {
            storage.delete(key);
        } catch (Exception e) {
            log.warn("Could not delete the old photo {}: {}", key, e.getMessage());
        }
    }

    // ---- notification preferences ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ProfileDtos.NotificationPreferences preferences(String userId) {
        return system(() -> view(find(userId)));
    }

    @Transactional
    public ProfileDtos.NotificationPreferences updatePreferences(String userId, ProfileDtos.UpdatePreferences request) {
        return system(() -> {
            UserAccount user = find(userId);
            user.setEmailOff(NotificationPrefs.apply(user.getEmailOff(), request == null ? null : request.email()));
            users.save(user);
            return view(user);
        });
    }

    private static ProfileDtos.NotificationPreferences view(UserAccount user) {
        return new ProfileDtos.NotificationPreferences(true, NotificationPrefs.email(user.getEmailOff()), "COMING_SOON", "COMING_SOON");
    }

    private UserAccount find(String userId) {
        return users.findById(userId).orElseThrow(() -> new UnauthorizedException("Account not found"));
    }

    private static <T> T system(java.util.function.Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("profile"), work::get);
    }
}
