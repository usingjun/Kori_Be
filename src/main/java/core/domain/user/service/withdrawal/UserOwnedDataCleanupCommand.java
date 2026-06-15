package core.domain.user.service.withdrawal;

import core.domain.notification.repository.NotificationRepository;
import core.domain.user.repository.AdminOtpRepository;
import core.domain.user.repository.BlockRepository;
import core.domain.userdevicetoken.repository.UserDeviceTokenRepository;
import core.domain.usernotificationsetting.repository.UserNotificationSettingRepository;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.enums.common.ImageType;
import core.global.userfeedback.UserFeedbackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserOwnedDataCleanupCommand implements UserWithdrawalCommand {

    private final ImageRepository imageRepository;
    private final ImageService imageService;
    private final AdminOtpRepository adminOtpRepository;
    private final BlockRepository blockRepository;
    private final UserNotificationSettingRepository userNotificationSettingRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final UserFeedbackRepository userFeedbackRepository;

    @Override
    public int order() {
        return UserWithdrawalCommandOrder.USER_OWNED_DATA;
    }

    @Override
    public void execute(UserWithdrawalContext context) {
        Long userId = context.userId();
        imageRepository.deleteAllByImageTypeAndRelatedId(ImageType.USER, userId);
        imageService.deleteUserProfileImage(userId);
        adminOtpRepository.deleteAllByUserId(userId);
        blockRepository.deleteAllByUserOrBlocked(context.user());
        userNotificationSettingRepository.deleteAllByUserId(userId);
        userDeviceTokenRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByUserId(userId);
        notificationRepository.deleteAllByActorId(userId);
        userFeedbackRepository.deleteAllByUserIdExplicit(userId);
    }
}
