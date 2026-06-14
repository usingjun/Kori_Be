package core.domain.user.controller;

import core.domain.admin.service.AdminAuthService;
import core.domain.user.dto.*;
import core.domain.user.service.UserService;
import core.domain.user.service.UserWithdrawalService;
import core.global.apple.dto.AppleLoginByCodeRequest;
import core.global.apple.dto.WithdrawIsApple;
import core.global.apple.service.AppleAuthService;
import core.global.config.CustomUserDetails;
import core.global.docs.annotations.AuthErrorDocs;
import core.global.docs.annotations.GlobalErrorDocs;
import core.global.docs.annotations.ImageErrorCodeDocs;
import core.global.docs.annotations.UserErrorDocs;
import core.global.dto.*;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.enums.errorcode.GlobalErrorCode;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.metrics.FeatureUsageMetrics;
import core.global.security.JwtTokenProvider;
import core.global.service.GeoService;
import core.global.service.GoogleAuthService;
import core.global.service.PasswordService;
import core.global.util.CookieUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@Tag(name = "User", description = "사용자 회원가입,로그인 관련 API")
@RestController
@RequestMapping("/api/v1/member")
@GlobalErrorDocs({GlobalErrorCode.INTERNAL_SERVER_ERROR, GlobalErrorCode.INVALID_INPUT, GlobalErrorCode.INVALID_JSON, GlobalErrorCode.METHOD_NOT_ALLOWED})
@RequiredArgsConstructor
@Slf4j
public class LoginRegisterController {
    private final UserService userService;
    private final UserWithdrawalService userWithdrawalService;
    private final PasswordService passwordService;
    private final AppleAuthService appleAuthService;
    private final ApplicationEventPublisher publisher;
    private final GoogleAuthService googleAuthService;
    private final FeatureUsageMetrics featureUsageMetrics;
    private final CookieUtil cookieUtil;
    private final GeoService geoService;
    private final JwtTokenProvider jwtTokenProvider;
    private final AdminAuthService adminAuthService;

