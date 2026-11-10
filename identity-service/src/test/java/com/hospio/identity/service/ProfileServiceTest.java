package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.integrations.ObjectStorage;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.identity.dto.ProfileDtos;
import com.hospio.identity.entity.UserAccount;
import com.hospio.identity.repository.UserAccountRepository;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** My profile (docs/profile.md): what may be changed and how it's checked, the photo's processing and who may see it. */
class ProfileServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final UserService userService = mock(UserService.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final ProfileService profile = new ProfileService(users, userService, storage);

    private UserAccount ada() {
        return UserAccount.builder().id("ada").businessId("b1").fullName("Ada Obi").email("ada@x.ng").phone("+234 803 555 0192")
                .phoneVerified(true).build();
    }

    @Test
    void namePhoneAndLanguageAreChecked() {
        UserAccount ada = ada();
        ProfileService.apply(ada, new ProfileDtos.UpdateProfile("  Ada   Obi-Nwosu ", null, "YO"), k -> false);
        assertThat(ada.getFullName()).isEqualTo("Ada Obi-Nwosu");
        assertThat(ada.getLanguage()).isEqualTo("yo");
        assertThat(ada.isPhoneVerified()).isTrue();

        assertThatThrownBy(() -> ProfileService.apply(ada(), new ProfileDtos.UpdateProfile("1", null, null), k -> false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("real name");
        assertThatThrownBy(() -> ProfileService.apply(ada(), new ProfileDtos.UpdateProfile("--", null, null), k -> false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ProfileService.apply(ada(), new ProfileDtos.UpdateProfile(null, "+234 000 000 0000", null), k -> false))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("real phone");
        assertThatThrownBy(() -> ProfileService.apply(ada(), new ProfileDtos.UpdateProfile(null, "12345", null), k -> false))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ProfileService.apply(ada(), new ProfileDtos.UpdateProfile(null, null, "fr"), k -> false))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aPhoneSomeoneElseUsesIsAConflictAndANewPhoneIsUnverified() {
        assertThatThrownBy(() -> ProfileService.apply(ada(), new ProfileDtos.UpdateProfile(null, "0809 111 2222", null),
                k -> k.equals("8091112222"))).isInstanceOf(ConflictException.class).hasMessageContaining("already in use");
        // The same number written another way is not a change (and not a conflict with oneself).
        UserAccount same = ada();
        ProfileService.apply(same, new ProfileDtos.UpdateProfile(null, "08035550192", null), k -> true);
        assertThat(same.isPhoneVerified()).isTrue();
        UserAccount changed = ada();
        ProfileService.apply(changed, new ProfileDtos.UpdateProfile(null, "+234 809 111 2222", null), k -> false);
        assertThat(changed.getPhone()).isEqualTo("+234 809 111 2222");
        assertThat(changed.isPhoneVerified()).isFalse();
        UserAccount cleared = ada();
        ProfileService.apply(cleared, new ProfileDtos.UpdateProfile(null, "", ""), k -> false);
        assertThat(cleared.getPhone()).isNull();
        assertThat(cleared.getLanguage()).isNull();
    }

    private static byte[] image(int w, int h, String format) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void photosAreCroppedSquareAndOnlyRealImagesPass() throws IOException {
        BufferedImage png = ImageIO.read(new ByteArrayInputStream(ProfileService.squareJpeg(image(900, 400, "png"))));
        assertThat(png.getWidth()).isEqualTo(256);
        assertThat(png.getHeight()).isEqualTo(256);
        assertThat(ProfileService.squareJpeg(image(300, 1200, "jpg"))).isNotEmpty();
        assertThatThrownBy(() -> ProfileService.squareJpeg("%PDF-1.4 not an image".getBytes())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ProfileService.squareJpeg(image(50, 50, "gif"))).isInstanceOf(IOException.class);
    }

    @Test
    void uploadRefusesBigOrFakeFilesAndReplacesTheOldPhoto() throws IOException {
        UserAccount ada = ada();
        ada.setPhotoKey("identity/b1/photos/ada-1.jpg");
        when(users.findById("ada")).thenReturn(Optional.of(ada));
        assertThatThrownBy(() -> profile.uploadPhoto("ada", new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[2 * 1024 * 1024 + 1])))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("2 MB");
        assertThatThrownBy(() -> profile.uploadPhoto("ada", new MockMultipartFile("file", "x.png", "image/png", "<script>".getBytes())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("JPEG or PNG");
        verify(storage, never()).put(anyString(), any(), anyString());

        profile.uploadPhoto("ada", new MockMultipartFile("file", "me.png", "image/png", image(400, 300, "png")));
        assertThat(ada.getPhotoKey()).startsWith("identity/b1/photos/ada-").endsWith(".jpg");
        assertThat(ada.getPhotoVersion()).isNotNull();
        verify(storage).put(eq(ada.getPhotoKey()), any(), eq("image/jpeg"));
        verify(storage).delete("identity/b1/photos/ada-1.jpg");

        profile.removePhoto("ada");
        assertThat(ada.getPhotoKey()).isNull();
        assertThat(ada.getPhotoVersion()).isNull();
    }

    @Test
    void aPhotoIsSeenByItsOwnerTheirBusinessAndThePlatformOnly() {
        UserAccount ada = ada();
        ada.setPhotoKey("k");
        when(users.findById("ada")).thenReturn(Optional.of(ada));
        when(storage.get("k")).thenReturn(Optional.of(new byte[] {1}));
        HospioUserDetails colleague = new HospioUserDetails("ola", "o@x", "Ola", "b1", List.of("h1"), Map.of(Role.BAR, AccessLevel.FULL), "hotel");
        HospioUserDetails stranger = new HospioUserDetails("eve", "e@y", "Eve", "b2", List.of("h9"), Map.of(Role.MANAGER, AccessLevel.FULL), "hotel");
        HospioUserDetails admin = new HospioUserDetails("adm", "a@h", "Adm", null, List.of(), Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), "platform");
        assertThat(profile.photo(colleague, "ada")).containsExactly(1);
        assertThat(profile.photo(admin, "ada")).containsExactly(1);
        assertThatThrownBy(() -> profile.photo(stranger, "ada")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void preferencesAreSavedAndReadBack() {
        UserAccount ada = ada();
        when(users.findById("ada")).thenReturn(Optional.of(ada));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ProfileDtos.NotificationPreferences p = profile.updatePreferences("ada", new ProfileDtos.UpdatePreferences(Map.of("reports", false)));
        assertThat(p.inApp()).isTrue();
        assertThat(p.email()).containsEntry("reports", false).containsEntry("money", true);
        assertThat(p.sms()).isEqualTo("COMING_SOON");
        assertThat(profile.preferences("ada").email()).containsEntry("reports", false);
    }
}
