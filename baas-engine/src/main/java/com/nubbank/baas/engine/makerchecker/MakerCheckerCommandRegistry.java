package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.common.BaasException;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MakerCheckerCommandRegistry {

    private final Map<String, MakerCheckerCommandHandler> byType;

    public MakerCheckerCommandRegistry(List<MakerCheckerCommandHandler> handlers) {
        this.byType = handlers.stream().collect(Collectors.toMap(
            MakerCheckerCommandHandler::commandType,
            Function.identity(),
            (a, b) -> { throw new IllegalStateException(
                "Duplicate maker-checker commandType '" + a.commandType() + "': "
                + a.getClass().getName() + " and " + b.getClass().getName()); }));
    }

    public MakerCheckerCommandHandler require(String commandType) {
        MakerCheckerCommandHandler h = byType.get(commandType);
        if (h == null)
            throw BaasException.badRequest("UNKNOWN_COMMAND_TYPE", "No maker-checker handler for " + commandType);
        return h;
    }

    public Optional<MakerCheckerCommandHandler> find(String commandType) {
        return Optional.ofNullable(byType.get(commandType));
    }
}
