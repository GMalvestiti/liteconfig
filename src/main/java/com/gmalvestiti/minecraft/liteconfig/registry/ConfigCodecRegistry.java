package com.gmalvestiti.minecraft.liteconfig.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Codec registrations for custom config value types.
 *
 * <p>A {@link Codec} controls file serialization and state copying. A {@link StreamCodec}
 * controls synchronization. Register either independently, or
 * chain both registrations when a type needs both:
 *
 * <pre>{@code
 * LiteConfig.codecs()
 *     .registerCodec(SomeType.class, SomeType.CODEC)
 *     .registerStreamCodec(SomeType.class, SomeType.STREAM_CODEC);
 * }</pre>
 */
public final class ConfigCodecRegistry {

    private volatile Map<Type, Registration> registrations = Map.of();
    private final Set<Type> plannedTypes = Collections.newSetFromMap(new WeakHashMap<>());
    private final AtomicInteger generation = new AtomicInteger();

    /**
     * Registers a Codec class.
     */
    public <T> ConfigCodecRegistry registerCodec(Class<T> type, Codec<T> codec) {
        return registerCodec((Type) type, codec);
    }

    /**
     * Registers a StreamCodec class.
     */
    public <T> ConfigCodecRegistry registerStreamCodec(Class<T> type, StreamCodec<ByteBuf, T> streamCodec) {
        return registerStreamCodec((Type) type, streamCodec);
    }

    /**
     * Registers a versioned StreamCodec class.
     */
    public <T> ConfigCodecRegistry registerStreamCodec(
        Class<T> type,
        String schemaVersion,
        StreamCodec<ByteBuf, T> streamCodec
    ) {
        return registerStreamCodec((Type) type, schemaVersion, streamCodec);
    }

    /**
     * Registers a tree codec for one exact reflective type.
     *
     * @param type exact field type
     * @param codec vanilla tree codec
     * @return this registry
     */
    public synchronized ConfigCodecRegistry registerCodec(Type type, Codec<?> codec) {
        Objects.requireNonNull(codec, "codec");

        Type key = requireType(type);
        requireUnplanned(key);

        Registration current = registrations.get(key);
        if (current != null && current.codec() != null) {
            throw duplicate("file", key);
        }

        put(key, new Registration(
            codec,
            current == null ? null : current.streamCodec(),
            current == null ? null : current.streamSchema()
        ));

        return this;
    }

    /**
     * Registers a network codec for one exact reflective type.
     *
     * @param type exact field type
     * @param streamCodec context-free vanilla network codec
     * @param <T> value type
     * @return this registry
     */
    public synchronized <T> ConfigCodecRegistry registerStreamCodec(
        Type type,
        StreamCodec<ByteBuf, T> streamCodec
    ) {
        return registerStreamCodec(type, "1", streamCodec);
    }

    /**
     * Registers a network codec with a stable schema version.
     *
     * <p>Increment {@code schemaVersion} whenever the codec's wire representation changes.
     */
    public synchronized <T> ConfigCodecRegistry registerStreamCodec(
        Type type,
        String schemaVersion,
        StreamCodec<ByteBuf, T> streamCodec
    ) {
        Objects.requireNonNull(streamCodec, "streamCodec");
        Objects.requireNonNull(schemaVersion, "schemaVersion");

        if (schemaVersion.isBlank() || schemaVersion.length() > 128) {
            throw new IllegalArgumentException(
                "Network codec schema version must contain 1 to 128 characters");
        }

        Type key = requireType(type);
        requireUnplanned(key);

        Registration current = registrations.get(key);
        if (current != null && current.streamCodec() != null) {
            throw duplicate("network", key);
        }

        put(key, new Registration(
            current == null ? null : current.codec(),
            streamCodec,
            schemaVersion));

        return this;
    }

    private void put(Type type, Registration registration) {
        Registration previous = registrations.get(type);

        Map<Type, Registration> next = new LinkedHashMap<>(registrations);
        next.put(type, registration);

        registrations = Map.copyOf(next);

        if (registration.codec() != null && (previous == null || previous.codec() == null)) {
            generation.incrementAndGet();
        }
    }

