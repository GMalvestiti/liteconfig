package com.gmalvestiti.minecraft.liteconfig.storage;

import com.gmalvestiti.minecraft.liteconfig.network.codec.BuiltInTypes;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

final class ConfigTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        Class<? super T> rawType = type.getRawType();

        if (!BuiltInTypes.isTextValue(rawType)) {
            return null;
        }

        return new TypeAdapter<T>() {
            @Override
            public void write(JsonWriter output, T value) throws IOException {
                output.value(value.toString());
            }

            @Override
            public T read(JsonReader input) throws IOException {
                JsonToken token = input.peek();

                if (token != JsonToken.STRING && token != JsonToken.NUMBER) {
                    throw new JsonSyntaxException("Expected text for " + rawType.getTypeName());
                }

                try {
                    @SuppressWarnings("unchecked")
                    T value = (T) BuiltInTypes.parseText(rawType, input.nextString());
                    return value;
                } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
                    throw new JsonSyntaxException("Invalid " + rawType.getTypeName(), invalid);
                }
            }
        }.nullSafe();
    }
}
