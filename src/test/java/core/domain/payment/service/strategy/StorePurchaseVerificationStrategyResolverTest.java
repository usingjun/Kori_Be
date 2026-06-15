package core.domain.payment.service.strategy;

import core.global.enums.DeviceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorePurchaseVerificationStrategyResolverTest {

    @Test
    void resolve_returnsRegisteredStrategyIgnoringCase() {
        StorePurchaseVerificationStrategy ios = strategy(DeviceType.IOS);
        StorePurchaseVerificationStrategy android = strategy(DeviceType.ANDROID);
        StorePurchaseVerificationStrategyResolver resolver =
                new StorePurchaseVerificationStrategyResolver(List.of(ios, android));

        assertThat(resolver.resolve("ios")).isSameAs(ios);
        assertThat(resolver.resolve("IOS")).isSameAs(ios);
        assertThat(resolver.resolve("android")).isSameAs(android);
        assertThat(resolver.resolve("ANDROID")).isSameAs(android);
    }

    @Test
    void resolve_rejectsUnsupportedPlatform() {
        StorePurchaseVerificationStrategyResolver resolver =
                new StorePurchaseVerificationStrategyResolver(List.of(strategy(DeviceType.IOS)));

        assertThatThrownBy(() -> resolver.resolve("web"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported platform: web");
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported platform: null");
    }

    @Test
    void constructor_rejectsDuplicatePlatformStrategies() {
        assertThatThrownBy(() -> new StorePurchaseVerificationStrategyResolver(
                List.of(strategy(DeviceType.IOS), strategy(DeviceType.IOS))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate purchase verification strategy: IOS");
    }

    private StorePurchaseVerificationStrategy strategy(DeviceType platform) {
        StorePurchaseVerificationStrategy strategy = mock(StorePurchaseVerificationStrategy.class);
        when(strategy.supportedPlatform()).thenReturn(platform);
        return strategy;
    }
}