    /**
     * Returns a token that changes whenever a file codec is registered.
     *
     * <p> Network codec registrations do not affect this token because
     * they do not alter file serialization.
     *
     * @return the current registration generation
     */
    public int generation() {
        return generation.get();
    }

    /**
     * Prevents a type's serialization role from changing after a field plan has classified it.
     */
    public synchronized void lockForPlanning(Type type) {
        plannedTypes.add(requireType(type));
    }

    /**
     * Returns the tree codec registered for an exact type.
     *
     * @param type type to inspect
     * @return the registered vanilla codec
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<Codec<T>> find(Class<T> type) {
        return (Optional<Codec<T>>) (Optional<?>) find((Type) type);
    }

    public Optional<Codec<?>> find(Type type) {
        Registration registration = registrations.get(requireType(type));
        return registration == null ? Optional.empty() : Optional.ofNullable(registration.codec());
    }

    /**
     * Returns the network codec registered for an exact type.
     *
     * @param type type to inspect
     * @return the registered context-free stream codec
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<StreamCodec<ByteBuf, T>> findStream(Class<T> type) {
        return (Optional<StreamCodec<ByteBuf, T>>) (Optional<?>) findStream((Type) type);
    }

    public Optional<StreamCodec<ByteBuf, ?>> findStream(Type type) {
        Registration registration = registrations.get(requireType(type));
        return registration == null
            ? Optional.empty()
            : Optional.ofNullable(registration.streamCodec());
    }

    /**
     * Returns the stable schema version registered with a network codec.
     */
    public Optional<String> findStreamSchema(Type type) {
        Registration registration = registrations.get(requireType(type));
        return registration == null
            ? Optional.empty()
            : Optional.ofNullable(registration.streamSchema());
    }

    /**
     * Adds every registered {@link Codec} to a Gson builder.
     *
     * @param builder builder to configure
     * @return {@code builder}
     */
    public GsonBuilder configure(GsonBuilder builder) {
        Objects.requireNonNull(builder, "builder");

        registrations.forEach((type, registration) -> {
            if (registration.codec() != null) {
                builder.registerTypeAdapterFactory(new TreeCodecAdapterFactory(type, registration.codec()));
            }
        });

        return builder;
    }

    private static IllegalStateException duplicate(String kind, Type type) {
        return new IllegalStateException(
            "A " + kind + " codec is already registered for " + type.getTypeName());
    }

    private void requireUnplanned(Type type) {
        if (plannedTypes.contains(type)) {
            throw new IllegalStateException(
                "A config codec for " + type.getTypeName()
                    + " must be registered before a holder plans fields of that type");
        }
    }

    private static Type requireType(Type type) {
        Objects.requireNonNull(type, "type");

        if (type == Void.TYPE || type == Void.class) {
            throw new IllegalArgumentException("A config value codec cannot target void");
        }

        return type;
    }

    private record Registration(
        Codec<?> codec,
        StreamCodec<ByteBuf, ?> streamCodec,
        String streamSchema
    ) {}

    private record TreeCodecAdapterFactory(Type type, Codec<?> codec) implements TypeAdapterFactory {

        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> target) {
            return type.equals(target.getType())
                ? (TypeAdapter<T>) new TreeCodecAdapter(
                    codec,
                    gson.getAdapter(JsonElement.class)).nullSafe()
                : null;
        }
    }

    private static final class TreeCodecAdapter extends TypeAdapter<Object> {

        private final Codec<Object> codec;
        private final TypeAdapter<JsonElement> elements;

        @SuppressWarnings("unchecked")
        private TreeCodecAdapter(Codec<?> codec, TypeAdapter<JsonElement> elements) {
            this.codec = (Codec<Object>) codec;
            this.elements = elements;
        }

        @Override
        public void write(JsonWriter output, Object value) throws IOException {
            JsonElement tree = codec.encodeStart(JsonOps.INSTANCE, value)
                .getOrThrow(IllegalArgumentException::new);
            elements.write(output, tree);
        }

        @Override
        public Object read(JsonReader input) throws IOException {
            JsonElement tree = elements.read(input);
            return codec.parse(JsonOps.INSTANCE, tree)
                .getOrThrow(IllegalArgumentException::new);
        }
    }
}
