package com.gmalvestiti.minecraft.liteconfig.engine.state.copy;

import com.gmalvestiti.minecraft.liteconfig.api.LiteConfig;
import com.gmalvestiti.minecraft.liteconfig.registry.ConfigCodecRegistry;

final class ClassCopier implements Copier {

    private static final ClassValue<ClassCopier> CACHE = new ClassValue<>() {
        @Override
        protected ClassCopier computeValue(Class<?> type) {
            return new ClassCopier(type);
        }
    };

    private final Class<?> type;
    private volatile Built built;

    private ClassCopier(Class<?> type) {
        this.type = type;
    }

    static ClassCopier of(Class<?> type) {
        return CACHE.get(type);
    }

    @Override
    public Object copy(Object source) {
        ConfigCodecRegistry codecs = LiteConfig.codecs();
        int generation = codecs.generation();
        Built current = built;

        if (current == null || current.generation != generation) {
            current = new Built(generation, build(codecs));
            built = current;
        }

        return current.copier.copy(source);
    }

    private Copier build(ConfigCodecRegistry codecs) {
        if (codecs.find(type).isPresent()) {
            return Copiers.roundTrip(type);
        }

        if (type.isRecord()) {
            Copier record = RecordCopier.create(type, codecs);
            return record != null ? record : Copiers.roundTrip(type);
        }

        return new ObjectCopier(type, codecs);
    }

    private record Built(int generation, Copier copier) {}
}