    @PostMapping("/doLogin")
    @Operation(summary = "일반 로그인")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.AUTHENTICATION_FAILED})
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody EmailLoginDto req) {
        AuthResponse response = userService.login(req);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "구글 소셜 로그인,회원가입", description = "앱에서 받은 인증 코드로 구글 로그인을 처리하고 JWT를 발급합니다.")
    @PostMapping("/google/app-login")
    @UserErrorDocs({UserErrorCode.DUPLICATE_EMAIL_PROVIDER_MISMATCH,})
    public ResponseEntity<ApiResponse<LoginResponseDto>> googleLogin(@RequestBody GoogleLoginReq req) {
        LoginResponseDto responseDto = googleAuthService.processGoogleLogin(req.getCode());
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }

    @PostMapping("/logout")
    @AuthErrorDocs({AuthErrorCode.INVALID_REFRESH_TOKEN,AuthErrorCode.INVALID_TOKEN })
    @Operation(summary = "로그아웃 API", description = "현재 사용자의 액세스 토큰을 블랙리스트에 등록하고, 리프레시 토큰을 삭제합니다.")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String accessToken = authHeader.substring(7);
            userService.logout(accessToken);
        }
        return ResponseEntity.noContent().build();
    }
    @Operation(summary = "애플 소셜 로그인 및 회원가입", description = "앱에서 받은 identityToken으로 애플 로그인을 처리하고 JWT를 발급합니다.")
    @PostMapping("/apple/app-login")
    @AuthErrorDocs({AuthErrorCode.INVALID_APPLE_REQUEST})
    @UserErrorDocs({UserErrorCode.DUPLICATE_RESOURCE, UserErrorCode.DUPLICATE_EMAIL_PROVIDER_MISMATCH}) // 에러 문서화
    public ResponseEntity<ApiResponse<LoginResponseDto>> loginWithApple(
            @Parameter(description = "Apple 로그인 요청 데이터", required = true)
            @RequestBody @Valid AppleLoginByCodeRequest req) {
        LoginResponseDto responseDto = appleAuthService.login(req);
        publisher.publishEvent(new UserLoggedInEvent(responseDto.userId().toString(), "apple"));
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }

    @PostMapping("/admin/login")
    @Operation(summary = "관리자 1차 로그인 (ID/PW)")
    public ResponseEntity<ApiResponse<AdminLoginStep1Response>> adminLoginStep1(
            @Valid @RequestBody EmailLoginDto req) {
        return ResponseEntity.ok(ApiResponse.success(adminAuthService.loginStep1(req)));
    }

    @PostMapping("/admin/otp-verify")
    @Operation(summary = "관리자 2차 로그인 (OTP)")
    public ResponseEntity<ApiResponse<String>> adminLoginStep2(
            @RequestBody OtpVerificationRequest req,
            HttpServletResponse httpResponse) {

        AuthResponse authResponse = adminAuthService.loginStep2(req);

        long maxAgeInSeconds = authResponse.expiresInMillis() / 1000;
        cookieUtil.createTokenCookie(httpResponse, "accessToken", authResponse.accessToken(), maxAgeInSeconds);

        return ResponseEntity.ok(ApiResponse.success("관리자 로그인 성공"));
    }

    @PostMapping("/admin/otp/reset-request")
    @Operation(summary = "관리자 OTP 초기화 메일 발송")
    public ResponseEntity<ApiResponse<String>> requestOtpReset(@RequestBody EmailRequest req) {
        adminAuthService.sendOtpResetCode(req.getEmail());
        return ResponseEntity.ok(ApiResponse.success("인증 코드가 메일로 발송되었습니다."));
    }

    @PostMapping("/admin/otp/reset-confirm")
    @Operation(summary = "관리자 OTP 초기화 수행 (코드 검증)")
    public ResponseEntity<ApiResponse<String>> confirmOtpReset(@RequestBody EmailVerificationRequest req) {
        adminAuthService.resetOtp(req.getEmail(), req.getVerificationCode());
        return ResponseEntity.ok(ApiResponse.success("OTP가 초기화되었습니다. 다시 로그인하여 재설정하세요."));
    }

    @PostMapping("/refresh")
    @AuthErrorDocs({AuthErrorCode.INVALID_REFRESH_TOKEN,AuthErrorCode.INVALID_TOKEN })
    @Operation(summary = "토큰 재발급 API", description = "리프레시 토큰으로 새로운 액세스 토큰과 리프레시 토큰을 발급합니다.")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refreshToken(@RequestBody TokenRefreshRequest request) {
        TokenRefreshResponse responseDto = userService.refreshTokens(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(responseDto));
    }

    @PostMapping("/signup")
    @Operation(summary = "일반 회원가입 및 JWT 발급")
    @UserErrorDocs({UserErrorCode.AGREEMENT_INPUT, UserErrorCode.DUPLICATE_RESOURCE, UserErrorCode.AUTHENTICATION_FAILED})
    public ResponseEntity<LoginResponseDto> signup(@Valid @RequestBody SignupRequest req) {
        LoginResponseDto res = userService.signup(req);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/send-verification-email")
    @Operation(summary = "이메일 인증 코드 발송")
    @UserErrorDocs({UserErrorCode.DUPLICATE_RESOURCE,})
    public ResponseEntity<ApiResponse<String>> sendVerificationEmail(@RequestBody EmailRequest request) {
        String tag = (request.getLang() == null || request.getLang().isBlank()) ? "en" : request.getLang();
        Locale locale = Locale.forLanguageTag(tag);   // "en" 기본
        userService.sendEmailVerificationCode(request.getEmail(), locale);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "이메일 인증 코드 검증.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "검증 성공", content = @Content(schema = @Schema(implementation = ApiResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "인증 코드 만료 또는 재발급 필요", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "인증 코드 불일치", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @AuthErrorDocs({AuthErrorCode.VERIFY_CODE_EXPIRES, AuthErrorCode.VERIFY_CODE_NEED_RESEND, AuthErrorCode.VERIFY_CODE_NOT_MATCH})
    @PostMapping("/verify-code")
    public ResponseEntity<ApiResponse<Boolean>> verifyEmailCode(@RequestBody EmailVerificationRequest request) {
        boolean result = userService.verifyEmailCode(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/email/check")
    @Operation(summary = "이메일 가입 중복 여부 확인")
    public ResponseEntity<ApiResponse<EmailCheckResponse>> checkRepeat(@RequestBody EmailCheckRequest request) {
        boolean exists = userService.existsByEmail(request.getEmail());

        String message = exists ? "중복된 이메일입니다." : "사용 가능한 이메일입니다.";
        EmailCheckResponse result = new EmailCheckResponse(exists, message);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/password/forgot")
    @Operation(summary = "비밀번호 재설정 메일 발송")
    public ResponseEntity<Void> forgotPassword(@RequestBody EmailRequest request) {
        Locale loc = (request.getLang() == null || request.getLang().isBlank())
                ? LocaleContextHolder.getLocale()
                : Locale.forLanguageTag(request.getLang());

        passwordService.sendEmailVerificationCode(request.getEmail(), loc);

        return ResponseEntity.ok().build();
    }


    @PostMapping("/password/reset-by-code")
    @Operation(summary = " 비밀번호 재설정")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<Void> resetPasswordByCode(@RequestBody ResetPasswordByCodeRequest req) {
        passwordService.verifyCodeAndResetPassword(req.getEmail(), req.getNewPassword());
        return ResponseEntity.ok().build();
    }


    @PatchMapping("/profile/setup")
    @Operation(summary = "처음 회원가입시 프로필 이미지랑 함께 자기소개 작성", description = "현재 사용자의 프로필 정보를 세팅합니다.")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND, UserErrorCode.INVALID_PROFILE})
    @ImageErrorCodeDocs({ImageErrorCode.USER_IMAGES_ALREADY_EXIST, ImageErrorCode.IMAGE_UPLOAD_FAILED, })
    public ResponseEntity<Void> updateProfile(@Valid @RequestBody UserSetupRequest dto) {
        userService.setupUserProfile(dto);
        return ResponseEntity.ok(null);
    }




    @DeleteMapping("/image")
    @Operation(summary = "프로필 이미지 삭제", description = "현재 사용자의 프로필 정보를 삭제합니다.")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    @ImageErrorCodeDocs({ImageErrorCode.IMAGE_FOLDER_DELETE_FAILED})
    public ResponseEntity<Void> deleteProfileImage() {
        userService.deleteProfileImage();
        return ResponseEntity.noContent().build();
    }

    /**
     * 사용자 프로필 조회
     */
    @GetMapping("/profile/setting")
    @Operation(summary = "프로필 수정용 조회", description = "수정을 위해 현재 사용자의 프로필 정보를 조회합니다.")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<UserProfileResponse> getProfile() {
        UserProfileResponse response = userService.getUserProfile();
        featureUsageMetrics.recordFollowUsage();
        return ResponseEntity.ok(response);
    }


    @DeleteMapping("/withdraw")
    @Operation(summary = "회원 탈퇴 API", description = "현재 로그인한 사용자의 계정을 삭제합니다")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<WithdrawIsApple> withdraw(HttpServletRequest request , @AuthenticationPrincipal CustomUserDetails principal) {
        String accessToken = jwtTokenProvider.resolveToken(request);
        boolean isapple = userWithdrawalService.withdraw(principal.getUserId(), accessToken);
        WithdrawIsApple withdrawIsApple = new WithdrawIsApple(isapple);
        return ResponseEntity.ok(withdrawIsApple);
    }


    /**
     * 여러 사용자의 프로필 정보를 한 번에 조회합니다.
     *
     * @param userIds 조회할 사용자 ID 리스트
     * @return UserResponseDto 리스트 형태의 사용자 정보
     */
    @GetMapping("/infos")
    public ResponseEntity<List<UserResponseDto>> getUsersInfo(@RequestParam("userIds") List<Long> userIds) {
        List<UserResponseDto> userProfiles = userService.findUsersProfiles(userIds);
        featureUsageMetrics.recordFollowUsage();
        return ResponseEntity.ok(userProfiles);
    }


    @Operation(summary = "애플 유저인지 판별", description = "사용자가 애플 유저인지, 그리고 이름 정보가 없는 재가입 유저인지 판별합니다.")
    @GetMapping("/{userId}/is-apple")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<ApiResponse<UserAppleStatusResponse>> getUserInfoIsApple(@PathVariable Long userId) {
        UserAppleStatusResponse response = userService.checkUserAppleStatus(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PatchMapping("/location")
    @Operation(summary = "사용자 위치 정보 업데이트",
            description = "사용자의 현재 위도, 경도를 받아 위치를 업데이트합니다. 위도/경도 중 하나라도 null이면 위치 미동의null)로 처리됩니다.")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<Void> updateUserCountry(
            @RequestBody @Valid LocationUpdateRequest dto,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        String country = null;
        if (dto.getLatitude() != null && dto.getLongitude() != null) {
            country = geoService.getCountryByLatLng(dto.getLatitude(), dto.getLongitude());
        }
        userService.updateUserResidence(userDetails.getUserId(), country);

        return ResponseEntity.ok().build();
    }

    /**
     * 유저 프로필 완료 여부 API
     */
    @GetMapping("/is-completed")
    @UserErrorDocs({UserErrorCode.USER_NOT_FOUND})
    public ResponseEntity<ProfileCompletionResponse> isProfileCompleted(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        ProfileCompletionResponse response = userService.checkProfileCompletion(userId);
        return ResponseEntity.ok(response);
    }
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = ProfileOptionsDto.CombinedResponse.class))
            )
    })
    @GetMapping("/profile-options")
    @Operation(summary = "프로필 설정 옵션 전체 조회",
            description = "프로필 설정 시 필요한 '추천 자기소개(영어)'와 '관심사 카테고리'를 한 번에 반환합니다. 기본 에러 404 등 커스텀 에러는 없습니다.")
    public ResponseEntity<ProfileOptionsDto.CombinedResponse> getProfileOptions() {
        return ResponseEntity.ok(userService.getProfileOptions());
    }
}
