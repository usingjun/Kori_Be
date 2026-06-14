package core.domain.payment.service.strategy;

import core.global.enums.DeviceType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class StorePurchaseVerificationStrategyResolver {

    private final Map<DeviceType, StorePurchaseVerificationStrategy> strategies;

    public StorePurchaseVerificationStrategyResolver(List<StorePurchaseVerificationStrategy> strategies) {
        this.strategies = new EnumMap<>(DeviceType.class);
        for (StorePurchaseVerificationStrategy strategy : strategies) {
            StorePurchaseVerificationStrategy previous =
                    this.strategies.putIfAbsent(strategy.supportedPlatform(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate purchase verification strategy: " + strategy.supportedPlatform()
                );
            }
        }
    }

    public StorePurchaseVerificationStrategy resolve(String platform) {
        DeviceType deviceType;
        try {
            deviceType = DeviceType.valueOf(platform.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported platform: " + platform, exception);
        }

        StorePurchaseVerificationStrategy strategy = strategies.get(deviceType);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported platform: " + platform);
        }
        return strategy;
    }
}
