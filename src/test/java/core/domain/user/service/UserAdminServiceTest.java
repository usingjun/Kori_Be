package core.domain.user.service;

import core.domain.aiuser.repository.AiPersonaRepository;
import core.domain.user.dto.UserSetupRequest;
import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ProfileImageService;
import core.global.enums.AiType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

import static core.domain.user.entity.UserTestBuilder.builder;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ProfileImageService profileImageService;
    @Mock
    private AiPersonaRepository aiPersonaRepository;
    @Mock
    private MultipartFile profileFile;

    @InjectMocks
    private UserAdminService userAdminService;

    @Test
    @DisplayName("AI 사용자 프로필 파일 교체 시 기존 folder를 선삭제하지 않고 직접 업로드에 위임한다")
    void updateAiUser_delegatesProfileReplacementWithoutPreDelete() {
        Long userId = 10L;
        User user = builder().id(userId).build();
        UserSetupRequest request = new UserSetupRequest(
                "Updated",
                "User",
                "Male",
                "01/01/2000",
                "KR",
                "intro",
                "FRIENDS",
                "ai@example.com",
                List.of("ko"),
                List.of("coding"),
                null
        );
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(aiPersonaRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(profileFile.isEmpty()).thenReturn(false);

        userAdminService.updateAiUser(
                userId,
                request,
                null,
                profileFile,
                "instruction",
                "background",
                AiType.ALL
        );

        verify(profileImageService).uploadUserProfileImage(userId, profileFile);
        verify(profileImageService, never()).deleteUserProfileImage(anyLong());
        verifyNoInteractions(imageRepository);
    }
}
